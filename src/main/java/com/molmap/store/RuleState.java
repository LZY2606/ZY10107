package com.molmap.store;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** One persisted, versioned rule record (the log payload). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuleState(String id, String name, int version, long createdAt,
                        String lhsEvidenceHash, String rhsEvidenceHash,
                        boolean valid, List<String> validationReasons, String fingerprint) {
}
