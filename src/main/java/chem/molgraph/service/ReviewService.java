package chem.molgraph.service;

import chem.molgraph.domain.CandidateView;
import chem.molgraph.domain.Issue;
import chem.molgraph.domain.Molecule;
import chem.molgraph.domain.RuleDef;
import chem.molgraph.domain.TransformResult;
import chem.molgraph.engine.Canonical;
import chem.molgraph.engine.RuleValidator;
import chem.molgraph.engine.TransformService;
import chem.molgraph.store.BatchRecord;
import chem.molgraph.store.CompositionRecord;
import chem.molgraph.store.MappingCertificate;
import chem.molgraph.store.MappingDrift;
import chem.molgraph.store.MigrationReport;
import chem.molgraph.store.NotFoundException;
import chem.molgraph.store.Repository;
import chem.molgraph.store.StoredCase;
import chem.molgraph.store.StoredRule;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ReviewService {

    private final Repository repository;
    private final TransformService transformService;

    public ReviewService(Repository repository, TransformService transformService) {
        this.repository = repository;
        this.transformService = transformService;
    }

    public StoredRule importRule(String ruleId, RuleDef def) {
        List<Issue> issues = RuleValidator.validateRule(def);
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException("规则无效: " + issues.get(0).code());
        }
        Optional<StoredRule> existing = repository.rule(ruleId);
        if (existing.isPresent()) {
            StoredRule old = existing.get();
            if (!old.version().equals(def.version())) {
                throw new IllegalArgumentException("规则已存在但版本不同；版本不可原地改写，请使用新版本编号。");
            }
            if (!old.fingerprint().equals(transformService.ruleFingerprint(def))) {
                throw new IllegalArgumentException("同版本规则内容不同；版本不可原地改写。");
            }
            return old;
        }
        return repository.receiveRule(ruleId, def);
    }

    public StoredCase importCase(String caseId, String title, Molecule evidence) {
        if (repository.caseRecord(caseId).isPresent()) {
            throw new IllegalArgumentException(
                    "证据案例 ID 已存在: " + caseId + "。原始证据一经接收不可原地改写，请使用新 ID 追加。");
        }
        List<Issue> issues = RuleValidator.validateMolecule(evidence);
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException("证据结构无效: " + issues.get(0).code());
        }
        return repository.receiveCase(caseId, title, evidence);
    }

    public StoredCase analyze(String caseId, String ruleId, Long expectedVersion) {
        StoredCase c = repository.caseRecord(caseId)
                .orElseThrow(() -> new NotFoundException("案例不存在: " + caseId));
        StoredRule r = repository.rule(ruleId)
                .orElseThrow(() -> new NotFoundException("规则不存在: " + ruleId));
        checkVersion(expectedVersion);
        TransformResult result = transformService.transform(r.def(), c.evidence());
        return repository.analyze(caseId, r, result);
    }

    public StoredCase lockMapping(String caseId, String candidateFingerprint, Long expectedVersion) {
        StoredCase c = repository.caseRecord(caseId)
                .orElseThrow(() -> new NotFoundException("案例不存在: " + caseId));
        TransformResult result = repository.analysis(caseId)
                .orElseThrow(() -> new IllegalStateException("案例尚未分析，无法锁定映射。"));
        checkVersion(expectedVersion);
        CandidateView candidate = result.candidates().stream()
                .filter(v -> v.fingerprint().equals(candidateFingerprint))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("候选产物不存在: " + candidateFingerprint));
        long baseVersion = repository.version();
        String certificateId = "cert-" + caseId + "-" + candidateFingerprint.substring(0, 12);
        String chainHash = Canonical.sha256(repository.headHash()
                + "|" + caseId + "|" + candidateFingerprint
                + "|" + result.evidenceFingerprint() + "|" + result.ruleFingerprint());
        MappingCertificate certificate = new MappingCertificate(
                certificateId, caseId, candidateFingerprint, result.ruleFingerprint(),
                result.ruleName(), result.ruleVersion(), result.evidenceFingerprint(),
                Map.copyOf(candidate.representativeMapping()), result,
                List.of(candidate.canonical()), baseVersion, chainHash, System.currentTimeMillis());
        return repository.lock(caseId, certificate);
    }

    public StoredCase revalidate(String caseId, String ruleId, Long expectedVersion) {
        StoredCase c = repository.caseRecord(caseId)
                .orElseThrow(() -> new NotFoundException("案例不存在: " + caseId));
        StoredRule r = repository.rule(ruleId)
                .orElseThrow(() -> new NotFoundException("规则不存在: " + ruleId));
        checkVersion(expectedVersion);
        TransformResult result = transformService.transform(r.def(), c.evidence());
        return repository.revalidate(caseId, result);
    }

    public StoredCase confirm(String caseId, Long expectedVersion) {
        StoredCase c = repository.caseRecord(caseId)
                .orElseThrow(() -> new NotFoundException("案例不存在: " + caseId));
        if (c.certificates().isEmpty()) {
            throw new IllegalStateException("必须先锁定一个映射才能确认案例。");
        }
        checkVersion(expectedVersion);
        return repository.confirm(caseId);
    }

    /**
     * Migrating a confirmed case never rewrites it. A new case is derived and linked
     * back to the source; the report lists conclusion changes and mapping drift.
     */
    public MigrationReport migrate(String sourceCaseId, String newRuleId, String newCaseId,
                                   String migrationId, Long expectedVersion) {
        StoredCase source = repository.caseRecord(sourceCaseId)
                .orElseThrow(() -> new NotFoundException("案例不存在: " + sourceCaseId));
        StoredRule oldRule = repository.rule(source.ruleId())
                .orElseThrow(() -> new NotFoundException("原规则缺失。"));
        StoredRule newRule = repository.rule(newRuleId)
                .orElseThrow(() -> new NotFoundException("新规则不存在: " + newRuleId));
        checkVersion(expectedVersion);

        TransformResult oldResult = source.certificates().isEmpty()
                ? repository.analysis(sourceCaseId)
                        .orElseGet(() -> transformService.transform(oldRule.def(), source.evidence()))
                : source.certificates().get(0).transformResult();
        TransformResult newResult = transformService.transform(newRule.def(), source.evidence());

        List<MappingDrift> drifts = diffCandidates(oldResult, newResult);
        String conclusion = classify(oldResult, newResult, drifts);

        MigrationReport report = new MigrationReport(migrationId, sourceCaseId, newCaseId,
                oldRule.version(), newRule.version(), conclusion, List.copyOf(drifts),
                oldResult, newResult, System.currentTimeMillis());
        repository.migrate(migrationId, sourceCaseId, newCaseId, newRule.version(), report);
        return report;
    }

    private List<MappingDrift> diffCandidates(TransformResult oldResult, TransformResult newResult) {
        List<MappingDrift> drifts = new ArrayList<>();
        Map<String, CandidateView> newByFp = new java.util.LinkedHashMap<>();
        for (CandidateView c : newResult.candidates()) {
            newByFp.put(c.fingerprint(), c);
        }
        Map<String, CandidateView> oldByFp = new java.util.LinkedHashMap<>();
        for (CandidateView c : oldResult.candidates()) {
            oldByFp.put(c.fingerprint(), c);
        }
        for (CandidateView old : oldResult.candidates()) {
            CandidateView fresh = newByFp.get(old.fingerprint());
            if (fresh == null) {
                drifts.add(new MappingDrift(old.fingerprint(), null, old.mappingCount(), 0,
                        MappingDrift.DISAPPEARED));
            } else if (fresh.mappingCount() != old.mappingCount()) {
                drifts.add(new MappingDrift(old.fingerprint(), fresh.fingerprint(),
                        old.mappingCount(), fresh.mappingCount(), MappingDrift.COUNT_CHANGED));
            } else {
                drifts.add(new MappingDrift(old.fingerprint(), fresh.fingerprint(),
                        old.mappingCount(), fresh.mappingCount(), MappingDrift.SAME));
            }
        }
        for (CandidateView fresh : newResult.candidates()) {
            if (!oldByFp.containsKey(fresh.fingerprint())) {
                drifts.add(new MappingDrift(null, fresh.fingerprint(), 0, fresh.mappingCount(),
                        MappingDrift.NEW_PRODUCT));
            }
        }
        return drifts;
    }

    private String classify(TransformResult oldResult, TransformResult newResult,
                            List<MappingDrift> drifts) {
        if (!oldResult.status().equals(newResult.status())) {
            return MigrationReport.CONCLUSION_BROKEN;
        }
        boolean structuralChange = drifts.stream().anyMatch(d ->
                MappingDrift.DISAPPEARED.equals(d.kind()) || MappingDrift.NEW_PRODUCT.equals(d.kind()));
        if (structuralChange) {
            return MigrationReport.CONCLUSION_BROKEN;
        }
        boolean countChange = drifts.stream().anyMatch(d -> MappingDrift.COUNT_CHANGED.equals(d.kind()));
        return countChange ? MigrationReport.CONCLUSION_DRIFT : MigrationReport.CONCLUSION_COMPATIBLE;
    }

    public BatchRecord runBatch(String batchId, String ruleId, List<String> caseIds,
                                Long expectedVersion) {
        StoredRule rule = repository.rule(ruleId)
                .orElseThrow(() -> new NotFoundException("规则不存在: " + ruleId));
        checkVersion(expectedVersion);
        List<TransformResult> results = new ArrayList<>();
        String defaultCaseId = null;
        String defaultFingerprint = null;
        for (String caseId : caseIds) {
            StoredCase c = repository.caseRecord(caseId)
                    .orElseThrow(() -> new NotFoundException("案例不存在: " + caseId));
            TransformResult result = transformService.transform(rule.def(), c.evidence());
            results.add(result);
            if (defaultCaseId == null && !result.candidates().isEmpty()) {
                defaultCaseId = caseId;
                defaultFingerprint = result.candidates().get(0).fingerprint();
            }
        }
        return repository.saveBatch(new BatchRecord(batchId, ruleId, List.copyOf(caseIds),
                List.copyOf(results), defaultCaseId, defaultFingerprint, 0, 0));
    }

    /**
     * Apply rule A then rule B. Failure modes are explicit and nothing is persisted on
     * failure (no half result):
     *  - INVALID_RULE / NO_MATCH on either stage
     *  - TEMP_ID_PROVENANCE_DEPENDENCY: B requires an atom tagged as originating from A,
     *    i.e. it depends on A's temporary numbering
     *  - TEMP_ID_RENAME_SENSITIVE: B's default product changes when temporary ids are
     *    renamed (order-stable naming required for chaining)
     */
    public CompositionRecord compose(String compositionId, String caseId,
                                     String firstRuleId, String secondRuleId,
                                     boolean persist, Long expectedVersion) {
        StoredCase c = repository.caseRecord(caseId)
                .orElseThrow(() -> new NotFoundException("案例不存在: " + caseId));
        StoredRule first = repository.rule(firstRuleId)
                .orElseThrow(() -> new NotFoundException("规则不存在: " + firstRuleId));
        StoredRule second = repository.rule(secondRuleId)
                .orElseThrow(() -> new NotFoundException("规则不存在: " + secondRuleId));
        if (persist) {
            checkVersion(expectedVersion);
        }

        TransformResult stage1 = transformService.transform(first.def(), c.evidence());
        if (!TransformResult.OK.equals(stage1.status()) && !TransformResult.LIMIT_REACHED.equals(stage1.status())) {
            CompositionRecord failed = new CompositionRecord(compositionId, firstRuleId, secondRuleId,
                    stage1.status(), stage1, null,
                    "第一条规则未产生可用产物: " + stage1.status(), System.currentTimeMillis(), 0);
            if (persist) {
                repository.saveComposition(failed);
            }
            return failed;
        }

        Molecule intermediate = stage1.candidates().get(0).product();
        TransformResult stage2 = transformService.transform(second.def(), intermediate);
        if (!TransformResult.OK.equals(stage2.status()) && !TransformResult.LIMIT_REACHED.equals(stage2.status())) {
            RuleDef relaxed = stripOriginTags(second.def());
            TransformResult relaxedStage2 = transformService.transform(relaxed, intermediate);
            boolean tagSensitive = second.def().pattern().atoms().stream()
                    .anyMatch(a -> a.originTag() != null)
                    && (TransformResult.OK.equals(relaxedStage2.status())
                        || TransformResult.LIMIT_REACHED.equals(relaxedStage2.status()));
            if (tagSensitive) {
                CompositionRecord failed = new CompositionRecord(compositionId, firstRuleId, secondRuleId,
                        "TEMP_ID_PROVENANCE_DEPENDENCY", stage1, stage2,
                        "第二条规则要求引用第一条规则产生的临时编号来源标记：带标记时匹配失败，去除来源标记后可以匹配。",
                        System.currentTimeMillis(), 0);
                if (persist) {
                    repository.saveComposition(failed);
                }
                return failed;
            }
            CompositionRecord failed = new CompositionRecord(compositionId, firstRuleId, secondRuleId,
                    stage2.status(), stage1, stage2,
                    "第二条规则执行失败: " + stage2.status(), System.currentTimeMillis(), 0);
            if (persist) {
                repository.saveComposition(failed);
            }
            return failed;
        }

        Molecule renamed = renameTemporaryIds(intermediate, "x");
        TransformResult stage2Renamed = transformService.transform(second.def(), renamed);
        boolean renameStable = stage2.candidates().isEmpty()
                ? stage2Renamed.candidates().isEmpty()
                : !stage2Renamed.candidates().isEmpty()
                && stage2.candidates().get(0).fingerprint()
                .equals(stage2Renamed.candidates().get(0).fingerprint());
        if (!renameStable) {
            CompositionRecord failed = new CompositionRecord(compositionId, firstRuleId, secondRuleId,
                    "TEMP_ID_RENAME_SENSITIVE", stage1, stage2,
                    "第二条规则对第一条产生的临时编号敏感：重命名临时编号后默认候选发生变化。",
                    System.currentTimeMillis(), 0);
            if (persist) {
                repository.saveComposition(failed);
            }
            return failed;
        }

        CompositionRecord record = new CompositionRecord(compositionId, firstRuleId, secondRuleId,
                "OK", stage1, stage2, null, System.currentTimeMillis(), 0);
        if (persist) {
            return repository.saveComposition(record);
        }
        return record;
    }

    private static RuleDef stripOriginTags(RuleDef def) {
        List<chem.molgraph.domain.Atom> atoms = new ArrayList<>();
        for (chem.molgraph.domain.Atom a : def.pattern().atoms()) {
            atoms.add(new chem.molgraph.domain.Atom(a.id(), a.element(), a.isotope(), a.charge(),
                    a.stereo(), a.stereoOrder(), null, a.x(), a.y()));
        }
        Molecule pattern = new Molecule(atoms, def.pattern().bonds());
        return new RuleDef(def.name(), def.version(), pattern, def.deleteAtoms(), def.addAtoms(),
                def.deleteBonds(), def.addBonds(), def.description());
    }

    private static chem.molgraph.domain.Molecule renameTemporaryIds(
            chem.molgraph.domain.Molecule mol, String salt) {
        Map<String, String> renaming = new java.util.LinkedHashMap<>();
        int counter = 0;
        for (chem.molgraph.domain.Atom a : mol.atoms()) {
            if (a.id().startsWith("new:")) {
                renaming.put(a.id(), "tmp" + salt + ":" + (counter++));
            }
        }
        if (renaming.isEmpty()) {
            return mol;
        }
        List<chem.molgraph.domain.Atom> atoms = new ArrayList<>();
        for (chem.molgraph.domain.Atom a : mol.atoms()) {
            List<String> order = null;
            if (a.stereoOrder() != null) {
                order = new ArrayList<>();
                for (String n : a.stereoOrder()) {
                    order.add(renaming.getOrDefault(n, n));
                }
            }
            String id = renaming.getOrDefault(a.id(), a.id());
            atoms.add(new chem.molgraph.domain.Atom(id, a.element(), a.isotope(), a.charge(),
                    a.stereo(), order, a.originTag(), a.x(), a.y()));
        }
        List<chem.molgraph.domain.Bond> bonds = new ArrayList<>();
        for (chem.molgraph.domain.Bond b : mol.bonds()) {
            bonds.add(new chem.molgraph.domain.Bond(
                    renaming.getOrDefault(b.a(), b.a()),
                    renaming.getOrDefault(b.b(), b.b()), b.order(), b.stereo()));
        }
        return new Molecule(atoms, bonds);
    }

    private void checkVersion(Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != repository.version()) {
            throw new chem.molgraph.store.ConflictException(expectedVersion, repository.version(),
                    "数据版本已变化（期望 " + expectedVersion + "，实际 " + repository.version()
                            + "）。请重新加载冲突内容后再合并提交。");
        }
    }
}
