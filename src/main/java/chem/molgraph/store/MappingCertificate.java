package chem.molgraph.store;

import chem.molgraph.domain.TransformResult;

import java.util.List;
import java.util.Map;

public record MappingCertificate(
        String certificateId,
        String caseId,
        String candidateFingerprint,
        String ruleFingerprint,
        String ruleName,
        String ruleVersion,
        String evidenceFingerprint,
        Map<String, String> mapping,
        TransformResult transformResult,
        List<String> productCanonical,
        long baseVersion,
        String chainHash,
        long lockedAt) {
}
