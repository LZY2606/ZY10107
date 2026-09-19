package com.molmap.store;

import com.molmap.rule.Fingerprints;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Content-addressed, write-once store for original evidence (imported input and
 * raw rule submissions). An object is stored at {@code evidence/<sha256>.json}
 * and never overwritten: receiving the same logical record again is a no-op,
 * and no API path mutates existing bytes. This is the immutability guarantee
 * behind "raw evidence cannot be rewritten in place".
 */
public final class EvidenceStore {

    private final Path dir;

    public EvidenceStore(Path dir) {
        this.dir = dir;
    }

    /** Stores raw JSON, returns its SHA-256 content fingerprint. */
    public String put(String rawJson) {
        String hash = Fingerprints.sha256(rawJson);
        Path target = dir.resolve(hash + ".json");
        try {
            Files.createDirectories(dir);
            if (!Files.exists(target)) {
                Path tmp = dir.resolve(hash + ".tmp");
                Files.writeString(tmp, rawJson, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                Files.move(tmp, target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed storing evidence " + hash, e);
        }
        return hash;
    }

    public String get(String hash) {
        try {
            return Files.readString(dir.resolve(hash + ".json"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("missing evidence " + hash, e);
        }
    }

    public boolean exists(String hash) {
        return Files.exists(dir.resolve(hash + ".json"));
    }
}
