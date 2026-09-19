package chem.molgraph.store;

public class ConflictException extends RuntimeException {
    public final long expectedVersion;
    public final long actualVersion;

    public ConflictException(long expectedVersion, long actualVersion, String message) {
        super(message);
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }
}
