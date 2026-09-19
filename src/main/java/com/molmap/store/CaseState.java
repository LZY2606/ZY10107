package com.molmap.store;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * One reviewed case. A case pins a specific rule version and stores both the
 * raw input fingerprint and derived candidates stamped with that rule version.
 * {@code version} here is the case record's optimistic-lock version.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaseState(
        String id,
        String ruleId,
        String ruleVersionRef,
        String ruleFingerprint,
        long createdAt,
        long updatedAt,
        long version,
        String status,
        String inputEvidenceHash,
        String inputFingerprint,
        List<PersistedCandidate> candidates,
        Map<String, String> lockedMapping,
        Integer lockedCandidateIndex,
        Certificate certificate,
        List<String> unresolvedReasons
) {
}
