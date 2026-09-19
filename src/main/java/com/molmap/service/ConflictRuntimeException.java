package com.molmap.service;

/**
 * A business conflict (revalidation failure or similar). Distinct from the
 * repository optimistic-lock conflict which carries both versions for re-merge.
 */
public class ConflictRuntimeException extends RuntimeException {
    private final String code;
    public ConflictRuntimeException(String code, String message) {
        super(message);
        this.code = code;
    }
    public String code() { return code; }
}
