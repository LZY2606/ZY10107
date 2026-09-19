package chem.molgraph.domain;

import java.util.List;
import java.util.Map;

public record CandidateView(
        String fingerprint,
        String canonical,
        int mappingCount,
        Map<String, String> representativeMapping,
        Molecule product,
        List<String> keptAtoms,
        List<String> deletedAtoms,
        List<String> addedAtoms,
        List<String> stereoChangedAtoms,
        List<String> keptBonds,
        List<String> deletedBonds,
        List<String> addedBonds,
        Map<String, Integer> elementDelta,
        int chargeDelta,
        List<Issue> issues,
        List<String> unparticipatedAtoms) {
}
