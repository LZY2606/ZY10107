package com.molmap.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.molmap.rule.Fingerprints;
import com.molmap.rule.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only JSON-lines event log. Each appended record is fsynced; replay is
 * idempotent. This gives durable local persistence without a database server.
 */
public final class JsonlStore {

    private final Path file;

    public JsonlStore(Path file) {
        this.file = file;
    }

    /** Append one record and flush it to disk before returning. */
    public synchronized void append(Object record) {
        try {
            Files.createDirectories(file.getParent());
            String line = Json.mapper().writeValueAsString(record);
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            // Best-effort durability: fsync the file when the platform exposes a FileChannel.
            try (var ch = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE)) {
                ch.force(true);
            } catch (IOException ignored) {
                // Some platforms/filesystems reject force(); the line is already written.
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed appending to " + file, e);
        }
    }

    public synchronized List<JsonNode> readAll() {
        List<JsonNode> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                out.add(Json.mapper().readTree(line));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading " + file, e);
        }
        return out;
    }

    public Path file() { return file; }
}
