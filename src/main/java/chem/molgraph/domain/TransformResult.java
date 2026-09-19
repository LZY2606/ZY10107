package chem.molgraph.domain;

import java.util.List;

public record TransformResult(
        String status,
        String ruleName,
        String ruleVersion,
        String ruleFingerprint,
        List<CandidateView> candidates,
        List<Issue> issues,
        int enumeratedMappings,
        int searchLimit,
        boolean limitReached,
        String evidenceFingerprint) {

    public static final String OK = "OK";
    public static final String NO_MATCH = "NO_MATCH";
    public static final String LIMIT_REACHED = "LIMIT_REACHED";
    public static final String INVALID_RULE = "INVALID_RULE";
    public static final String INVALID_INPUT = "INVALID_INPUT";
}
