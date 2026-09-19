package com.molmap.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/**
 * An atom node. Carries every chemically meaningful property that the reviewer
 * must distinguish: the opaque local id, element, mass isotope, formal charge
 * and tetrahedral stereo descriptor.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class Atom {
    private final String id;
    private final String element;
    private final Integer isotope;
    private final Integer charge;
    private final String stereo;

    public Atom(String id, String element, Integer isotope, Integer charge, String stereo) {
        this.id = id;
        this.element = element;
        this.isotope = isotope;
        this.charge = charge;
        this.stereo = stereo;
    }

    public String getId() { return id; }
    public String getElement() { return element; }
    public Integer getIsotope() { return isotope; }
    public Integer getCharge() { return charge; }
    public String getStereo() { return stereo; }

    public int chargeOrZero() { return charge == null ? 0 : charge; }

    public Atom withId(String newId) {
        return new Atom(newId, element, isotope, charge, stereo);
    }

    public Atom withStereo(String newStereo) {
        return new Atom(id, element, isotope, charge, newStereo);
    }

    public String signature() {
        return element + "|i=" + (isotope == null ? "" : isotope)
                + "|c=" + chargeOrZero()
                + "|s=" + (stereo == null ? "" : stereo);
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Atom atom)) return false;
        return Objects.equals(id, atom.id) && Objects.equals(element, atom.element)
                && Objects.equals(isotope, atom.isotope)
                && Objects.equals(charge, atom.charge) && Objects.equals(stereo, atom.stereo);
    }

    @Override public int hashCode() {
        return Objects.hash(id, element, isotope, charge, stereo);
    }

    @Override public String toString() {
        return id + "(" + element + (isotope == null ? "" : isotope)
                + (chargeOrZero() == 0 ? "" : (chargeOrZero() > 0 ? "+" + chargeOrZero() : chargeOrZero()))
                + ")";
    }
}
