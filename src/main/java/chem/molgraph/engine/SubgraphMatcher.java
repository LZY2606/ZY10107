package chem.molgraph.engine;

import chem.molgraph.domain.Atom;
import chem.molgraph.domain.Bond;
import chem.molgraph.domain.Molecule;
import chem.molgraph.domain.Stereo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enumerates (non-induced) subgraph monomorphisms from a connected pattern into a
 * target molecule. Each returned mapping is patternAtomId -> targetAtomId.
 * Atoms with originTag on the pattern only match targets carrying the same originTag.
 */
public final class SubgraphMatcher {

    private SubgraphMatcher() {
    }

    public static class Enumeration {
        public final List<Map<String, String>> mappings = new ArrayList<>();
        public boolean limitReached;
    }

    public static Enumeration enumerate(Molecule pattern, Molecule target, int limit) {
        Enumeration result = new Enumeration();
        if (pattern.atoms().size() > target.atoms().size()) {
            return result;
        }
        Map<String, Atom> tatoms = target.atomMap();

        Map<String, List<String>> padj = adjacency(pattern);
        Map<String, List<String>> tadj = adjacency(target);

        List<String> order = patternOrder(pattern, padj);

        Map<String, String> map = new HashMap<>();
        Map<String, String> used = new HashMap<>();
        search(0, order, pattern, target, padj, tadj, tatoms, map, used, result, limit);
        return result;
    }

    private static void search(int depth, List<String> order,
                               Molecule pattern, Molecule target,
                               Map<String, List<String>> padj, Map<String, List<String>> tadj,
                               Map<String, Atom> tatoms,
                               Map<String, String> map, Map<String, String> used,
                               Enumeration result, int limit) {
        if (result.mappings.size() >= limit) {
            result.limitReached = true;
            return;
        }
        if (depth == order.size()) {
            result.mappings.add(new LinkedHashMap<>(map));
            return;
        }
        String p = order.get(depth);
        Atom pa = pattern.atomMap().get(p);
        List<String> candidates = candidatesFor(p, pattern, target, map, padj, tadj, tatoms);
        for (String t : candidates) {
            if (used.containsKey(t)) {
                continue;
            }
            Atom ta = tatoms.get(t);
            if (!atomCompatible(pa, ta)) {
                continue;
            }
            map.put(p, t);
            used.put(t, p);
            search(depth + 1, order, pattern, target, padj, tadj, tatoms, map, used, result, limit);
            used.remove(t);
            map.remove(p);
            if (result.limitReached) {
                return;
            }
        }
    }

    private static List<String> candidatesFor(String p, Molecule pattern, Molecule target,
                                              Map<String, String> map,
                                              Map<String, List<String>> padj,
                                              Map<String, List<String>> tadj,
                                              Map<String, Atom> tatoms) {
        Atom pa = pattern.atomMap().get(p);
        List<String> mappedNeighbors = new ArrayList<>();
        for (String pn : padj.get(p)) {
            String tn = map.get(pn);
            if (tn != null) {
                mappedNeighbors.add(tn);
            }
        }
        List<String> pool = new ArrayList<>();
        if (mappedNeighbors.isEmpty()) {
            pool.addAll(tadj.keySet());
        } else {
            pool.addAll(tadj.get(mappedNeighbors.get(0)));
            for (int k = 1; k < mappedNeighbors.size(); k++) {
                pool.retainAll(tadj.get(mappedNeighbors.get(k)));
            }
        }
        List<String> out = new ArrayList<>();
        for (String t : pool) {
            if (map.containsValue(t)) {
                continue;
            }
            Atom ta = tatoms.get(t);
            if (!atomCompatible(pa, ta)) {
                continue;
            }
            if (pa.originTag() != null && !pa.originTag().equals(ta.originTag())) {
                continue;
            }
            if (pa.stereo() != Stereo.NONE) {
                if (pa.stereoOrder() == null || tadj.get(t).size() < pa.stereoOrder().size()) {
                    continue;
                }
            }
            boolean feasible = true;
            for (String pn : padj.get(p)) {
                String tn = map.get(pn);
                if (tn != null) {
                    Bond pb = pattern.findBond(p, pn);
                    Bond tb = target.findBond(t, tn);
                    if (tb == null || tb.order() != pb.order()) {
                        feasible = false;
                        break;
                    }
                }
            }
            if (feasible) {
                out.add(t);
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    static boolean atomCompatible(Atom pa, Atom ta) {
        if (!pa.element().equals(ta.element())) {
            return false;
        }
        if (pa.isotope() != 0 && pa.isotope() != ta.isotope()) {
            return false;
        }
        if (pa.charge() != ta.charge()) {
            return false;
        }
        return true;
    }

    private static Map<String, List<String>> adjacency(Molecule mol) {
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (Atom a : mol.atoms()) {
            adj.put(a.id(), new ArrayList<>());
        }
        for (Bond b : mol.bonds()) {
            adj.get(b.a()).add(b.b());
            adj.get(b.b()).add(b.a());
        }
        for (List<String> ns : adj.values()) {
            ns.sort(Comparator.naturalOrder());
        }
        return adj;
    }

    private static List<String> patternOrder(Molecule pattern, Map<String, List<String>> adj) {
        List<String> remaining = new ArrayList<>(adj.keySet());
        List<String> order = new ArrayList<>();
        String start = remaining.stream()
                .max(Comparator.comparingInt((String s) -> adj.get(s).size())
                        .thenComparing(s -> pattern.atomMap().get(s).element(), Comparator.reverseOrder())
                        .thenComparing(Comparator.naturalOrder()))
                .orElseThrow();
        order.add(start);
        remaining.remove(start);
        while (!remaining.isEmpty()) {
            String next = null;
            long bestPlaced = -1;
            long bestUnplaced = Long.MIN_VALUE;
            String bestId = null;
            for (String cand : remaining) {
                long placed = adj.get(cand).stream().filter(order::contains).count();
                if (placed == 0) {
                    continue;
                }
                long unplaced = adj.get(cand).size() - placed;
                if (next == null || placed > bestPlaced
                        || (placed == bestPlaced && -unplaced > bestUnplaced)
                        || (placed == bestPlaced && -unplaced == bestUnplaced && cand.compareTo(bestId) < 0)) {
                    next = cand;
                    bestPlaced = placed;
                    bestUnplaced = -unplaced;
                    bestId = cand;
                }
            }
            if (next == null) {
                next = remaining.stream().sorted().findFirst().orElseThrow();
            }
            order.add(next);
            remaining.remove(next);
        }
        return order;
    }

}
