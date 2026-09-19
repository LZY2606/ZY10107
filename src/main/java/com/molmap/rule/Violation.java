package com.molmap.rule;

/** Severity-tagged diagnostic attached to an individual candidate. */
public record Violation(String code, String severity, String message) {
    public static Violation error(String code, String message) {
        return new Violation(code, "ERROR", message);
    }
    public static Violation warn(String code, String message) {
        return new Violation(code, "WARN", message);
    }
}
