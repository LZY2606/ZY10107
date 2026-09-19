package com.molmap.rule;

import java.util.List;

/** Result of structurally validating a graph or a rule. */
public record Validation(boolean valid, List<String> reasons) {
    public static Validation ok() { return new Validation(true, List.of()); }
    public static Validation fail(List<String> reasons) { return new Validation(false, List.copyOf(reasons)); }
    public Validation plus(List<String> more) {
        if (more.isEmpty()) return this;
        return new Validation(valid, java.util.stream.Stream.concat(reasons.stream(), more.stream()).toList());
    }
}
