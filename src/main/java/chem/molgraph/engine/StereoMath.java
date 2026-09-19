package chem.molgraph.engine;

import chem.molgraph.domain.Stereo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class StereoMath {

    private StereoMath() {
    }

    /** Parity (0 even / 1 odd) of the permutation that turns list a into list b. */
    public static int permutationParity(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            throw new IllegalArgumentException("lists differ in length");
        }
        Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < b.size(); i++) {
            pos.put(b.get(i), i);
        }
        int[] p = new int[a.size()];
        for (int i = 0; i < a.size(); i++) {
            Integer j = pos.get(a.get(i));
            if (j == null) {
                throw new IllegalArgumentException("lists contain different elements");
            }
            p[i] = j;
        }
        int inversions = 0;
        for (int i = 0; i < p.length; i++) {
            for (int j = i + 1; j < p.length; j++) {
                if (p[i] > p[j]) {
                    inversions++;
                }
            }
        }
        return inversions & 1;
    }

    public static int sign(Stereo stereo) {
        return switch (stereo) {
            case UP -> 1;
            case DOWN -> -1;
            case NONE -> 0;
        };
    }

    /** Chirality descriptor of a center evaluated against an arbitrary ordered list of neighbors. */
    public static int descriptor(Stereo stereo, List<String> storedOrder, List<String> neighborFrame) {
        if (stereo == Stereo.NONE || storedOrder == null || storedOrder.isEmpty()) {
            return 0;
        }
        int s = sign(stereo);
        return permutationParity(storedOrder, neighborFrame) == 0 ? s : -s;
    }
}
