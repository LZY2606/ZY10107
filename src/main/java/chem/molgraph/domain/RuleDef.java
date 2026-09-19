package chem.molgraph.domain;

import java.util.List;

/**
 * Pattern + rewrite definition.
 * Pattern atoms may declare originTag ("requiresOrigin") so a chained rule can demand
 * that an atom originate from the previous step.
 */
public record RuleDef(
        String name,
        String version,
        Molecule pattern,
        List<String> deleteAtoms,
        List<Atom> addAtoms,
        List<String> deleteBonds,
        List<Bond> addBonds,
        String description) {
}
