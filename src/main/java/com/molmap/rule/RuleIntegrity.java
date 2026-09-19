package com.molmap.rule;

import com.molmap.model.Atom;
import com.molmap.model.Bond;
import com.molmap.model.MolGraph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Internal consistency checks for a transformation rule (beyond plain graph
 * validity). These determine whether the rule itself is usable, distinct from
 * per-run outcomes such as "no match" or "limit reached".
 */
public final class RuleIntegrity {

    public Validation check(MolGraph lhs, MolGraph rhs) {
        List<String> reasons = new ArrayList<>();

        if (lhs.atoms().isEmpty()) {
            reasons.add("RULE:LHS_EMPTY: the match pattern must contain at least one atom");
        }

        Set<String> lhsIds = lhs.atomIds();
        Set<String> rhsIds = rhs.atomIds();

        for (Bond b : rhs.bonds()) {
            if (!rhsIds.contains(b.getFrom()) || !rhsIds.contains(b.getTo())) {
                reasons.add("RULE:RHS_DANGLING_BOND: RHS bond " + b.getFrom() + "->" + b.getTo()
                        + " references an atom absent from RHS");
            }
        }
        for (Bond b : lhs.bonds()) {
            if (!lhsIds.contains(b.getFrom()) || !lhsIds.contains(b.getTo())) {
                reasons.add("RULE:LHS_DANGLING_BOND: LHS bond " + b.getFrom() + "->" + b.getTo()
                        + " references an atom absent from LHS");
            }
        }

        // Preserved atoms (shared id) must not change element or isotope: such a
        // rewrite is chemically meaningless as an atom mapping.
        for (Atom r : rhs.atoms()) {
            Atom l = lhs.atom(r.getId());
            if (l != null) {
                if (!l.getElement().equals(r.getElement())) {
                    reasons.add("RULE:ELEMENT_MUTATION: preserved atom '" + r.getId() + "' changes element "
                            + l.getElement() + "->" + r.getElement());
                }
                int li = l.getIsotope() == null ? 0 : l.getIsotope();
                int ri = r.getIsotope() == null ? 0 : r.getIsotope();
                if (li != ri) {
                    reasons.add("RULE:ISOTOPE_MUTATION: preserved atom '" + r.getId() + "' changes isotope "
                            + li + "->" + ri);
                }
            }
        }

        // Every preserved atom must be connected (in LHS) to at least one other
        // LHS atom; isolated pattern atoms are ambiguous and rejected.
        if (lhs.atoms().size() > 1) {
            Set<String> endpoints = new HashSet<>();
            for (Bond b : lhs.bonds()) { endpoints.add(b.getFrom()); endpoints.add(b.getTo()); }
            for (String id : lhsIds) {
                if (!endpoints.contains(id)) {
                    reasons.add("RULE:ISOLATED_LHS_ATOM: pattern atom '" + id + "' has no bond inside the match");
                }
            }
        }

        // RHS cannot be empty while it deletes atoms unless it explicitly removes
        // everything; allow it only when LHS is a single atom fragment.
        if (rhs.atoms().isEmpty() && lhs.atoms().size() > 1) {
            reasons.add("RULE:RHS_EMPTY: cannot delete a multi-atom pattern without specifying a product");
        }

        return reasons.isEmpty() ? Validation.ok() : Validation.fail(reasons);
    }
}
