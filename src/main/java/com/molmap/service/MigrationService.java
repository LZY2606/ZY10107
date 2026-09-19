package com.molmap.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.molmap.model.MolGraph;
import com.molmap.rule.Candidate;
import com.molmap.rule.Json;
import com.molmap.rule.MatchEngine;
import com.molmap.rule.Rule;
import com.molmap.rule.RunResult;
import com.molmap.store.CaseState;
import com.molmap.store.EvidenceStore;
import com.molmap.store.RuleState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Migrates cases from an old rule version to a newer one WITHOUT rewriting the
 * confirmed case. It produces a report listing conclusion changes (status,
 * candidate count, fingerprints) and mapping drift (same input atom maps to a
 * different pattern position, or an embedding disappeared/appeared).
 */
public final class MigrationService {

    private final CaseService caseService;
    private final RuleService ruleService;
    private final EvidenceStore evidence;
    private final MatchEngine engine = new MatchEngine();

    public MigrationService(CaseService caseService, RuleService ruleService, EvidenceStore evidence) {
        this.caseService = caseService;
        this.ruleService = ruleService;
        this.evidence = evidence;
    }

    public record CandidateSummary(String productFingerprint, String orbitSignature,
                                   int multiplicity, boolean acceptable) {}

    public record CaseMigration(String caseId, String oldRuleVersionRef, String newRuleVersionRef,
                                String oldStatus, String newStatus, boolean conclusionChanged,
                                List<String> changes,
                                List<CandidateSummary> oldCandidates,
                                List<CandidateSummary> newCandidates) {}

    public record MigrationReport(String oldRuleVersionRef, String newRuleVersionRef,
                                  int caseCount, List<CaseMigration> cases) {}

    public MigrationReport migrateRule(String ruleId, int toVersion, List<CaseState> cases) {
        RuleState newState = caseService.resolveRule(ruleId, toVersion);
        Rule newRule = ruleService.toRule(newState);

        List<CaseMigration> results = new ArrayList<>();
        java.util.SortedSet<String> oldRefs = new java.util.TreeSet<>();
        for (CaseState cs : cases) {
            if (!cs.ruleId().equals(ruleId)) continue;
            int oldVersion = parseVersion(cs.ruleVersionRef());
            RuleState oldState = caseService.resolveRule(ruleId, oldVersion);
            Rule oldRule = ruleService.toRule(oldState);
            oldRefs.add(cs.ruleVersionRef());

            MolGraph input = caseService.graphFromEvidence(cs.inputEvidenceHash());

            RunResult oldRun = engine.run(oldRule, input, CaseService.DEFAULT_LIMIT);
            RunResult newRun = engine.run(newRule, input, CaseService.DEFAULT_LIMIT);

            List<String> changes = diff(oldRun, newRun, cs);
            boolean changed = !changes.isEmpty() || !oldRun.status().equals(newRun.status());

            results.add(new CaseMigration(cs.id(), cs.ruleVersionRef(), newRule.versionRef(),
                    oldRun.status(), newRun.status(), changed, changes,
                    summarize(oldRun), summarize(newRun)));
        }
        String oldRef = oldRefs.isEmpty() ? "(none)" : String.join(",", oldRefs);
        return new MigrationReport(oldRef,
                newRule.versionRef(), results.size(), results);
    }

    private List<CandidateSummary> summarize(RunResult r) {
        List<CandidateSummary> out = new ArrayList<>();
        for (Candidate c : r.candidates()) {
            out.add(new CandidateSummary(c.productFingerprint(), c.orbitSignature(),
                    c.multiplicity(), c.acceptable()));
        }
        return out;
    }

    private List<String> diff(RunResult oldRun, RunResult newRun, CaseState cs) {
        List<String> changes = new ArrayList<>();
        if (!oldRun.status().equals(newRun.status())) {
            changes.add("STATUS " + oldRun.status() + " -> " + newRun.status());
        }
        if (oldRun.candidateCount() != newRun.candidateCount()) {
            changes.add("CANDIDATE_COUNT " + oldRun.candidateCount() + " -> " + newRun.candidateCount());
        }
        if (oldRun.rawMappingCount() != newRun.rawMappingCount()) {
            changes.add("RAW_MAPPING_COUNT " + oldRun.rawMappingCount() + " -> " + newRun.rawMappingCount());
        }

        Set<String> oldFps = new TreeSet<>();
        for (Candidate c : oldRun.candidates()) oldFps.add(c.productFingerprint());
        Set<String> newFps = new TreeSet<>();
        for (Candidate c : newRun.candidates()) newFps.add(c.productFingerprint());
        for (String gone : new TreeSet<>(oldFps)) {
            if (!newFps.contains(gone)) changes.add("PRODUCT_LOST " + abbreviate(gone));
        }
        for (String appeared : newFps) {
            if (!oldFps.contains(appeared)) changes.add("PRODUCT_ADDED " + abbreviate(appeared));
        }

        // Mapping drift on the confirmed mapping if the case was locked.
        if (cs.lockedMapping() != null) {
            changes.addAll(mappingDrift(cs, newRun));
        }
        return changes;
    }

    private List<String> mappingDrift(CaseState cs, RunResult newRun) {
        List<String> out = new ArrayList<>();
        Map<String, String> locked = cs.lockedMapping();
        Candidate equivalent = null;
        for (Candidate c : newRun.candidates()) {
            if (c.productFingerprint().equals(cs.certificate() == null
                    ? null : cs.certificate().productFingerprint())) {
                equivalent = c;
            }
        }
        if (equivalent == null) {
            out.add("MAPPING_DRIFT: locked product fingerprint no longer produced");
            return out;
        }
        Map<String, String> fresh = equivalent.mapping();
        for (Map.Entry<String, String> e : new LinkedHashMap<>(locked).entrySet()) {
            String nowMapped = fresh.get(e.getKey());
            if (nowMapped == null) {
                out.add("MAPPING_DRIFT: pattern atom " + e.getKey() + " no longer mapped");
            } else if (!nowMapped.equals(e.getValue())) {
                out.add("MAPPING_DRIFT: pattern atom " + e.getKey() + " input "
                        + e.getValue() + " -> " + nowMapped);
            }
        }
        return out;
    }

    private static String abbreviate(String fp) {
        return fp.length() <= 18 ? fp : fp.substring(0, 18) + "...";
    }

    private static int parseVersion(String ref) {
        return Integer.parseInt(ref.substring(ref.lastIndexOf('v') + 1));
    }
}
