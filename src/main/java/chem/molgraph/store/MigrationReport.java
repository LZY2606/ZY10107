package chem.molgraph.store;

import chem.molgraph.domain.TransformResult;

import java.util.List;

public record MigrationReport(
        String migrationId,
        String sourceCaseId,
        String newCaseId,
        String oldRuleVersion,
        String newRuleVersion,
        String conclusion,
        List<MappingDrift> drifts,
        TransformResult oldResult,
        TransformResult newResult,
        long createdAt) {

    public static final String CONCLUSION_COMPATIBLE = "COMPATIBLE";
    public static final String CONCLUSION_DRIFT = "MAPPING_DRIFT";
    public static final String CONCLUSION_BROKEN = "CONCLUSION_CHANGED";
}
