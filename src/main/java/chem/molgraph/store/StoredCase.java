package chem.molgraph.store;

import chem.molgraph.domain.Molecule;

import java.util.List;

public record StoredCase(
        String caseId,
        String title,
        Molecule evidence,
        String evidenceFingerprint,
        long receivedAt,
        String status,
        String ruleId,
        String ruleVersion,
        String ruleFingerprint,
        String lastTransformStatus,
        List<MappingCertificate> certificates,
        String parentCaseId,
        long version) {

    public static final String IMPORTED = "IMPORTED";
    public static final String ANALYZED = "ANALYZED";
    public static final String LOCKED = "LOCKED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String STALE_RULE = "STALE_RULE";
}
