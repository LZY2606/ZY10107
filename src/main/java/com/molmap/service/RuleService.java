package com.molmap.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.molmap.model.MolGraph;
import com.molmap.rule.Canonicalizer;
import com.molmap.rule.Fingerprints;
import com.molmap.rule.GraphValidator;
import com.molmap.rule.Json;
import com.molmap.rule.Rule;
import com.molmap.rule.RuleIntegrity;
import com.molmap.rule.Validation;
import com.molmap.store.EvidenceStore;
import com.molmap.store.RuleRepository;
import com.molmap.store.RuleState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Creates/versioned rules, validates them and binds them to raw evidence. */
public final class RuleService {

    private final RuleRepository repo;
    private final EvidenceStore evidence;
    private final GraphValidator graphValidator = new GraphValidator();
    private final RuleIntegrity integrity = new RuleIntegrity();

    public RuleService(RuleRepository repo, EvidenceStore evidence) {
        this.repo = repo;
        this.evidence = evidence;
    }

    public record Submission(String id, String name, JsonNode lhs, JsonNode rhs) {}

    public RuleState createOrVersion(Submission sub, long now) {
        String id = (sub.id() == null || sub.id().isBlank())
                ? "rule-" + UUID.randomUUID().toString().substring(0, 8) : sub.id();
        int version = repo.nextVersion(id);

        String lhsJson = canonicalJson(sub.lhs());
        String rhsJson = canonicalJson(sub.rhs());
        String lhsHash = evidence.put(lhsJson);
        String rhsHash = evidence.put(rhsJson);

        MolGraph lhs = parseGraph(sub.lhs());
        MolGraph rhs = parseGraph(sub.rhs());

        List<String> reasons = new ArrayList<>();
        Validation vl = graphValidator.validate(lhs, "lhs");
        Validation vr = graphValidator.validate(rhs, "rhs");
        if (!vl.valid()) reasons.addAll(vl.reasons());
        if (!vr.valid()) reasons.addAll(vr.reasons());
        Validation integ = integrity.check(lhs, rhs);
        if (!integ.valid()) reasons.addAll(integ.reasons());

        String fingerprint = ruleFingerprint(lhs, rhs);
        RuleState state = new RuleState(id, sub.name(), version, now, lhsHash, rhsHash,
                reasons.isEmpty(), List.copyOf(reasons), fingerprint);
        return repo.append(state);
    }

    /** Content fingerprint independent of id/name/version: same edit -> same hash. */
    public static String ruleFingerprint(MolGraph lhs, MolGraph rhs) {
        String body = Canonicalizer.of(lhs).fingerprint() + ">>" + Canonicalizer.of(rhs).fingerprint();
        return Fingerprints.sha256(body);
    }

    private static MolGraph parseGraph(JsonNode node) {
        return Json.mapper().convertValue(node, MolGraph.class);
    }

    private static String canonicalJson(JsonNode node) {
        try {
            Object m = Json.mapper().treeToValue(node, Object.class);
            return Json.mapper().writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid graph JSON: " + e.getMessage(), e);
        }
    }

    public Rule toRule(RuleState state) {
        try {
            MolGraph lhs = Json.mapper().convertValue(
                    Json.mapper().readTree(evidence.get(state.lhsEvidenceHash())), MolGraph.class);
            MolGraph rhs = Json.mapper().convertValue(
                    Json.mapper().readTree(evidence.get(state.rhsEvidenceHash())), MolGraph.class);
            return new Rule(state.id(), state.name(), state.version(), state.createdAt(),
                    lhs, rhs, state.valid(), state.validationReasons(), state.fingerprint());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("corrupt rule evidence for " + state.id() + "@v" + state.version(), e);
        }
    }

    public java.util.List<RuleState> findAllLatest() { return repo.findAllLatest(); }
    public java.util.List<RuleState> findAllVersions(String id) { return repo.findAllVersions(id); }
    public java.util.Optional<RuleState> findVersion(String id, int version) { return repo.findVersion(id, version); }
    public java.util.Optional<RuleState> findLatest(String id) { return repo.findLatest(id); }
}
