package com.molmap.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.molmap.model.MolGraph;
import com.molmap.rule.Candidate;
import com.molmap.rule.Fingerprints;
import com.molmap.rule.Json;
import com.molmap.rule.MatchEngine;
import com.molmap.rule.Rule;
import com.molmap.rule.RunResult;
import com.molmap.store.CaseRepository;
import com.molmap.store.CaseState;
import com.molmap.store.Certificate;
import com.molmap.store.EvidenceStore;
import com.molmap.store.PersistedCandidate;
import com.molmap.store.RuleRepository;
import com.molmap.store.RuleState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Case lifecycle: run, review, lock/re-validate, and optimistic concurrency. */
public final class CaseService {

    public static final int DEFAULT_LIMIT = 1000;

    public static final String ST_PENDING = "PENDING";
    public static final String ST_NO_MATCH = "NO_MATCH";
    public static final String ST_LIMIT = "LIMIT_REACHED";
    public static final String ST_INVALID_RULE = "INVALID_RULE";
    public static final String ST_INVALID_INPUT = "INVALID_INPUT";
    public static final String ST_CONFIRMED = "CONFIRMED";

    private final CaseRepository cases;
    private final RuleRepository rules;
    private final RuleService ruleService;
    private final EvidenceStore evidence;
    private final MatchEngine engine = new MatchEngine();

    public CaseService(CaseRepository cases, RuleRepository rules,
                       RuleService ruleService, EvidenceStore evidence) {
        this.cases = cases;
        this.rules = rules;
        this.ruleService = ruleService;
        this.evidence = evidence;
    }

    public record Created(CaseState state, RunResult result) {}

    public Created create(CreateCaseRequest req, long now) {
        RuleState rs = resolveRule(req.ruleId(), req.ruleVersion());
        Rule rule = ruleService.toRule(rs);

        String rawInput = canonicalJson(req.input());
        // (helper defined at bottom of class)
        String inputHash = evidence.put(rawInput);
        MolGraph input = Json.mapper().convertValue(req.input(), MolGraph.class);
        int limit = req.matchLimit() == null ? DEFAULT_LIMIT : req.matchLimit();

        RunResult run = engine.run(rule, input, limit);
        String status = mapStatus(run.status());

        List<PersistedCandidate> persisted = new ArrayList<>();
        for (Candidate c : run.candidates()) {
            persisted.add(toPersisted(c, rule));
        }
        String inputFp = run.candidates().isEmpty()
                ? fingerprintOrNull(input) : run.candidates().get(0).inputFingerprint();

        List<String> unresolved = collectUnresolved(run);
        CaseState state = new CaseState("case-" + UUID.randomUUID().toString().substring(0, 8),
                rs.id(), rule.versionRef(), rule.getFingerprint(), now, now, 1,
                status, inputHash, inputFp, persisted, null, null, null, unresolved);
        return new Created(cases.create(state), run);
    }

    public RuleState resolveRule(String ruleId, Integer ruleVersion) {
        Optional<RuleState> rs = ruleVersion == null
                ? rules.findLatest(ruleId)
                : rules.findVersion(ruleId, ruleVersion);
        return rs.orElseThrow(() -> new NotFoundException("rule not found: "
                + ruleId + (ruleVersion == null ? " (latest)" : "@v" + ruleVersion)));
    }

    public CaseState lock(String caseId, LockRequest req, long now) {
        CaseState current = cases.find(caseId).orElseThrow(() -> new NotFoundException("case not found: " + caseId));
        RuleState rs = rules.findVersion(current.ruleId(), versionOf(current.ruleVersionRef()))
                .orElseThrow(() -> new NotFoundException("pinned rule version missing: " + current.ruleVersionRef()));

        PersistedCandidate chosen = current.candidates().stream()
                .filter(c -> c.index() == req.candidateIndex()).findFirst()
                .orElseThrow(() -> new BadRequestException("candidate index "
                        + req.candidateIndex() + " does not exist"));

        // Re-validate the locked mapping against the SAME pinned rule version,
        // proving the stored derived candidate is still reproducible.
        Rule rule = ruleService.toRule(rs);
        MolGraph input = graphFromEvidence(current.inputEvidenceHash());
        RunResult reRun = engine.run(rule, input, DEFAULT_LIMIT);
        Candidate recomputed = reRun.candidates().stream()
                .filter(c -> c.productFingerprint().equals(chosen.productFingerprint())
                        && c.orbitSignature().equals(chosen.orbitSignature()))
                .findFirst()
                .orElseThrow(() -> new ConflictRuntimeException(
                        "REVALIDATION_FAILED", "locked mapping no longer reproduces under "
                                + current.ruleVersionRef()));

        if (!recomputed.acceptable()) {
            throw new ConflictRuntimeException("REVALIDATION_VIOLATIONS",
                    "locked candidate still carries violations: "
                            + recomputed.violations().toString());
        }

        Certificate cert = buildCertificate(current, chosen, now);
        CaseState next = new CaseState(current.id(), current.ruleId(), current.ruleVersionRef(),
                current.ruleFingerprint(), current.createdAt(), now, current.version(),
                ST_CONFIRMED, current.inputEvidenceHash(), current.inputFingerprint(),
                current.candidates(), new LinkedHashMap<>(recomputed.mapping()),
                chosen.index(), cert, current.unresolvedReasons());
        return cases.update(next, req.caseVersion());
    }

    public CaseState unlock(String caseId, long expectedVersion, long now) {
        CaseState current = cases.find(caseId).orElseThrow(() -> new NotFoundException("case not found: " + caseId));
        CaseState next = new CaseState(current.id(), current.ruleId(), current.ruleVersionRef(),
                current.ruleFingerprint(), current.createdAt(), now, current.version(),
                ST_PENDING, current.inputEvidenceHash(), current.inputFingerprint(),
                current.candidates(), null, null, null, current.unresolvedReasons());
        return cases.update(next, expectedVersion);
    }

    public List<CaseState> list() { return cases.findAll(); }
    public Optional<CaseState> get(String id) { return cases.find(id); }

    private Certificate buildCertificate(CaseState current, PersistedCandidate c, long now) {
        String body = String.join("|", current.id(), current.ruleVersionRef(),
                current.ruleFingerprint(), current.inputEvidenceHash(),
                c.productFingerprint(), c.orbitSignature(), String.valueOf(c.index()),
                        String.valueOf(c.multiplicity()));
        return new Certificate(current.id(), current.ruleVersionRef(), current.ruleFingerprint(),
                current.inputEvidenceHash(), current.inputFingerprint(), c.productFingerprint(),
                c.mapping(), c.index(), c.multiplicity(), now, Fingerprints.sha256(body));
    }

    private PersistedCandidate toPersisted(Candidate c, Rule rule) {
        JsonNode productNode = Json.mapper().valueToTree(c.product());
        return new PersistedCandidate(c.index(), new LinkedHashMap<>(c.mapping()), productNode,
                c.inputFingerprint(), c.productFingerprint(), c.orbitSignature(),
                c.multiplicity(), c.violations(), rule.versionRef(), rule.getFingerprint());
    }

    private String mapStatus(String runStatus) {
        return switch (runStatus) {
            case RunResult.OK -> ST_PENDING;
            case RunResult.NO_MATCH -> ST_NO_MATCH;
            case RunResult.LIMIT_REACHED -> ST_LIMIT;
            case RunResult.INVALID_RULE -> ST_INVALID_RULE;
            case RunResult.INVALID_INPUT -> ST_INVALID_INPUT;
            default -> ST_PENDING;
        };
    }

    private List<String> collectUnresolved(RunResult run) {
        List<String> out = new ArrayList<>();
        switch (run.status()) {
            case RunResult.NO_MATCH -> out.add("NO_MATCH: " + run.message());
            case RunResult.LIMIT_REACHED -> out.add("LIMIT_REACHED: " + run.message());
            case RunResult.INVALID_RULE -> out.add("INVALID_RULE: " + run.message());
            case RunResult.INVALID_INPUT -> out.add("INVALID_INPUT: " + run.message());
            default -> { }
        }
        for (Candidate c : run.candidates()) {
            for (var v : c.violations()) {
                out.add("CANDIDATE[" + c.index() + "] " + v.code() + ": " + v.message());
            }
        }
        return out;
    }

    static String canonicalJson(JsonNode node) {
        try {
            Object value = Json.mapper().treeToValue(node, Object.class);
            return Json.mapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new BadRequestException("invalid input JSON: " + e.getMessage());
        }
    }

    MolGraph graphFromEvidence(String hash) {
        try {
            return Json.mapper().convertValue(Json.mapper().readTree(evidence.get(hash)), MolGraph.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("corrupt input evidence " + hash, e);
        }
    }

    private String fingerprintOrNull(MolGraph g) {
        try {
            return com.molmap.rule.Canonicalizer.of(g).fingerprint();
        } catch (Exception e) {
            return null;
        }
    }

    private int versionOf(String versionRef) {
        int at = versionRef.lastIndexOf("@v");
        return Integer.parseInt(versionRef.substring(at + 2));
    }
}
