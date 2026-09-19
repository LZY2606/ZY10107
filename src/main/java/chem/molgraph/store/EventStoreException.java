package chem.molgraph.store;

public class EventStoreException extends RuntimeException {
    public EventStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public EventStoreException(String message) {
        super(message);
    }
}
