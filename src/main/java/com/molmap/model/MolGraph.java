package com.molmap.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A labelled molecular graph. Insertion order is preserved for deterministic
 * presentation, but all canonical comparisons go through {@code Canonicalizer}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class MolGraph {
    private final List<Atom> atoms;
    private final List<Bond> bonds;

    @com.fasterxml.jackson.annotation.JsonCreator
    public MolGraph(@com.fasterxml.jackson.annotation.JsonProperty("atoms") List<Atom> atoms,
                    @com.fasterxml.jackson.annotation.JsonProperty("bonds") List<Bond> bonds) {
        this.atoms = new ArrayList<>(atoms == null ? List.of() : atoms);
        this.bonds = new ArrayList<>(bonds == null ? List.of() : bonds);
    }

    @com.fasterxml.jackson.annotation.JsonProperty("atoms")
    public List<Atom> atoms() { return Collections.unmodifiableList(atoms); }
    @com.fasterxml.jackson.annotation.JsonProperty("bonds")
    public List<Bond> bonds() { return Collections.unmodifiableList(bonds); }

    public Atom atom(String id) {
        for (Atom a : atoms) if (a.getId().equals(id)) return a;
        return null;
    }

    public Set<String> atomIds() {
        Set<String> ids = new TreeSet<>();
        for (Atom a : atoms) ids.add(a.getId());
        return ids;
    }

    public List<Bond> bondsBetween(String a, String b) {
        List<Bond> out = new ArrayList<>();
        for (Bond bd : bonds) {
            if ((bd.getFrom().equals(a) && bd.getTo().equals(b))
                    || (bd.getFrom().equals(b) && bd.getTo().equals(a))) {
                out.add(bd);
            }
        }
        return out;
    }

    public int totalCharge() {
        int q = 0;
        for (Atom a : atoms) q += a.chargeOrZero();
        return q;
    }

    public Map<String, Integer> elementCounts() {
        Map<String, Integer> counts = new TreeMap<>();
        for (Atom a : atoms) counts.merge(a.getElement(), 1, Integer::sum);
        return counts;
    }

    /** Deterministic adjacency map keyed by atom id (parallel bonds preserved). */
    public Map<String, List<Bond>> adjacency() {
        Map<String, List<Bond>> adj = new LinkedHashMap<>();
        for (Atom a : atoms) adj.put(a.getId(), new ArrayList<>());
        for (Bond b : bonds) {
            adj.get(b.getFrom()).add(b);
            adj.get(b.getTo()).add(b);
        }
        return adj;
    }

    public String describe() {
        return atoms.stream().map(Atom::toString).collect(Collectors.joining(" "))
                + " || " + bonds.stream().map(Object::toString).collect(Collectors.joining(" "));
    }
}
