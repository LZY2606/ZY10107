package com.molmap;

import com.molmap.model.Atom;
import com.molmap.model.Bond;
import com.molmap.model.MolGraph;

import java.util.ArrayList;
import java.util.List;

/** Tiny builder used by tests. */
public final class Graphs {
    private Graphs() {}

    public static Atom atom(String id, String el) { return new Atom(id, el, null, 0, null); }
    public static Atom atom(String id, String el, Integer iso, Integer charge, String stereo) {
        return new Atom(id, el, iso, charge, stereo);
    }
    public static Bond bond(String a, String b, String order) { return new Bond(a, b, order, null); }
    public static Bond bond(String a, String b, String order, String stereo) {
        return new Bond(a, b, order, stereo);
    }

    public static MolGraph graph(List<Atom> atoms, List<Bond> bonds) {
        return new MolGraph(new ArrayList<>(atoms), new ArrayList<>(bonds));
    }

    /** A chain a-b-c of the requested element. */
    public static MolGraph chain(String prefix, String el) {
        return graph(
                List.of(atom(prefix + 1, el), atom(prefix + 2, el), atom(prefix + 3, el)),
                List.of(bond(prefix + 1, prefix + 2, "1"), bond(prefix + 2, prefix + 3, "1")));
    }
}
