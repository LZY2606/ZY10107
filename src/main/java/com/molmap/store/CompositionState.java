package com.molmap.store;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompositionState(String id, long createdAt,
                               String firstRuleVersionRef, String secondRuleVersionRef,
                               boolean succeeded, String status, String reason,
                               String inputEvidenceHash, String productFingerprint,
                               java.util.List<String> tempIdsReferenced) {
}
