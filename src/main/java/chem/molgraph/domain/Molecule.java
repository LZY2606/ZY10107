package chem.molgraph.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Molecule(List<Atom> atoms, List<Bond> bonds) {

    public Molecule {
        atoms = atoms == null ? List.of() : List.copyOf(atoms);
        bonds = bonds == null ? List.of() : List.copyOf(bonds);
    }

    public Map<String, Atom> atomMap() {
        Map<String, Atom> m = new LinkedHashMap<>();
        for (Atom a : atoms) m.put(a.id(), a);
        return m;
    }

    public List<Bond> bondsOf(String atomId) {
        List<Bond> out = new ArrayList<>();
        for (Bond b : bonds) if (b.a().equals(atomId) || b.b().equals(atomId)) out.add(b);
        return out;
    }

    public String other(Bond b, String atomId) {
        return b.a().equals(atomId) ? b.b() : b.a();
    }

    public Bond findBond(String u, String v) {
        for (Bond b : bonds) {
            if ((b.a().equals(u) && b.b().equals(v)) || (b.a().equals(v) && b.b().equals(u))) return b;
        }
        return null;
    }
}
