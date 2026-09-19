package chem.molgraph.store;

public record MappingDrift(
        String oldFingerprint,
        String newFingerprint,
        int oldMappingCount,
        int newMappingCount,
        String kind) {

    public static final String SAME = "SAME";
    public static final String COUNT_CHANGED = "COUNT_CHANGED";
    public static final String DISAPPEARED = "DISAPPEARED";
    public static final String NEW_PRODUCT = "NEW_PRODUCT";
}
