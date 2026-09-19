package com.molmap.rule;

import java.util.List;
import java.util.Map;

/**
 * Outcome of enumerating one rule against one input. The status is an explicit
 * state machine value rather than an overloaded empty list:
 *
 * OK              - zero or more candidates found
 * NO_MATCH        - pattern structurally absent from the input
 * LIMIT_REACHED   - enumeration hit the cap; result would be incomplete
 * INVALID_RULE    - rule failed its own validation; nothing enumerated
 * INVALID_INPUT   - input graph failed structural validation
 */
public record RunResult(String ruleVersionRef, String status, String message,
                        int matchLimit, int rawMappingCount, int candidateCount,
                        boolean capped, List<Candidate> candidates,
                        List<Map<String, String>> rawMappings) {

    public static final String OK = "OK";
    public static final String NO_MATCH = "NO_MATCH";
    public static final String LIMIT_REACHED = "LIMIT_REACHED";
    public static final String INVALID_RULE = "INVALID_RULE";
    public static final String INVALID_INPUT = "INVALID_INPUT";

    public static RunResult terminal(String ref, String status, String msg, int limit) {
        return new RunResult(ref, status, msg, limit, 0, 0,
                LIMIT_REACHED.equals(status), List.of(), List.of());
    }

    public boolean isTerminalFailure() {
        return !OK.equals(status) && !NO_MATCH.equals(status) && !LIMIT_REACHED.equals(status);
    }
}
