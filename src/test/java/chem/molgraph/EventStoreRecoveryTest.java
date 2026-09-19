package chem.molgraph;

import chem.molgraph.store.Event;
import chem.molgraph.store.EventStore;
import chem.molgraph.store.EventStoreException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventStoreRecoveryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void appendsSurviveRestartWithHashChain(@TempDir Path temp) throws Exception {
        Path dir = temp.resolve("ok");
        EventStore store = new EventStore(mapper, dir.toString());
        store.init();
        store.append("Test", List.of("a", "b"));
        store.append("Test", List.of("c"));

        EventStore reopened = new EventStore(mapper, dir.toString());
        reopened.init();
        assertEquals(2, reopened.events().size());
        assertFalse(reopened.isReadOnly());
        assertEquals("c", ((List<?>) mapper.readValue(reopened.events().get(1).payloadJson(), List.class)).get(0));
    }

    @Test
    void inPlaceTamperingQuarantinesLogAndKeepsVerifiedPrefix(@TempDir Path temp) throws Exception {
        Path dir = temp.resolve("tampered");
        Files.createDirectories(dir);
        EventStore store = new EventStore(mapper, dir.toString());
        store.init();
        store.append("Test", "first");
        store.append("Test", "second");

        Path log = dir.resolve("events.log");
        List<String> lines = Files.readAllLines(log);
        String tampered = lines.get(0).replaceFirst("first", "hacked");
        Files.writeString(log, tampered + System.lineSeparator() + lines.get(1) + System.lineSeparator());

        EventStore reopened = new EventStore(mapper, dir.toString());
        reopened.init();
        assertTrue(reopened.isReadOnly());
        assertNotNull(reopened.recoveryError());
        assertEquals(0, reopened.events().size());
        assertThrows(EventStoreException.class, () -> reopened.append("Test", "third"));
        assertTrue(Files.list(dir.resolve("quarantine")).findAny().isPresent());
    }

    @Test
    void garbageAtTailTruncatesAtLastGoodFrame(@TempDir Path temp) throws Exception {
        Path dir = temp.resolve("tail");
        Files.createDirectories(dir);
        EventStore store = new EventStore(mapper, dir.toString());
        store.init();
        store.append("Test", "first");
        store.append("Test", "second");
        Path log = dir.resolve("events.log");
        Files.writeString(log, Files.readString(log) + "NOT-A-JSON-FRAME\n");

        EventStore reopened = new EventStore(mapper, dir.toString());
        reopened.init();
        assertTrue(reopened.isReadOnly());
        assertEquals(2, reopened.events().size());
        Event last = reopened.events().get(1);
        assertEquals("second", mapper.readValue(last.payloadJson(), String.class));
    }
}
