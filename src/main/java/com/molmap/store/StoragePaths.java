package com.molmap.store;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves on-disk storage locations. Everything lives under {@code data/} by
 * default so the app is fully local and portable. Recovery only needs these
 * files (see README).
 */
public final class StoragePaths {
    private final Path root;

    public StoragePaths(String root) {
        this.root = Paths.get(root);
    }

    public Path root() { return root; }
    public Path rulesLog() { return root.resolve("rules.log"); }
    public Path casesLog() { return root.resolve("cases.log"); }
    public Path compositionsLog() { return root.resolve("compositions.log"); }
    public Path evidenceDir() { return root.resolve("evidence"); }
    public Path lockFile() { return root.resolve(".replay.lock"); }
}
