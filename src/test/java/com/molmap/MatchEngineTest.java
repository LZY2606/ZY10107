package com.molmap;

import com.molmap.model.Atom;
import com.molmap.model.Bond;
import com.molmap.model.MolGraph;
import com.molmap.rule.Candidate;
import com.molmap.rule.Rule;
import com.molmap.rule.MatchEngine;
import com.molmap.rule.RunResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.molmap.Graphs.atom;
import static com.molmap.Graphs.bond;
import static com.molmap.Graphs.graph;
import static org.junit.jupiter.api.Assertions.*;

class MatchEngineTest {

    private final MatchEngine engine = new MatchEngine();

    private Rule rule(MolGraph lhs, MolGraph rhs, boolean valid) {
        return new Rule("r", "t", 1, 0L, lhs, rhs, valid, List.of(), "fp");
    }

    /** Substitute a single atom element would break conservation; here we delete
     *  nothing and only flip a bond order on a symmetric motif. */
    private MolGraph waterLike(String... ids) {
        return graph(
                List.of(atom(ids[0], "O", null, 0, null),
                        atom(ids[1], "H", null, 0, null),
                        atom(ids[2], "H", null, 0, null)),
                List.of(bond(ids[0], ids[1], "1"), bond(ids[0], ids[2], "1")));
    }

    @Test
    void noMatchHasExplicitStatus() {
        MolGraph lhs = graph(List.of(atom("x", "C")), List.of());
        MolGraph rhs = graph(List.of(atom("x", "C")), List.of());
        MolGraph input = graph(List.of(atom("i", "N")), List.of());
        RunResult r = engine.run(rule(lhs, rhs, true), input, 100);
        assertEquals(RunResult.NO_MATCH, r.status());
        assertTrue(r.candidates().isEmpty());
        assertNotEquals(RunResult.OK, r.status());
    }

    @Test
    void invalidInputAndInvalidRuleAreSeparateStates() {
        MolGraph good = graph(List.of(atom("x", "C")), List.of());
        MolGraph input = graph(List.of(new Atom("", "C", null, 0, null)), List.of());
        RunResult r = engine.run(rule(good, good, true), input, 100);
        assertEquals(RunResult.INVALID_INPUT, r.status());

        Rule invalid = new Rule("r", "t", 1, 0L, good, good, false,
                List.of("RULE:LHS_EMPTY: nope"), "fp");
        RunResult r2 = engine.run(invalid, waterLike("a", "b", "c"), 100);
        assertEquals(RunResult.INVALID_RULE, r2.status());
    }

    @Test
    void symmetricAtomsProduceMergedCandidateWithMultiplicity() {
        // Water: the two H are symmetry equivalent. Rule swaps which H is h1/h2,
        // but product is identical; a single canonical candidate keeps count.
        MolGraph lhs = waterLike("o", "h1", "h2");
        MolGraph rhs = graph(
                List.of(atom("o", "O"), atom("h1", "H"), atom("h2", "H")),
                List.of(bond("o", "h1", "1"), bond("o", "h2", "1")));
        MolGraph input = waterLike("O1", "H1", "H2");
        RunResult r = engine.run(rule(lhs, rhs, true), input, 100);
        assertEquals(RunResult.OK, r.status(), r.message());
        assertEquals(2, r.rawMappingCount());
        assertEquals(1, r.candidateCount());
        assertEquals(2, r.candidates().get(0).multiplicity());
    }

    @Test
    void limitReachedIsDistinctAndReturnsNothing() {
        // 6 equivalent carbons in a ring, single-atom pattern -> 6 raw mappings,
        // cap of 3 must trip LIMIT_REACHED and return no partial candidates.
        List<Atom> atoms = new java.util.ArrayList<>();
        List<Bond> bonds = new java.util.ArrayList<>();
        int n = 6;
        for (int i = 0; i < n; i++) atoms.add(atom("c" + i, "C"));
        for (int i = 0; i < n; i++) bonds.add(bond("c" + i, "c" + ((i + 1) % n), "1"));
        MolGraph ring = graph(atoms, bonds);
        MolGraph lhs = graph(List.of(atom("x", "C")), List.of());
        MolGraph rhs = graph(List.of(atom("x", "C")), List.of());
        RunResult r = engine.run(rule(lhs, rhs, true), ring, 3);
        assertEquals(RunResult.LIMIT_REACHED, r.status());
        assertTrue(r.candidates().isEmpty());
        assertTrue(r.capped());
    }

    @Test
    void addingBondProducesFingerprintedCandidateAndConservationCheck() {
        // Pattern is an ethyl fragment C-C; RHS turns it into C=C. Elements and
        // charge stay conserved; no uninvolved atoms touched.
        MolGraph input = graph(
                List.of(atom("a", "C"), atom("b", "C"), atom("x", "H")),
                List.of(bond("a", "b", "1"), bond("b", "x", "1")));
        MolGraph lhs = graph(
                List.of(atom("p", "C"), atom("q", "C")),
                List.of(bond("p", "q", "1")));
        MolGraph rhs = graph(
                List.of(atom("p", "C"), atom("q", "C")),
                List.of(bond("p", "q", "2")));
        RunResult r = engine.run(rule(lhs, rhs, true), input, 100);
        assertEquals(RunResult.OK, r.status(), r.message());
        Candidate c = r.candidates().get(0);
        assertTrue(c.acceptable(), c.violations().toString());
        assertNotNull(c.productFingerprint());
        // An order change removes the single bond and adds the double bond, but
        // the bond to the uninvolved H must be preserved.
        assertEquals(1, c.addedBonds().size());
        assertEquals(1, c.removedBonds().size());
        assertFalse(c.deletedInputIds().contains("x"), "uninvolved H must not be deleted");
        assertNotNull(c.product().atom("x"), "uninvolved H survives in the product");
    }

    @Test
    void chargeDifferenceIsReportedAsViolation() {
        // Pattern atom neutral, RHS atom +1 -> product net charge changes.
        MolGraph input = graph(List.of(atom("a", "N", null, 0, null)), List.of());
        MolGraph lhs = graph(List.of(atom("p", "N", null, 0, null)), List.of());
        MolGraph rhs = graph(List.of(atom("p", "N", null, 1, null)), List.of());
        RunResult r = engine.run(rule(lhs, rhs, true), input, 100);
        Candidate c = r.candidates().get(0);
        assertFalse(c.acceptable());
        assertTrue(c.violations().stream().anyMatch(v -> v.code().equals("CHARGE_NOT_CONSERVED")));
    }

    @Test
    void isotopeAndStereoAreMatchingConstraints() {
        MolGraph input = graph(List.of(
                atom("a", "C", 13, 0, "TH"),
                atom("b", "C", 12, 0, null)),
                List.of(bond("a", "b", "1")));
        MolGraph lhsIso = graph(List.of(atom("p", "C", 13, 0, "TH")), List.of());
        MolGraph rhsIso = graph(List.of(atom("p", "C", 13, 0, "TH")), List.of());
        RunResult hit = engine.run(rule(lhsIso, rhsIso, true), input, 100);
        assertEquals(RunResult.OK, hit.status());

        MolGraph lhsWrong = graph(List.of(atom("p", "C", 14, 0, null)), List.of());
        MolGraph rhsWrong = graph(List.of(atom("p", "C", 14, 0, null)), List.of());
        RunResult miss = engine.run(rule(lhsWrong, rhsWrong, true), input, 100);
        assertEquals(RunResult.NO_MATCH, miss.status());
    }

    @Test
    void candidatesAreDeterministicallyOrderedRegardlessOfTraversal() {
        MolGraph input = graph(
                List.of(atom("a", "C"), atom("b", "N"), atom("c", "C")),
                List.of(bond("a", "b", "1"), bond("b", "c", "1")));
        MolGraph lhs = graph(List.of(atom("p", "C")), List.of());
        MolGraph rhs = graph(List.of(atom("p", "C")), List.of());
        RunResult r1 = engine.run(rule(lhs, rhs, true), input, 100);
        RunResult r2 = engine.run(rule(lhs, rhs, true), input, 100);
        assertEquals(r1.candidates().get(0).productFingerprint(),
                r2.candidates().get(0).productFingerprint());
    }
}
