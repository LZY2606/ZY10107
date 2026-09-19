package com.molmap.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.molmap.rule.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Append-only repository for cases. Each log line is the full post-state of a
 * case; replay keeps the newest record (highest optimistic-lock version).
 */
public final class CaseRepository {

    public static final class Conflict extends RuntimeException {
        public final long expected;
        public final long actual;
        public Conflict(long expected, long actual) {
            super("case version conflict: expected " + expected + " but current is " + actual);
            this.expected = expected;
            this.actual = actual;
        }
    }

    private final JsonlStore log;
    private final Map<String, CaseState> byId = new LinkedHashMap<>();

    public CaseRepository(JsonlStore log) {
        this.log = log;
        replay();
    }

    public synchronized void replay() {
        byId.clear();
        for (JsonNode node : log.readAll()) {
            CaseState s = Json.mapper().convertValue(node, CaseState.class);
            CaseState existing = byId.get(s.id());
            if (existing == null || s.version() >= existing.version()) {
                byId.put(s.id(), s);
            }
        }
    }

    public synchronized CaseState create(CaseState state) {
        if (byId.containsKey(state.id())) throw new IllegalStateException("case already exists: " + state.id());
        log.append(state);
        byId.put(state.id(), state);
        return state;
    }

    /**
     * Optimistic update. {@code expectedVersion} must equal the stored version;
     * otherwise a second browser has committed first and the caller gets a
     * conflict with the current state, allowing a re-merge.
     */
    public synchronized CaseState update(CaseState state, long expectedVersion) {
        CaseState current = byId.get(state.id());
        if (current == null) throw new IllegalStateException("unknown case: " + state.id());
        if (current.version() != expectedVersion) {
            throw new Conflict(expectedVersion, current.version());
        }
        CaseState next = new CaseState(state.id(), state.ruleId(), state.ruleVersionRef(),
                state.ruleFingerprint(), state.createdAt(), state.updatedAt(), current.version() + 1,
                state.status(), state.inputEvidenceHash(), state.inputFingerprint(),
                state.candidates(), state.lockedMapping(), state.lockedCandidateIndex(),
                state.certificate(), state.unresolvedReasons());
        log.append(next);
        byId.put(next.id(), next);
        return next;
    }

    public synchronized Optional<CaseState> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public synchronized List<CaseState> findAll() {
        return new ArrayList<>(byId.values());
    }
}
