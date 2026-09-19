package com.molmap.rule;

import com.molmap.model.Atom;
import com.molmap.model.Bond;
import com.molmap.model.MolGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Enumerates subgraph isomorphisms (the match subgraph -> input graph) with
 * isotope/charge/bond-order/stereo constraints, then applies the rewrite and
 * builds canonical, symmetry-merged candidates with per-candidate diagnostics.
 */
public final class MatchEngine {

    private final Canonicalizer canonicalizer = new Canonicalizer();

    /** Enumerate up to {@code hardCap} raw mappings; null return means cap exceeded. */
    private List<Map<String, String>> enumerate(MolGraph pattern, MolGraph target, int hardCap) {
        List<String> pIds = new ArrayList<>();
        for (Atom a : pattern.atoms()) pIds.add(a.getId());
        Map<String, List<Bond>> pAdj = pattern.adjacency();
        Map<String, List<Bond>> tAdj = target.adjacency();

        List<Map<String, String>> results = new ArrayList<>();
        Map<String, String> assignment = new LinkedHashMap<>();
        if (backtrack(pIds, 0, assignment, pattern, target, pAdj, tAdj, results, hardCap)) {
            return null;
        }
        return results;
    }

    /** Returns true when the hard cap is exceeded (caller should abort). */
    private boolean backtrack(List<String> pIds, int idx, Map<String, String> assignment,
                              MolGraph pattern, MolGraph target,
                              Map<String, List<Bond>> pAdj, Map<String, List<Bond>> tAdj,
                              List<Map<String, String>> results, int hardCap) {
        if (idx == pIds.size()) {
            results.add(new LinkedHashMap<>(assignment));
            return results.size() > hardCap;
        }
        String pid = pIds.get(idx);
        Atom pat = pattern.atom(pid);
        for (Atom cand : target.atoms()) {
            if (assignment.containsValue(cand.getId())) continue;
            if (!atomCompatible(pat, cand)) continue;
            if (!edgesConsistent(pid, cand.getId(), assignment, pAdj, tAdj)) continue;
            assignment.put(pid, cand.getId());
            if (backtrack(pIds, idx + 1, assignment, pattern, target, pAdj, tAdj, results, hardCap)) {
                return true;
            }
            assignment.remove(pid);
        }
        return false;
    }

    private boolean atomCompatible(Atom pat, Atom cand) {
        if (!pat.getElement().equals(cand.getElement())) return false;
        int pi = pat.getIsotope() == null ? 0 : pat.getIsotope();
        int ci = cand.getIsotope() == null ? 0 : cand.getIsotope();
        if (pi != ci) return false;
        if (pat.chargeOrZero() != cand.chargeOrZero()) return false;
        if (pat.getStereo() != null && !pat.getStereo().equals(cand.getStereo())) return false;
        return true;
    }

    private boolean edgesConsistent(String pid, String mappedId, Map<String, String> assignment,
                                   Map<String, List<Bond>> pAdj, Map<String, List<Bond>> tAdj) {
        for (Bond pb : pAdj.get(pid)) {
            String pNeighbour = pb.other(pid);
            String mappedNeighbour = assignment.get(pNeighbour);
            if (mappedNeighbour == null) continue;
            boolean found = false;
            for (Bond tb : tAdj.get(mappedId)) {
                if (!tb.other(mappedId).equals(mappedNeighbour)) continue;
                if (sameOrder(pb.getOrder(), tb.getOrder())) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    private boolean sameOrder(String a, String b) {
        String x = a == null ? "1" : a;
        String y = b == null ? "1" : b;
        return x.equals(y);
    }

    private record Product(MolGraph graph, Set<String> retained, Set<String> deleted,
                           Set<String> added, List<String> addedBonds, List<String> removedBonds,
                           List<String> stereoChanges, int tempSeqStart) {}

    private Product rewrite(Rule rule, MolGraph input, Map<String, String> mapping) {
        MolGraph lhs = rule.getLhs();
        MolGraph rhs = rule.getRhs();
        Set<String> lhsIds = lhs.atomIds();
        Set<String> rhsIds = rhs.atomIds();

        Set<String> mappedInput = new LinkedHashSet<>(mapping.values());
        Set<String> retainedPattern = new TreeSet<>(lhsIds);
        retainedPattern.retainAll(rhsIds);
        Set<String> deletedPattern = new TreeSet<>(lhsIds);
        deletedPattern.removeAll(rhsIds);

        Set<String> retainedInputIds = new TreeSet<>();
        for (String pid : retainedPattern) retainedInputIds.add(mapping.get(pid));
        Set<String> deletedInputIds = new TreeSet<>();
        for (String pid : deletedPattern) deletedInputIds.add(mapping.get(pid));

        // Temporary id space, guaranteed not to collide with existing input ids.
        int seq = 1;
        Set<String> inputIds = input.atomIds();
        while (inputIds.contains("t" + seq) || mapping.containsValue("t" + seq)) seq++;

        List<Atom> outAtoms = new ArrayList<>();
        Map<String, String> rhsToProduct = new LinkedHashMap<>();
        Set<String> addedIds = new TreeSet<>();

        // 1) atoms untouched by the match
        for (Atom a : input.atoms()) {
            if (!mappedInput.contains(a.getId())) outAtoms.add(a);
        }
        // 2) retained atoms: product attributes come from the RHS (charge/stereo may change)
        for (Atom ra : rhs.atoms()) {
            if (retainedPattern.contains(ra.getId())) {
                outAtoms.add(new Atom(mapping.get(ra.getId()), ra.getElement(),
                        ra.getIsotope(), ra.getCharge(), ra.getStereo()));
                rhsToProduct.put(ra.getId(), mapping.get(ra.getId()));
            }
        }
        // 3) newly created atoms get temporary ids
        for (Atom na : rhs.atoms()) {
            if (!lhsIds.contains(na.getId())) {
                String tid = "t" + (seq++);
                outAtoms.add(na.withId(tid));
                rhsToProduct.put(na.getId(), tid);
                addedIds.add(tid);
            }
        }

        // Bond bookkeeping.
        Set<String> deletedInputSet = deletedInputIds;
        List<Bond> outBonds = new ArrayList<>();
        List<String> removedBondDesc = new ArrayList<>();
        List<String> addedBondDesc = new ArrayList<>();
        List<String> stereoChanges = new ArrayList<>();

        // Input bonds fall into four cases:
        //  - neither endpoint matched: uninvolved bond, kept byte-for-byte
        //  - exactly one endpoint matched (other uninvolved): connectivity to the
        //    uninvolved substructure is preserved unchanged
        //  - both matched & one deleted: removed
        //  - both matched & retained: survives only if an identical RHS bond exists
        for (Bond ib : input.bonds()) {
            boolean fMatched = mappedInput.contains(ib.getFrom());
            boolean tMatched = mappedInput.contains(ib.getTo());
            if (!fMatched || !tMatched) {
                // At least one endpoint is uninvolved. Keep the bond unless the
                // other endpoint is a deleted atom (then it cannot survive).
                if (deletedInputSet.contains(ib.getFrom()) || deletedInputSet.contains(ib.getTo())) {
                    removedBondDesc.add(describeBond(ib, input));
                    continue;
                }
                outBonds.add(ib);
                continue;
            }
            if (deletedInputSet.contains(ib.getFrom()) || deletedInputSet.contains(ib.getTo())) {
                removedBondDesc.add(describeBond(ib, input));
                continue;
            }
            String rp1 = reverseMap(mapping, ib.getFrom());
            String rp2 = reverseMap(mapping, ib.getTo());
            Bond matchingRhs = findBond(rhs, rp1, rp2, ib.getOrder());
            if (matchingRhs == null) {
                removedBondDesc.add(describeBond(ib, input));
                continue;
            }
            String ns = matchingRhs.getStereo();
            outBonds.add(new Bond(ib.getFrom(), ib.getTo(), ib.getOrder(), ns));
            if (!eq(ib.getStereo(), ns)) {
                stereoChanges.add("BOND " + ib.getFrom() + "=" + ib.getTo() + " stereo "
                        + dash(ib.getStereo()) + " -> " + dash(ns));
            }
        }

        // New RHS bonds (at least one newly created atom, or an edge that did not exist).
        for (Bond rb : rhs.bonds()) {
            boolean betweenRetained = retainedPattern.contains(rb.getFrom()) && retainedPattern.contains(rb.getTo());
            String p1 = rhsToProduct.get(rb.getFrom());
            String p2 = rhsToProduct.get(rb.getTo());
            if (betweenRetained) {
                Bond existed = findBond(input, p1, p2, rb.getOrder());
                if (existed == null) {
                    outBonds.add(new Bond(p1, p2, rb.getOrder(), rb.getStereo()));
                    addedBondDesc.add(p1 + " " + rb.getOrder() + " " + p2
                            + (rb.getStereo() == null ? "" : " " + rb.getStereo()));
                }
            } else {
                outBonds.add(new Bond(p1, p2, rb.getOrder(), rb.getStereo()));
                addedBondDesc.add(p1 + " " + rb.getOrder() + " " + p2
                        + (rb.getStereo() == null ? "" : " " + rb.getStereo()));
            }
        }

        // Atom stereo changes on retained atoms.
        for (String pid : retainedPattern) {
            Atom before = input.atom(mapping.get(pid));
            Atom after = rule.getRhs().atom(pid);
            if (!eq(before.getStereo(), after.getStereo())) {
                stereoChanges.add("ATOM " + before.getId() + " (" + before.getElement() + ") stereo "
                        + dash(before.getStereo()) + " -> " + dash(after.getStereo()));
            }
        }

        MolGraph product = new MolGraph(outAtoms, outBonds);
        return new Product(product, retainedInputIds, deletedInputIds, addedIds,
                addedBondDesc, removedBondDesc, stereoChanges, seq);
    }

    private static String reverseMap(Map<String, String> mapping, String inputId) {
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            if (e.getValue().equals(inputId)) return e.getKey();
        }
        return null;
    }

    private static Bond findBond(MolGraph g, String a, String b, String order) {
        if (a == null || b == null) return null;
        for (Bond bd : g.bondsBetween(a, b)) {
            String o = bd.getOrder() == null ? "1" : bd.getOrder();
            String want = order == null ? "1" : order;
            if (o.equals(want)) return bd;
        }
        return null;
    }

    private static String describeBond(Bond b, MolGraph g) {
        Atom f = g.atom(b.getFrom());
        Atom t = g.atom(b.getTo());
        return b.getFrom() + "(" + (f == null ? "?" : f.getElement()) + ") " + b.getOrder()
                + " " + b.getTo() + "(" + (t == null ? "?" : t.getElement()) + ")";
    }

    private static boolean eq(String a, String b) { return java.util.Objects.equals(a, b); }
    private static String dash(String s) { return s == null ? "-" : s; }

    public RunResult run(Rule rule, MolGraph input, int matchLimit) {
        Validation inputCheck = new GraphValidator().validate(input, "input");
        if (!inputCheck.valid()) {
            return RunResult.terminal(rule.versionRef(), RunResult.INVALID_INPUT,
                    String.join("; ", inputCheck.reasons()), matchLimit);
        }
        if (!rule.isValid()) {
            return RunResult.terminal(rule.versionRef(), RunResult.INVALID_RULE,
                    "rule is invalid: " + String.join("; ", rule.getValidationReasons()), matchLimit);
        }

        int hardCap = Math.max(matchLimit, 1);
        List<Map<String, String>> raw = enumerate(rule.getLhs(), input, hardCap);
        if (raw == null) {
            return RunResult.terminal(rule.versionRef(), RunResult.LIMIT_REACHED,
                    "enumeration exceeded " + hardCap + " mappings; results intentionally not returned",
                    matchLimit);
        }
        if (raw.isEmpty()) {
            return RunResult.terminal(rule.versionRef(), RunResult.NO_MATCH,
                    "the match subgraph is not a substructure of the input", matchLimit);
        }

        String inputFp = canonicalizer.of(input).fingerprint();
        List<Candidate> built = new ArrayList<>();
        for (Map<String, String> mapping : raw) {
            Product p = rewrite(rule, input, mapping);
            List<Violation> violations = diagnose(input, mapping, p, rule);
            Canonicalizer.Canonical prodCan = canonicalizer.of(p.graph());
            String orbitSig = orbitSignature(mapping, input);
            built.add(new Candidate(0, mapping, p.graph(), p.retained(), p.deleted(), p.added(),
                    p.addedBonds(), p.removedBonds(), p.stereoChanges(),
                    inputFp, prodCan.fingerprint(), orbitSig, 1, violations));
        }

        // Merge symmetry-equivalent embeddings, retaining the number of maps merged.
        Map<String, Candidate> merged = new LinkedHashMap<>();
        for (Candidate c : built) {
            String key = c.orbitSignature() + "||" + c.productFingerprint();
            Candidate existing = merged.get(key);
            if (existing == null) {
                merged.put(key, c);
            } else {
                merged.put(key, withMultiplicity(existing, existing.multiplicity() + 1));
            }
        }

        // Deterministic default order: product fingerprint, then orbit signature.
        // Traversal order of raw matches never influences presentation.
        List<Candidate> ordered = new ArrayList<>(merged.values());
        ordered.sort(Comparator.comparing(Candidate::productFingerprint)
                .thenComparing(Candidate::orbitSignature));

        List<Candidate> indexed = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            indexed.add(withIndex(ordered.get(i), i));
        }
        return new RunResult(rule.versionRef(), RunResult.OK,
                indexed.size() + " canonical candidate(s) from " + raw.size() + " mapping(s)",
                matchLimit, raw.size(), indexed.size(), false, indexed,
                List.copyOf(raw));
    }

    private static Candidate withIndex(Candidate c, int index) {
        return new Candidate(index, c.mapping(), c.product(), c.retainedInputIds(),
                c.deletedInputIds(), c.addedAtomIds(), c.addedBonds(), c.removedBonds(),
                c.stereoChanges(), c.inputFingerprint(), c.productFingerprint(),
                c.orbitSignature(), c.multiplicity(), c.violations());
    }

    private static Candidate withMultiplicity(Candidate c, int mult) {
        return new Candidate(c.index(), c.mapping(), c.product(), c.retainedInputIds(),
                c.deletedInputIds(), c.addedAtomIds(), c.addedBonds(), c.removedBonds(),
                c.stereoChanges(), c.inputFingerprint(), c.productFingerprint(),
                c.orbitSignature(), mult, c.violations());
    }

    /**
     * A signature that is identical for embeddings differing only by permuting
     * symmetry-equivalent input atoms. We map each pattern atom not to a raw
     * input id but to the input atom's canonical orbit rank.
     */
    private String orbitSignature(Map<String, String> mapping, MolGraph input) {
        Canonicalizer.Canonical can = canonicalizer.of(input);
        Map<Integer, Integer> rankPositions = new TreeMap<>();
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(mapping).entrySet()) {
            int rank = can.rankOf().get(e.getValue());
            parts.add(e.getKey() + "=" + rank);
        }
        return String.join(",", parts);
    }

    /** Element conservation, charge difference and uninvolved-structure checks. */
    private List<Violation> diagnose(MolGraph input, Map<String, String> mapping,
                                     Product p, Rule rule) {
        List<Violation> out = new ArrayList<>();

        // 1) Element conservation across the full product (including uninvolved).
        Map<String, Integer> before = input.elementCounts();
        Map<String, Integer> after = p.graph().elementCounts();
        Set<String> elements = new TreeSet<>();
        elements.addAll(before.keySet());
        elements.addAll(after.keySet());
        for (String el : elements) {
            int d = after.getOrDefault(el, 0) - before.getOrDefault(el, 0);
            if (d != 0) {
                out.add(Violation.error("ELEMENT_NOT_CONSERVED",
                        "element " + el + " count changes by " + d));
            }
        }

        // 2) Charge difference.
        int dq = p.graph().totalCharge() - input.totalCharge();
        if (dq != 0) {
            out.add(Violation.error("CHARGE_NOT_CONSERVED",
                    "net charge changes by " + dq + " (" + input.totalCharge()
                            + " -> " + p.graph().totalCharge() + ")"));
        }

        // 3) Uninvolved structure must be carried through byte-for-byte (same
        // atoms, same element/isotope/charge, same bonds).
        Set<String> mappedInput = new LinkedHashSet<>(mapping.values());
        for (Atom a : input.atoms()) {
            if (mappedInput.contains(a.getId())) continue;
            Atom kept = p.graph().atom(a.getId());
            if (kept == null) {
                out.add(Violation.error("UNINVOLVED_ATOM_LOST",
                        "uninvolved atom " + a.getId() + " disappeared"));
            } else if (!kept.equals(a)) {
                out.add(Violation.error("UNINVOLVED_ATOM_MUTATED",
                        "uninvolved atom " + a.getId() + " was modified"));
            }
        }
        int untouchedBondsBefore = 0;
        for (Bond b : input.bonds()) {
            if (!mappedInput.contains(b.getFrom()) && !mappedInput.contains(b.getTo())) {
                untouchedBondsBefore++;
                if (findBond(p.graph(), b.getFrom(), b.getTo(), b.getOrder()) == null) {
                    out.add(Violation.error("UNINVOLVED_BOND_LOST",
                            "uninvolved bond " + b.getFrom() + "-" + b.getTo() + " disappeared"));
                }
            }
        }
        int untouchedBondsAfter = 0;
        for (Bond b : p.graph().bonds()) {
            if (!mappedInput.contains(b.getFrom()) && !mappedInput.contains(b.getTo())) {
                untouchedBondsAfter++;
            }
        }
        if (untouchedBondsAfter != untouchedBondsBefore) {
            out.add(Violation.error("UNINVOLVED_BOND_ADDED",
                    "a bond was introduced between uninvolved atoms"));
        }
        return out;
    }
}
