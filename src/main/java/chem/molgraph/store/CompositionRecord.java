package chem.molgraph.store;

import chem.molgraph.domain.TransformResult;

public record CompositionRecord(
        String compositionId,
        String firstRuleId,
        String secondRuleId,
        String status,
        TransformResult intermediate,
        TransformResult finalResult,
        String failedReason,
        long createdAt,
        long version) {
}
