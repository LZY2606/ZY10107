package com.molmap.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Batch evaluation: the same input (or many) can yield several product
 * candidates. Ordering of the request list never changes the default candidate
 * order inside a result; that is always canonical fingerprint order.
 */
public final class BatchService {

    private final CaseService caseService;

    public BatchService(CaseService caseService) {
        this.caseService = caseService;
    }

    public record BatchItem(String name, String ruleId, Integer ruleVersion,
                            JsonNode input, Integer matchLimit) {}
    public record BatchRequest(List<BatchItem> items) {}
    public record BatchResponse(int total, int ok, int noMatch, int limitReached,
                                int invalidRule, int invalidInput,
                                List<CaseService.Created> results) {}

    public BatchResponse run(BatchRequest req, long now) {
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new BadRequestException("batch requires at least one item");
        }
        List<CaseService.Created> results = new java.util.ArrayList<>();
        int ok = 0, noMatch = 0, limit = 0, invalidRule = 0, invalidInput = 0;
        for (BatchItem item : req.items()) {
            CreateCaseRequest ccr = new CreateCaseRequest(item.name(), item.ruleId(),
                    item.ruleVersion(), item.input(), item.matchLimit());
            CaseService.Created created = caseService.create(ccr, now);
            results.add(created);
            switch (created.state().status()) {
                case CaseService.ST_PENDING, CaseService.ST_CONFIRMED -> ok++;
                case CaseService.ST_NO_MATCH -> noMatch++;
                case CaseService.ST_LIMIT -> limit++;
                case CaseService.ST_INVALID_RULE -> invalidRule++;
                case CaseService.ST_INVALID_INPUT -> invalidInput++;
                default -> { }
            }
        }
        return new BatchResponse(results.size(), ok, noMatch, limit, invalidRule, invalidInput, results);
    }
}
