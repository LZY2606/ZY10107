package com.molmap.rule;

import com.molmap.model.Atom;
import com.molmap.model.Bond;
import com.molmap.model.MolGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic graph canonicalization.
 *
 * <p>Atom invariants are refined with Weisfeiler-Lehman colour refinement that
 * incorporates element, isotope, charge, atom stereo and incident bond
 * order/stereo. Ties are broken only by the original atom id, so the same
 * graph always reproduces the same fingerprint byte-for-byte. Atoms sharing a
 * final colour are a symmetry orbit: mappings differing only by permutations
 * inside one orbit are chemically equivalent.
 */
public final class Canonicalizer {

    public record Canonical(Map<String, Integer> rankOf, Map<Integer, List<String>> orbits,
                            String fingerprint, List<String> orderedIds) { }

    private static String bondSig(Bond b) {
        String order = b.getOrder() == null ? "1" : b.getOrder();
        String stereo = b.getStereo() == null ? "" : b.getStereo();
        return order + "/" + stereo;
    }

    public static Canonical of(MolGraph g) {
        Map<String, List<Bond>> adj = g.adjacency();
        List<String> ids = new ArrayList<>();
        for (Atom a : g.atoms()) ids.add(a.getId());

        Map<String, Integer> code = assignCodes(initialKeys(g), ids);

        for (int iter = 0; iter <= ids.size() + 1; iter++) {
            Map<String, String> refined = new LinkedHashMap<>();
            for (String id : ids) refined.put(id, refineKey(id, code, adj));
            Map<String, Integer> nextCode = assignCodes(refined, ids);
            if (new HashSet<>(nextCode.values()).size() == new HashSet<>(code.values()).size()) {
                code = nextCode;
                break;
            }
            code = nextCode;
        }

        // Deterministic total order: final colour, refined key, original id.
        final Map<String, Integer> finalCode = code;
        Map<String, String> keyOf = new LinkedHashMap<>();
        for (String id : ids) keyOf.put(id, refineKey(id, finalCode, adj));
        Comparator<String> ordering = Comparator
                .comparingInt((String id) -> finalCode.get(id))
                .thenComparing((String id) -> keyOf.get(id))
                .thenComparing(id -> id);
        List<String> ordered = new ArrayList<>(ids);
        ordered.sort(ordering);

        // Ranks are dense by identical (code, refinedKey). Equivalent => orbit.
        Map<String, Integer> rankOf = new LinkedHashMap<>();
        Map<Integer, List<String>> orbits = new TreeMap<>();
        int rank = 0;
        String prev = null;
        for (String id : ordered) {
            String key = code.get(id) + "|" + keyOf.get(id);
            if (prev == null || !key.equals(prev)) {
                rank++;
                orbits.put(rank, new ArrayList<>());
            }
            rankOf.put(id, rank);
            orbits.get(rank).add(id);
            prev = key;
        }

        String fingerprint = renderFingerprint(g, rankOf, adj);
        return new Canonical(rankOf, orbits, fingerprint, ordered);
    }

    private static Map<String, String> initialKeys(MolGraph g) {
        Map<String, String> keys = new LinkedHashMap<>();
        for (Atom a : g.atoms()) keys.put(a.getId(), a.signature());
        return keys;
    }

    private static String refineKey(String id, Map<String, Integer> code,
                                    Map<String, List<Bond>> adj) {
        List<String> edges = new ArrayList<>();
        for (Bond b : adj.get(id)) {
            edges.add(bondSig(b) + "->" + code.get(b.other(id)));
        }
        edges.sort(Comparator.naturalOrder());
        return code.get(id) + "||" + String.join(";", edges);
    }

    private static <V> Map<String, Integer> assignCodes(Map<String, V> keys, List<String> ids) {
        Map<V, Integer> codeByKey = new HashMap<>();
        List<V> distinct = new ArrayList<>();
        Set<V> seen = new HashSet<>();
        for (String id : ids) {
            V k = keys.get(id);
            if (seen.add(k)) distinct.add(k);
        }
        distinct.sort(Comparator.comparing((V k) -> String.valueOf(k)));
        int c = 0;
        for (V k : distinct) codeByKey.put(k, ++c);
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String id : ids) out.put(id, codeByKey.get(keys.get(id)));
        return out;
    }

    private static String renderFingerprint(MolGraph g, Map<String, Integer> rankOf,
                                            Map<String, List<Bond>> adj) {
        List<String> atomLines = new ArrayList<>();
        for (Atom a : g.atoms()) {
            atomLines.add(rankOf.get(a.getId()) + ":" + a.signature());
        }
        atomLines.sort(Comparator.naturalOrder());

        Set<String> seen = new HashSet<>();
        List<String> bondLines = new ArrayList<>();
        for (Bond b : g.bonds()) {
            int r1 = rankOf.get(b.getFrom());
            int r2 = rankOf.get(b.getTo());
            int lo = Math.min(r1, r2);
            int hi = Math.max(r1, r2);
            String line = lo + ":" + bondSig(b) + ":" + hi;
            if (seen.add(line)) bondLines.add(line);
        }
        bondLines.sort(Comparator.naturalOrder());
        return "ATOMS[" + String.join(",", atomLines) + "]BONDS[" + String.join(",", bondLines) + "]";
    }
}
