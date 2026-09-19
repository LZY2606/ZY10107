package com.molmap.rule;

import com.molmap.model.MolGraph;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One rewrite outcome for one embedding of the rule pattern in the input.
 *
 * @param index             zero-based position in the default display order
 * @param mapping           lhs atom id  ->  input atom id
 * @param product           rewritten graph (temp ids for newly created atoms)
 * @param retainedInputIds  input atom ids preserved
 * @param deletedInputIds   input atom ids removed
 * @param addedAtomIds      product ids created (temporary, t#-prefixed)
 * @param addedBonds        bonds not present in the input
 * @param removedBonds      input bonds not present in the product
 * @param stereoChanges     human readable stereo transitions on retained atoms/bonds
 * @param fingerprints      stable fingerprints of input and product
 * @param orbitSignature    canonical signature that merges symmetry-equivalent maps
 * @param multiplicity      equivalent maps merged into this canonical candidate
 * @param violations        conservation / charge / uninvolved diagnostics
 */
public record Candidate(
        int index,
        Map<String, String> mapping,
        MolGraph product,
        Set<String> retainedInputIds,
        Set<String> deletedInputIds,
        Set<String> addedAtomIds,
        List<String> addedBonds,
        List<String> removedBonds,
        List<String> stereoChanges,
        String inputFingerprint,
        String productFingerprint,
        String orbitSignature,
        int multiplicity,
        List<Violation> violations
) {
    public boolean acceptable() { return violations.isEmpty(); }
}
