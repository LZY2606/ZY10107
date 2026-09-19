package com.molmap.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/**
 * An undirected edge between two atom ids. Order is one of 1,2,3 (single,
 * double, triple) or "A" for aromatic. Stereo on a bond is "/" or "\\" for an
 * E/Z double-bond directional marker; null means unspecified.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class Bond {
    private final String from;
    private final String to;
    private final String order;
    private final String stereo;

    public Bond(String from, String to, String order, String stereo) {
        this.from = from;
        this.to = to;
        this.order = order;
        this.stereo = stereo;
    }

    public String getFrom() { return from; }
    public String getTo() { return to; }
    public String getOrder() { return order; }
    public String getStereo() { return stereo; }

    public boolean incident(String atomId) {
        return from.equals(atomId) || to.equals(atomId);
    }

    public String other(String atomId) {
        if (from.equals(atomId)) return to;
        if (to.equals(atomId)) return from;
        throw new IllegalArgumentException(atomId + " is not an endpoint of " + this);
    }

    public Bond replaceEndpoint(String oldId, String newId) {
        return new Bond(from.equals(oldId) ? newId : from,
                to.equals(oldId) ? newId : to, order, stereo);
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Bond bond)) return false;
        return Objects.equals(from, bond.from) && Objects.equals(to, bond.to)
                && Objects.equals(order, bond.order) && Objects.equals(stereo, bond.stereo);
    }

    @Override public int hashCode() { return Objects.hash(from, to, order, stereo); }

    @Override public String toString() { return from + "-" + order + "-" + to; }
}
