package com.molmap.store;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * An immutable mapping certificate issued when a mapping is locked. It binds a
 * confirmed case to exact content fingerprints, so later rule versions can be
 * diffed against what a reviewer actually approved.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Certificate(
        String caseId,
        String ruleVersionRef,
        String ruleFingerprint,
        String inputEvidenceHash,
        String inputFingerprint,
        String productFingerprint,
        Map<String, String> lockedMapping,
        int candidateIndex,
        int multiplicity,
        long lockedAt,
        String certificateHash
) {
}
