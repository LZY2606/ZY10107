package chem.molgraph.engine;

import chem.molgraph.domain.Atom;
import chem.molgraph.domain.Bond;
import chem.molgraph.domain.Molecule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic canonical labeling (Weisfeiler-Lehman refinement with deterministic
 * backtracking tie breaking). Produces a canonical string that fully captures
 * element, isotope, charge, bond order and tetrahedral stereo information, plus a
 * stable SHA-256 fingerprint.
 */
public final class Canonical {

    private Canonical() {
    }

    public record Result(String canonical, String fingerprint, Map<String, Integer> ranks) {
    }

    public static Result canonicalize(Molecule mol) {
        int n = mol.atoms().size();
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(mol.atoms().get(i).id(), i);
        }
        Atom[] atoms = mol.atoms().toArray(new Atom[0]);

        int[][] adj = new int[n][];
        int[][] orders = new int[n][];
        for (int i = 0; i < n; i++) {
            List<int[]> neighbors = new ArrayList<>();
            for (Bond b : mol.bonds()) {
                if (index.get(b.a()) == i) {
                    neighbors.add(new int[]{index.get(b.b()), b.order()});
                } else if (index.get(b.b()) == i) {
                    neighbors.add(new int[]{index.get(b.a()), b.order()});
                }
            }
            neighbors.sort(Comparator.comparingInt(p -> p[0]));
            adj[i] = new int[neighbors.size()];
            orders[i] = new int[neighbors.size()];
            for (int k = 0; k < neighbors.size(); k++) {
                adj[i][k] = neighbors.get(k)[0];
                orders[i][k] = neighbors.get(k)[1];
            }
        }

        String[] labels = new String[n];
        for (int i = 0; i < n; i++) {
            labels[i] = initialLabel(atoms[i]);
        }
        boolean changed = true;
        while (changed) {
            Map<String, String> intern = new HashMap<>();
            String[] next = new String[n];
            for (int i = 0; i < n; i++) {
                List<String> neighLabels = new ArrayList<>();
                for (int k = 0; k < adj[i].length; k++) {
                    neighLabels.add(orders[i][k] + ":" + labels[adj[i][k]]);
                }
                neighLabels.sort(Comparator.naturalOrder());
                String raw = labels[i] + "|[" + String.join(",", neighLabels) + "]";
                next[i] = intern.computeIfAbsent(raw, s -> String.valueOf(intern.size()));
            }
            String[] compacted = compact(next);
            changed = false;
            for (int i = 0; i < n; i++) {
                if (!compacted[i].equals(labels[i])) {
                    changed = true;
                }
            }
            labels = compacted;
        }

        int[] rankOf = new int[n];
        for (int i = 0; i < n; i++) {
            rankOf[i] = Integer.parseInt(labels[i]);
        }
        int[] permutation = refineTies(adj, orders, atoms, rankOf);
        int[] canonicalRankOf = new int[n];
        for (int pos = 0; pos < n; pos++) {
            canonicalRankOf[permutation[pos]] = pos;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("V1|atoms:");
        Map<Integer, Integer> firstSeen = new TreeMap<>();
        for (int pos = 0; pos < n; pos++) {
            Atom a = atoms[permutation[pos]];
            if (pos > 0) {
                sb.append(';');
            }
            sb.append(pos).append('=').append(initialLabel(a));
        }
        sb.append("|bonds:");
        List<String> bondStrings = new ArrayList<>();
        for (Bond b : mol.bonds()) {
            int u = canonicalRankOf[index.get(b.a())];
            int v = canonicalRankOf[index.get(b.b())];
            if (u > v) {
                int t = u;
                u = v;
                v = t;
            }
            bondStrings.add(u + "-" + b.order() + "-" + v + (b.stereo() == chem.molgraph.domain.BondStereo.NONE ? "" : ":" + b.stereo()));
        }
        bondStrings.sort(Comparator.naturalOrder());
        sb.append(String.join(",", bondStrings));
        sb.append("|tet:");
        List<String> tetStrings = new ArrayList<>();
        for (int pos = 0; pos < n; pos++) {
            Atom a = atoms[permutation[pos]];
            if (a.stereo() != chem.molgraph.domain.Stereo.NONE && a.stereoOrder() != null && !a.stereoOrder().isEmpty()) {
                List<String> frame = new ArrayList<>();
                for (String nid : a.stereoOrder()) {
                    Integer ni = index.get(nid);
                    if (ni == null) {
                        frame.add("?");
                    } else {
                        frame.add(String.valueOf(canonicalRankOf[ni]));
                    }
                }
                int descriptor = StereoMath.descriptor(a.stereo(), a.stereoOrder(), a.stereoOrder());
                tetStrings.add(pos + "=" + (descriptor > 0 ? "UP" : descriptor < 0 ? "DN" : "0") + "@[" + String.join(",", frame) + "]");
            }
        }
        tetStrings.sort(Comparator.naturalOrder());
        sb.append(String.join(",", tetStrings));

        String canonical = sb.toString();
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            ranks.put(atoms[i].id(), canonicalRankOf[i]);
        }
        return new Result(canonical, sha256(canonical), ranks);
    }

    private static String initialLabel(Atom a) {
        return a.element() + "#" + a.isotope() + "^" + a.charge();
    }

    private static String[] compact(String[] labels) {
        Map<String, String> unique = new TreeMap<>();
        for (String l : labels) {
            unique.put(l, null);
        }
        Map<String, String> mapping = new HashMap<>();
        int counter = 0;
        for (String l : unique.keySet()) {
            mapping.put(l, String.valueOf(counter++));
        }
        String[] out = new String[labels.length];
        for (int i = 0; i < labels.length; i++) {
            out[i] = mapping.get(labels[i]);
        }
        return out;
    }

    /**
     * WL partitions may still contain genuinely equivalent atoms (automorphic).
     * Canonical order is found by DFS: at every step choose the smallest color class
     * with an unassigned atom, try each of its atoms as the next anchor, refine labels
     * by distance to the chosen ordering, and keep the lexicographically smallest
     * complete labeling. Equivalent anchors produce identical refinements.
     */
    private static int[] refineTies(int[][] adj, int[][] orders, Atom[] atoms, int[] colors) {
        int n = adj.length;
        int[] best = null;
        String bestKey = null;
        java.util.List<Integer> classes = new java.util.ArrayList<>();
        for (int c : colors) {
            if (!classes.contains(c)) classes.add(c);
        }
        classes.sort(Integer::compareTo);
        java.util.List<int[]> permutations = new java.util.ArrayList<>();
        permutations.add(new int[0]);
        for (int color : classes) {
            java.util.List<Integer> members = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (colors[i] == color) members.add(i);
            }
            java.util.List<int[]> nextPermutations = new java.util.ArrayList<>();
            for (int[] prefix : permutations) {
                java.util.Set<Integer> placed = new java.util.HashSet<>();
                for (int x : prefix) placed.add(x);
                java.util.List<Integer> remaining = new java.util.ArrayList<>();
                for (int m : members) if (!placed.contains(m)) remaining.add(m);
                java.util.List<java.util.List<Integer>> ordersToTry = permutationsOf(remaining);
                for (java.util.List<Integer> suffix : ordersToTry) {
                    int[] np = new int[prefix.length + suffix.size()];
                    System.arraycopy(prefix, 0, np, 0, prefix.length);
                    for (int k = 0; k < suffix.size(); k++) np[prefix.length + k] = suffix.get(k);
                    nextPermutations.add(np);
                }
            }
            permutations = nextPermutations;
        }
        for (int[] perm : permutations) {
            String key = orderingKey(perm, adj, orders, atoms, colors);
            if (bestKey == null || key.compareTo(bestKey) < 0) {
                bestKey = key;
                best = perm;
            }
        }
        return best;
    }

    private static java.util.List<java.util.List<Integer>> permutationsOf(java.util.List<Integer> items) {
        java.util.List<java.util.List<Integer>> out = new java.util.ArrayList<>();
        if (items.size() <= 1) {
            out.add(new java.util.ArrayList<>(items));
            return out;
        }
        for (int i = 0; i < items.size(); i++) {
            java.util.List<Integer> rest = new java.util.ArrayList<>(items);
            Integer head = rest.remove(i);
            for (java.util.List<Integer> tail : permutationsOf(rest)) {
                java.util.List<Integer> merged = new java.util.ArrayList<>();
                merged.add(head);
                merged.addAll(tail);
                out.add(merged);
            }
        }
        return out;
    }

    private static String orderingKey(int[] perm, int[][] adj, int[][] orders, Atom[] atoms, int[] colors) {
        int n = adj.length;
        int[] position = new int[n];
        java.util.Arrays.fill(position, -1);
        for (int i = 0; i < perm.length; i++) position[perm[i]] = i;
        StringBuilder sb = new StringBuilder();
        for (int pos = 0; pos < perm.length; pos++) {
            int node = perm[pos];
            sb.append(initialLabel(atoms[node])).append('|');
            java.util.List<String> neighborPositions = new java.util.ArrayList<>();
            for (int k = 0; k < adj[node].length; k++) {
                neighborPositions.add(orders[node][k] + ":" + position[adj[node][k]]);
            }
            neighborPositions.sort(java.util.Comparator.naturalOrder());
            sb.append(String.join(",", neighborPositions)).append(';');
        }
        return sb.toString();
    }

    private static String signature(int node, int[][] adj, int[][] orders, boolean[] assigned, int[] order, int placed) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        List<String> parts = new ArrayList<>();
        for (int k = 0; k < adj[node].length; k++) {
            int j = adj[node][k];
            String pos = assigned[j] ? String.valueOf(orderPosition(order, j, placed)) : "_";
            parts.add(orders[node][k] + ":" + pos);
        }
        parts.sort(Comparator.naturalOrder());
        sb.append(String.join(",", parts));
        sb.append('}');
        return sb.toString();
    }

    private static int orderPosition(int[] order, int node, int placed) {
        for (int i = 0; i < placed; i++) {
            if (order[i] == node) {
                return i;
            }
        }
        throw new IllegalStateException("assigned node not found");
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
