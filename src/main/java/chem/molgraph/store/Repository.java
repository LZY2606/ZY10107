package chem.molgraph.store;

import chem.molgraph.domain.Molecule;
import chem.molgraph.domain.RuleDef;
import chem.molgraph.domain.TransformResult;
import chem.molgraph.engine.Canonical;
import chem.molgraph.engine.TransformService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class Repository {

    private final EventStore eventStore;
    private final ObjectMapper mapper;
    private final TransformService transformService;

    private final Map<String, StoredRule> rules = new LinkedHashMap<>();
    private final Map<String, StoredCase> cases = new LinkedHashMap<>();
    private final Map<String, BatchRecord> batches = new LinkedHashMap<>();
    private final Map<String, CompositionRecord> compositions = new LinkedHashMap<>();
    private final Map<String, MigrationReport> migrations = new LinkedHashMap<>();
    private final Map<String, TransformResult> analysisByCase = new LinkedHashMap<>();

    public Repository(EventStore eventStore, ObjectMapper mapper, TransformService transformService) {
        this.eventStore = eventStore;
        this.mapper = mapper;
        this.transformService = transformService;
    }

    @PostConstruct
    public void replay() {
        for (Event event : eventStore.events()) {
            apply(event);
        }
    }

    private void apply(Event event) {
        try {
            switch (event.type()) {
                case "RuleReceived" -> {
                    RuleReceived p = read(event, RuleReceived.class);
                    rules.put(p.ruleId(), new StoredRule(p.ruleId(), p.name(), p.version(), p.def(),
                            p.fingerprint(), event.seq()));
                }
                case "CaseReceived" -> {
                    CaseReceived p = read(event, CaseReceived.class);
                    cases.put(p.caseId(), new StoredCase(p.caseId(), p.title(), p.evidence(),
                            p.evidenceFingerprint(), event.timestamp(), StoredCase.IMPORTED,
                            null, null, null, null, new ArrayList<>(), null, event.seq()));
                }
                case "CaseAnalyzed" -> {
                    CaseAnalyzed p = read(event, CaseAnalyzed.class);
                    StoredCase c = requireCase(p.caseId());
                    analysisByCase.put(p.caseId(), p.result());
                    String newStatus = hasLocked(c) ? StoredCase.LOCKED : StoredCase.ANALYZED;
                    cases.put(p.caseId(), new StoredCase(c.caseId(), c.title(), c.evidence(),
                            c.evidenceFingerprint(), c.receivedAt(), newStatus,
                            p.ruleId(), p.ruleVersion(), p.ruleFingerprint(), p.result().status(),
                            c.certificates(), c.parentCaseId(), event.seq()));
                }
                case "MappingLocked" -> {
                    MappingLocked p = read(event, MappingLocked.class);
                    StoredCase c = requireCase(p.caseId());
                    List<MappingCertificate> certs = new ArrayList<>(c.certificates());
                    certs.removeIf(existing -> existing.candidateFingerprint()
                            .equals(p.certificate().candidateFingerprint()));
                    certs.add(p.certificate());
                    cases.put(p.caseId(), new StoredCase(c.caseId(), c.title(), c.evidence(),
                            c.evidenceFingerprint(), c.receivedAt(), StoredCase.LOCKED,
                            c.ruleId(), c.ruleVersion(), c.ruleFingerprint(), c.lastTransformStatus(),
                            List.copyOf(certs), c.parentCaseId(), event.seq()));
                }
                case "CaseConfirmed" -> {
                    CaseConfirmed p = read(event, CaseConfirmed.class);
                    StoredCase c = requireCase(p.caseId());
                    cases.put(p.caseId(), new StoredCase(c.caseId(), c.title(), c.evidence(),
                            c.evidenceFingerprint(), c.receivedAt(), StoredCase.CONFIRMED,
                            c.ruleId(), c.ruleVersion(), c.ruleFingerprint(), c.lastTransformStatus(),
                            c.certificates(), c.parentCaseId(), event.seq()));
                }
                case "CaseRevalidated" -> {
                    CaseRevalidated p = read(event, CaseRevalidated.class);
                    StoredCase c = requireCase(p.caseId());
                    analysisByCase.put(p.caseId(), p.result());
                    String status = c.status();
                    if (StoredCase.CONFIRMED.equals(status)) {
                        status = StoredCase.STALE_RULE;
                    } else if (StoredCase.LOCKED.equals(status) || c.certificates().isEmpty()) {
                        status = hasLocked(c) ? StoredCase.LOCKED : StoredCase.ANALYZED;
                    }
                    cases.put(p.caseId(), new StoredCase(c.caseId(), c.title(), c.evidence(),
                            c.evidenceFingerprint(), c.receivedAt(), status,
                            c.ruleId(), c.ruleVersion(), c.ruleFingerprint(), p.result().status(),
                            c.certificates(), c.parentCaseId(), event.seq()));
                }
                case "CaseMigrated" -> {
                    CaseMigrated p = read(event, CaseMigrated.class);
                    migrations.put(p.migrationId(), p.report());
                    TransformResult nr = p.report().newResult();
                    StoredCase nc = new StoredCase(p.newCaseId(),
                            cases.get(p.sourceCaseId()).title() + " [迁移 " + p.newRuleVersion() + "]",
                            cases.get(p.sourceCaseId()).evidence(),
                            cases.get(p.sourceCaseId()).evidenceFingerprint(), event.timestamp(),
                            StoredCase.ANALYZED,
                            rules.values().stream()
                                    .filter(r -> r.version().equals(p.newRuleVersion())
                                            && r.name().equals(p.report().newResult().ruleName()))
                                    .map(StoredRule::ruleId).findFirst().orElse(null),
                            p.newRuleVersion(),
                            nr.ruleFingerprint(), nr.status(), new ArrayList<>(),
                            p.sourceCaseId(), event.seq());
                    cases.put(p.newCaseId(), nc);
                    analysisByCase.put(p.newCaseId(), nr);
                }
                case "BatchRun" -> {
                    BatchRun p = read(event, BatchRun.class);
                    batches.put(p.batchId(), new BatchRecord(p.batchId(), p.ruleId(),
                            List.copyOf(p.caseIds()), List.copyOf(p.results()),
                            p.defaultCaseId(), p.defaultFingerprint(), event.timestamp(), event.seq()));
                }
                case "CompositionSaved" -> {
                    CompositionSaved p = read(event, CompositionSaved.class);
                    compositions.put(p.record().compositionId(),
                            new CompositionRecord(p.record().compositionId(), p.record().firstRuleId(),
                                    p.record().secondRuleId(), p.record().status(),
                                    p.record().intermediate(), p.record().finalResult(),
                                    p.record().failedReason(), event.timestamp(), event.seq()));
                }
                default -> throw new IllegalStateException("未知事件类型: " + event.type());
            }
        } catch (Exception e) {
            throw new EventStoreException("重放事件 " + event.seq() + " (" + event.type() + ") 失败: " + e.getMessage(), e);
        }
    }

    private boolean hasLocked(StoredCase c) {
        return !c.certificates().isEmpty();
    }

    public StoredRule receiveRule(String ruleId, RuleDef def) {
        String fingerprint = transformService.ruleFingerprint(def);
        apply(eventStore.append("RuleReceived", new RuleReceived(ruleId, def.name(), def.version(), def, fingerprint)));
        return rules.get(ruleId);
    }

    public StoredCase receiveCase(String caseId, String title, Molecule evidence) {
        String fp = Canonical.canonicalize(evidence).fingerprint();
        apply(eventStore.append("CaseReceived", new CaseReceived(caseId, title, evidence, fp)));
        return cases.get(caseId);
    }

    public StoredCase analyze(String caseId, StoredRule rule, TransformResult result) {
        apply(eventStore.append("CaseAnalyzed", new CaseAnalyzed(caseId, rule.ruleId(),
                rule.version(), rule.fingerprint(), result)));
        return cases.get(caseId);
    }

    public StoredCase lock(String caseId, MappingCertificate certificate) {
        apply(eventStore.append("MappingLocked", new MappingLocked(caseId, certificate)));
        return cases.get(caseId);
    }

    public StoredCase confirm(String caseId) {
        apply(eventStore.append("CaseConfirmed", new CaseConfirmed(caseId)));
        return cases.get(caseId);
    }

    public StoredCase revalidate(String caseId, TransformResult result) {
        apply(eventStore.append("CaseRevalidated", new CaseRevalidated(caseId, result)));
        return cases.get(caseId);
    }

    public StoredCase migrate(String migrationId, String sourceCaseId, String newCaseId,
                              String newRuleVersion, MigrationReport report) {
        apply(eventStore.append("CaseMigrated",
                new CaseMigrated(migrationId, sourceCaseId, newCaseId, newRuleVersion, report)));
        return cases.get(newCaseId);
    }

    public BatchRecord saveBatch(BatchRecord record) {
        apply(eventStore.append("BatchRun", new BatchRun(record.batchId(), record.ruleId(),
                record.caseIds(), record.results(), record.defaultCaseId(), record.defaultFingerprint())));
        return batches.get(record.batchId());
    }

    public CompositionRecord saveComposition(CompositionRecord record) {
        apply(eventStore.append("CompositionSaved", new CompositionSaved(record)));
        return compositions.get(record.compositionId());
    }

    private StoredCase requireCase(String caseId) {
        StoredCase c = cases.get(caseId);
        if (c == null) {
            throw new EventStoreException("案例不存在: " + caseId);
        }
        return c;
    }

    private <T> T read(Event event, Class<T> type) throws Exception {
        return mapper.readValue(event.payloadJson(), type);
    }

    public Optional<StoredRule> rule(String ruleId) {
        return Optional.ofNullable(rules.get(ruleId));
    }

    public List<StoredRule> rules() {
        return List.copyOf(rules.values());
    }

    public Optional<StoredCase> caseRecord(String caseId) {
        return Optional.ofNullable(cases.get(caseId));
    }

    public List<StoredCase> cases() {
        return List.copyOf(cases.values());
    }

    public Optional<TransformResult> analysis(String caseId) {
        return Optional.ofNullable(analysisByCase.get(caseId));
    }

    public List<BatchRecord> batches() {
        return List.copyOf(batches.values());
    }

    public List<CompositionRecord> compositions() {
        return List.copyOf(compositions.values());
    }

    public List<MigrationReport> migrations() {
        return List.copyOf(migrations.values());
    }

    public Optional<MigrationReport> migration(String id) {
        return Optional.ofNullable(migrations.get(id));
    }

    public long version() {
        return eventStore.currentVersion();
    }

    public String headHash() {
        return eventStore.headHash();
    }

    public EventStore eventStore() {
        return eventStore;
    }

    private record RuleReceived(String ruleId, String name, String version, RuleDef def, String fingerprint) {
    }

    private record CaseReceived(String caseId, String title, Molecule evidence, String evidenceFingerprint) {
    }

    private record CaseAnalyzed(String caseId, String ruleId, String ruleVersion,
                                String ruleFingerprint, TransformResult result) {
    }

    private record MappingLocked(String caseId, MappingCertificate certificate) {
    }

    private record CaseConfirmed(String caseId) {
    }

    private record CaseRevalidated(String caseId, TransformResult result) {
    }

    private record CaseMigrated(String migrationId, String sourceCaseId, String newCaseId,
                                String newRuleVersion, MigrationReport report) {
    }

    private record BatchRun(String batchId, String ruleId, List<String> caseIds,
                            List<TransformResult> results, String defaultCaseId,
                            String defaultFingerprint) {
    }

    private record CompositionSaved(CompositionRecord record) {
    }
}
