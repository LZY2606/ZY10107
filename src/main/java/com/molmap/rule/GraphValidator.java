package com.molmap.rule;

import com.molmap.model.Bond;
import com.molmap.model.MolGraph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure structural validation of an imported molecular graph. */
public final class GraphValidator {

    private static final Set<String> BOND_ORDERS = Set.of("1", "2", "3", "A");
    private static final Set<String> ATOM_STEREO = Set.of("TH", "AL", "U");
    private static final Set<String> BOND_STEREO = Set.of("/", "\\", "U");

    public Validation validate(MolGraph g, String label) {
        List<String> reasons = new ArrayList<>();
        if (g == null) return Validation.fail(List.of(label + ": graph is missing"));
        Set<String> ids = new HashSet<>();
        if (g.atoms().isEmpty()) reasons.add(label + ": graph has no atoms");
        for (var a : g.atoms()) {
            if (a.getId() == null || a.getId().isBlank()) {
                reasons.add(label + ": atom with missing/blank id");
            } else if (!ids.add(a.getId())) {
                reasons.add(label + ": duplicate atom id '" + a.getId() + "'");
            }
            if (a.getElement() == null || a.getElement().isBlank()) {
                reasons.add(label + ": atom '" + a.getId() + "' has no element");
            } else if (!a.getElement().equals(a.getElement().toUpperCase(Locale.ROOT))) {
                reasons.add(label + ": atom '" + a.getId() + "' element must be upper case");
            }
            if (a.getCharge() != null && (a.getCharge() < -8 || a.getCharge() > 8)) {
                reasons.add(label + ": atom '" + a.getId() + "' charge out of range [-8,8]");
            }
            if (a.getStereo() != null && !ATOM_STEREO.contains(a.getStereo())) {
                reasons.add(label + ": atom '" + a.getId() + "' has unknown atom stereo '"
                        + a.getStereo() + "' (allowed TH, AL, U)");
            }
        }
        for (Bond b : g.bonds()) {
            if (!ids.contains(b.getFrom()) || !ids.contains(b.getTo())) {
                reasons.add(label + ": bond " + b.getFrom() + " -> " + b.getTo() + " references an unknown atom");
                continue;
            }
            if (b.getFrom().equals(b.getTo())) {
                reasons.add(label + ": self-loop bond on '" + b.getFrom() + "' is not allowed");
            }
            if (!BOND_ORDERS.contains(b.getOrder())) {
                reasons.add(label + ": bond " + b.getFrom() + " -> " + b.getTo()
                        + " has invalid bond order '" + b.getOrder() + "' (allowed 1,2,3,A)");
            }
            if (b.getStereo() != null && !BOND_STEREO.contains(b.getStereo())) {
                reasons.add(label + ": bond " + b.getFrom() + " -> " + b.getTo()
                        + " has unknown bond stereo '" + b.getStereo() + "' (allowed /, \\, U)");
            }
        }
        return reasons.isEmpty() ? Validation.ok() : Validation.fail(reasons);
    }
}
