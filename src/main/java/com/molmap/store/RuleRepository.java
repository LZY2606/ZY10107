package com.molmap.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.molmap.rule.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Append-only repository for rule versions; rebuilds state by replaying the log. */
public final class RuleRepository {

    private final JsonlStore log;
    private final Map<String, TreeMap<Integer, RuleState>> byId = new LinkedHashMap<>();

    public RuleRepository(JsonlStore log) {
        this.log = log;
        replay();
    }

    public synchronized void replay() {
        byId.clear();
        for (JsonNode node : log.readAll()) {
            RuleState s = Json.mapper().convertValue(node, RuleState.class);
            byId.computeIfAbsent(s.id(), k -> new TreeMap<>()).put(s.version(), s);
        }
    }

    public synchronized RuleState append(RuleState state) {
        log.append(state);
        byId.computeIfAbsent(state.id(), k -> new TreeMap<>()).put(state.version(), state);
        return state;
    }

    public synchronized List<RuleState> findAllLatest() {
        List<RuleState> out = new ArrayList<>();
        for (TreeMap<Integer, RuleState> versions : byId.values()) {
            out.add(versions.lastEntry().getValue());
        }
        return out;
    }

    public synchronized List<RuleState> findAllVersions(String id) {
        TreeMap<Integer, RuleState> versions = byId.get(id);
        return versions == null ? List.of() : new ArrayList<>(versions.values());
    }

    public synchronized Optional<RuleState> findLatest(String id) {
        TreeMap<Integer, RuleState> versions = byId.get(id);
        return versions == null ? Optional.empty() : Optional.of(versions.lastEntry().getValue());
    }

    public synchronized Optional<RuleState> findVersion(String id, int version) {
        TreeMap<Integer, RuleState> versions = byId.get(id);
        return versions == null ? Optional.empty() : Optional.ofNullable(versions.get(version));
    }

    public synchronized int nextVersion(String id) {
        TreeMap<Integer, RuleState> versions = byId.get(id);
        return versions == null ? 1 : versions.lastKey() + 1;
    }
}
