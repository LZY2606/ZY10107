package chem.molgraph;

import chem.molgraph.domain.Atom;
import chem.molgraph.domain.Bond;
import chem.molgraph.domain.BondStereo;
import chem.molgraph.domain.Molecule;
import chem.molgraph.domain.Stereo;
import chem.molgraph.engine.Canonical;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalTest {

    private Molecule ethanol(String... ids) {
        return new Molecule(List.of(
                new Atom(ids[0], "C", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom(ids[1], "C", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom(ids[2], "O", 0, 0, Stereo.NONE, null, null, null, null)),
                List.of(new Bond(ids[0], ids[1], 1, BondStereo.NONE),
                        new Bond(ids[1], ids[2], 1, BondStereo.NONE)));
    }

    @Test
    void renamingAtomsDoesNotChangeFingerprint() {
        Molecule a = ethanol("a", "b", "c");
        Molecule b = ethanol("zz", "mm", "qq");
        assertEquals(Canonical.canonicalize(a).fingerprint(), Canonical.canonicalize(b).fingerprint());
    }

    @Test
    void isotopeChargeAndBondOrderChangeFingerprint() {
        Molecule base = ethanol("a", "b", "c");
        Molecule isotope = new Molecule(List.of(
                new Atom("a", "C", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("b", "C", 13, 0, Stereo.NONE, null, null, null, null),
                new Atom("c", "O", 0, 0, Stereo.NONE, null, null, null, null)),
                List.of(new Bond("a", "b", 1, BondStereo.NONE),
                        new Bond("b", "c", 1, BondStereo.NONE)));
        Molecule charged = new Molecule(List.of(
                new Atom("a", "C", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("b", "C", 0, 1, Stereo.NONE, null, null, null, null),
                new Atom("c", "O", 0, 0, Stereo.NONE, null, null, null, null)),
                List.of(new Bond("a", "b", 1, BondStereo.NONE),
                        new Bond("b", "c", 1, BondStereo.NONE)));
        Molecule doubleBond = new Molecule(List.of(
                new Atom("a", "C", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("b", "C", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("c", "O", 0, 0, Stereo.NONE, null, null, null, null)),
                List.of(new Bond("a", "b", 1, BondStereo.NONE),
                        new Bond("b", "c", 2, BondStereo.NONE)));
        String fp = Canonical.canonicalize(base).fingerprint();
        assertNotEquals(fp, Canonical.canonicalize(isotope).fingerprint());
        assertNotEquals(fp, Canonical.canonicalize(charged).fingerprint());
        assertNotEquals(fp, Canonical.canonicalize(doubleBond).fingerprint());
    }

    @Test
    void tetrahedralEnantiomersHaveDifferentFingerprints() {
        Atom centerA = new Atom("c", "C", 0, 0, Stereo.UP, List.of("a", "b", "d", "e"), null, null, null);
        Atom centerB = new Atom("c", "C", 0, 0, Stereo.DOWN, List.of("a", "b", "d", "e"), null, null, null);
        Molecule molA = tetra(centerA);
        Molecule molB = tetra(centerB);
        assertNotEquals(Canonical.canonicalize(molA).fingerprint(),
                Canonical.canonicalize(molB).fingerprint());
    }

    private Molecule tetra(Atom center) {
        return new Molecule(List.of(center,
                new Atom("a", "Cl", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("b", "Br", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("d", "F", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("e", "H", 0, 0, Stereo.NONE, null, null, null, null)),
                List.of(new Bond("c", "a", 1, BondStereo.NONE),
                        new Bond("c", "b", 1, BondStereo.NONE),
                        new Bond("c", "d", 1, BondStereo.NONE),
                        new Bond("c", "e", 1, BondStereo.NONE)));
    }
}
