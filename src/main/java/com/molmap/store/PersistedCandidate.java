package com.molmap.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import com.molmap.rule.Violation;

import java.util.List;
import java.util.Map;

/** Derived candidate snapshot, stamped with the rule version/source fingerprint. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PersistedCandidate(
        int index,
        Map<String, String> mapping,
        JsonNode product,
        String inputFingerprint,
        String productFingerprint,
        String orbitSignature,
        int multiplicity,
        List<Violation> violations,
        String derivedFromRuleVersion,
        String sourceFingerprint
) {
}
