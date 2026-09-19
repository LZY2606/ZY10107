package com.molmap.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.molmap.rule.Json;

import java.util.ArrayList;
import java.util.List;

/** Append-only log of rule-composition attempts (both successes and failures). */
public final class CompositionRepository {

    private final JsonlStore log;
    private final List<CompositionState> all = new ArrayList<>();

    public CompositionRepository(JsonlStore log) {
        this.log = log;
        replay();
    }

    public synchronized void replay() {
        all.clear();
        for (JsonNode node : log.readAll()) {
            all.add(Json.mapper().convertValue(node, CompositionState.class));
        }
    }

    public synchronized void append(CompositionState state) {
        log.append(state);
        all.add(state);
    }

    public synchronized List<CompositionState> findAll() {
        return new ArrayList<>(all);
    }
}
