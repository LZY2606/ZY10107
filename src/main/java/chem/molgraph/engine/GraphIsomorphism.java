package chem.molgraph.engine;

import chem.molgraph.domain.Atom;
import chem.molgraph.domain.Bond;
import chem.molgraph.domain.Molecule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Labeled-graph isomorphism used to merge symmetry-equivalent mappings.
 * Labels include element / isotope / charge and tetrahedral stereo descriptors,
 * so an isomorphism here is an automorphism of the chemically labeled product.
 */
public final class GraphIsomorphism {

    private GraphIsomorphism() {
    }

    public static boolean isomorphic(Molecule a, Molecule b) {
        if (a.atoms().size() != b.atoms().size() || a.bonds().size() != b.bonds().size()) {
            return false;
        }
        int n = a.atoms().size();
        Map<String, Integer> ia = index(a);
        Map<String, Integer> ib = index(b);

        Map<Integer, Integer> map = new HashMap<>();
        boolean[] used = new boolean[n];
        return extend(0, a, b, ia, ib, map, used);
    }

    private static boolean extend(int depth, Molecule a, Molecule b,
                                  Map<String, Integer> ia, Map<String, Integer> ib,
                                  Map<Integer, Integer> map, boolean[] used) {
        if (depth == a.atoms().size()) {
            return consistent(map, a, b, ia, ib);
        }
        Atom pa = a.atoms().get(depth);
        for (int j = 0; j < b.atoms().size(); j++) {
            if (used[j]) {
                continue;
            }
            Atom pb = b.atoms().get(j);
            if (!sameLabel(pa, pb)) {
                continue;
            }
            map.put(depth, j);
            used[j] = true;
            if (partialConsistent(map, a, b, ia, ib)
                    && extend(depth + 1, a, b, ia, ib, map, used)) {
                return true;
            }
            used[j] = false;
            map.remove(depth);
        }
        return false;
    }

    private static boolean sameLabel(Atom a, Atom b) {
        if (!a.element().equals(b.element()) || a.isotope() != b.isotope() || a.charge() != b.charge()) {
            return false;
        }
        if (a.stereo() != b.stereo()) {
            return false;
        }
        return true;
    }

    private static boolean partialConsistent(Map<Integer, Integer> map, Molecule a, Molecule b,
                                             Map<String, Integer> ia, Map<String, Integer> ib) {
        for (Bond bond : a.bonds()) {
            Integer u = map.get(ia.get(bond.a()));
            Integer v = map.get(ia.get(bond.b()));
            if (u == null || v == null) {
                continue;
            }
            Bond other = b.findBond(b.atoms().get(u).id(), b.atoms().get(v).id());
            if (other == null || other.order() != bond.order() || other.stereo() != bond.stereo()) {
                return false;
            }
        }
        // tetrahedral stereo: mapped neighbor lists must have the same parity
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            Atom aa = a.atoms().get(e.getKey());
            Atom bb = b.atoms().get(e.getValue());
            if (aa.stereoOrder() == null || bb.stereoOrder() == null) {
                if (aa.stereoOrder() == null && bb.stereoOrder() == null) {
                    continue;
                }
                return false;
            }
            List<String> mappedA = new ArrayList<>();
            for (String n : aa.stereoOrder()) {
                Integer idx = ia.get(n);
                Integer mapped = map.get(idx);
                if (mapped == null) {
                    mappedA = null;
                    break;
                }
                mappedA.add(b.atoms().get(mapped).id());
            }
            if (mappedA == null) {
                continue;
            }
            if (mappedA.size() != bb.stereoOrder().size()) {
                return false;
            }
            boolean sameSet = mappedA.containsAll(bb.stereoOrder());
            if (!sameSet) {
                return false;
            }
            int parity = StereoMath.permutationParity(mappedA, bb.stereoOrder());
            int signA = StereoMath.sign(aa.stereo());
            int signB = StereoMath.sign(bb.stereo());
            if (signA != signB * (parity == 0 ? 1 : -1)) {
                return false;
            }
        }
        return true;
    }

    private static boolean consistent(Map<Integer, Integer> map, Molecule a, Molecule b,
                                      Map<String, Integer> ia, Map<String, Integer> ib) {
        if (a.bonds().size() != b.bonds().size()) {
            return false;
        }
        return partialConsistent(map, a, b, ia, ib);
    }

    private static Map<String, Integer> index(Molecule mol) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < mol.atoms().size(); i++) {
            m.put(mol.atoms().get(i).id(), i);
        }
        return m;
    }
}
