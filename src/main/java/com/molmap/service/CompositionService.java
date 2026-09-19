package com.molmap.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.molmap.model.MolGraph;
import com.molmap.rule.Candidate;
import com.molmap.rule.Json;
import com.molmap.rule.MatchEngine;
import com.molmap.rule.Rule;
import com.molmap.rule.RunResult;
import com.molmap.store.CompositionRepository;
import com.molmap.store.CompositionState;
import com.molmap.store.EvidenceStore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Composes two rules in sequence. The second rule runs on the first rule's
 * product, whose newly created atoms carry temporary {@code t#} ids. If the
 * second rule's embedding maps onto those temporary ids, that is an implicit,
 * unstable dependency: composition is rejected and NOTHING is persisted, so a
 * failed composition never leaves a half-result.
 */
public final class CompositionService {

    private final CaseService caseService;
    private final RuleService ruleService;
    private final EvidenceStore evidence;
    private final CompositionRepository repo;
    private final MatchEngine engine = new MatchEngine();

    public CompositionService(CaseService caseService, RuleService ruleService,
                              EvidenceStore evidence, CompositionRepository repo) {
        this.caseService = caseService;
        this.ruleService = ruleService;
        this.evidence = evidence;
        this.repo = repo;
    }

    public record ComposeRequest(String rule1Id, Integer rule1Version,
                                 String rule2Id, Integer rule2Version,
                                 JsonNode input, Integer candidateIndex, Integer matchLimit) {}

    public record CompositionResponse(CompositionState state, RunResult first, RunResult second) {}

    public CompositionResponse compose(ComposeRequest req, long now) {
        Rule rule1 = ruleService.toRule(caseService.resolveRule(req.rule1Id(), req.rule1Version()));
        Rule rule2 = ruleService.toRule(caseService.resolveRule(req.rule2Id(), req.rule2Version()));

        String inputHash = evidence.put(canonical(req.input()));
        MolGraph input = Json.mapper().convertValue(req.input(), MolGraph.class);
        int limit = req.matchLimit() == null ? CaseService.DEFAULT_LIMIT : req.matchLimit();

        RunResult first = engine.run(rule1, input, limit);
        if (!RunResult.OK.equals(first.status())) {
            CompositionState st = persistFailure(rule1, rule2, inputHash, null,
                    "FIRST_" + first.status(), first.message(), List.of());
            return new CompositionResponse(st, first, null);
        }
        int pick = req.candidateIndex() == null ? 0 : req.candidateIndex();
        Candidate chosen = first.candidates().stream().filter(c -> c.index() == pick).findFirst()
                .orElseThrow(() -> new BadRequestException("first rule candidate index missing: " + pick));

        RunResult second = engine.run(rule2, chosen.product(), limit);
        List<String> tempReferenced = tempDependencies(chosen, second);
        if (!tempReferenced.isEmpty()) {
            CompositionState st = persistFailure(rule1, rule2, inputHash, null,
                    "RULE2_DEPENDS_ON_TEMP_IDS",
                    "second rule maps onto temporary ids produced by rule 1: " + tempReferenced,
                    tempReferenced);
            return new CompositionResponse(st, first, second);
        }
        if (!RunResult.OK.equals(second.status())) {
            CompositionState st = persistFailure(rule1, rule2, inputHash, null,
                    "SECOND_" + second.status(), second.message(), List.of());
            return new CompositionResponse(st, first, second);
        }
        // Pick canonical first candidate of second rule for the final fingerprint.
        String finalFp = second.candidates().get(0).productFingerprint();
        CompositionState ok = new CompositionState("comp-" + UUID.randomUUID().toString().substring(0, 8),
                now, rule1.versionRef(), rule2.versionRef(), true, "OK",
                second.candidateCount() + " final candidate(s)", inputHash, finalFp, List.of());
        repo.append(ok);
        return new CompositionResponse(ok, first, second);
    }

    private List<String> tempDependencies(Candidate firstProduct, RunResult second) {
        List<String> temp = new ArrayList<>();
        if (!RunResult.OK.equals(second.status())) return temp;
        // Inspect EVERY raw embedding (including symmetry equivalents merged out
        // of the canonical candidate list): a single mapping onto a rule-1
        // temporary id makes the composition order-dependent and thus invalid.
        int emb = 0;
        for (java.util.Map<String, String> mapping : second.rawMappings()) {
            for (String inputId : mapping.values()) {
                if (firstProduct.addedAtomIds().contains(inputId)) {
                    String token = "embedding[" + emb + "] maps onto temporary atom:" + inputId;
                    if (!temp.contains(token)) temp.add(token);
                }
            }
            emb++;
        }
        return temp;
    }

    private CompositionState persistFailure(Rule r1, Rule r2, String inputHash, String fp,
                                            String status, String reason, List<String> tempIds) {
        CompositionState st = new CompositionState("comp-" + UUID.randomUUID().toString().substring(0, 8),
                System.currentTimeMillis(), r1.versionRef(), r2.versionRef(), false,
                status, reason, inputHash, fp, List.copyOf(tempIds));
        repo.append(st);
        return st;
    }

    private static String canonical(JsonNode node) {
        try {
            return Json.mapper().writeValueAsString(Json.mapper().treeToValue(node, Object.class));
        } catch (Exception e) {
            throw new BadRequestException("invalid input JSON: " + e.getMessage());
        }
    }
}
