package chem.molgraph.store;

import chem.molgraph.domain.TransformResult;

import java.util.List;

public record BatchRecord(
        String batchId,
        String ruleId,
        List<String> caseIds,
        List<TransformResult> results,
        String defaultCaseId,
        String defaultFingerprint,
        long createdAt,
        long version) {
}
