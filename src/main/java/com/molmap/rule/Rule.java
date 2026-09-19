package com.molmap.rule;

import com.molmap.model.MolGraph;

import java.util.Collections;
import java.util.List;

/**
 * A structural transformation rule.
 *
 * <p>{@code lhs} is the pattern to match; {@code rhs} is the rewrite result.
 * Atom ids shared between lhs and rhs denote preserved/mapped atoms. LHS-only
 * atoms are deleted; RHS-only atoms are created. Bonds between preserved atoms
 * that appear identically on both sides are retained; otherwise they are
 * removed and the RHS bonds are added.
 *
 * <p>{@code valid}/{@code validationReasons}/{@code fingerprint} are derived
 * metadata (never author-edited), stamped with the rule's version so that old
 * cases remain tied to the rule content they were confirmed against.
 */
public final class Rule {
    private final String id;
    private final String name;
    private final int version;
    private final long createdAt;
    private final MolGraph lhs;
    private final MolGraph rhs;
    private final boolean valid;
    private final List<String> validationReasons;
    private final String fingerprint;

    public Rule(String id, String name, int version, long createdAt, MolGraph lhs, MolGraph rhs,
                boolean valid, List<String> validationReasons, String fingerprint) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.createdAt = createdAt;
        this.lhs = lhs;
        this.rhs = rhs;
        this.valid = valid;
        this.validationReasons = validationReasons == null ? List.of() : List.copyOf(validationReasons);
        this.fingerprint = fingerprint;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public int getVersion() { return version; }
    public long getCreatedAt() { return createdAt; }
    public MolGraph getLhs() { return lhs; }
    public MolGraph getRhs() { return rhs; }
    public boolean isValid() { return valid; }
    public List<String> getValidationReasons() { return Collections.unmodifiableList(validationReasons); }
    public String getFingerprint() { return fingerprint; }

    /** Content identity for a specific version: independent of name/timestamps. */
    public String versionRef() { return id + "@v" + version; }
}
