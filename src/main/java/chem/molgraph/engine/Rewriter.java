package chem.molgraph.engine;

import chem.molgraph.domain.Atom;
import chem.molgraph.domain.Bond;
import chem.molgraph.domain.BondStereo;
import chem.molgraph.domain.CandidateView;
import chem.molgraph.domain.Issue;
import chem.molgraph.domain.Molecule;
import chem.molgraph.domain.RuleDef;
import chem.molgraph.domain.Stereo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Applies one verified embedding of a rule to an input molecule. */
public final class Rewriter {

    private Rewriter() {
    }

    public static class Product {
        public Molecule molecule;
        public List<String> keptAtoms = new ArrayList<>();
        public List<String> deletedAtoms = new ArrayList<>();
        public List<String> addedAtoms = new ArrayList<>();
        public List<String> stereoChangedAtoms = new ArrayList<>();
        public List<String> keptBonds = new ArrayList<>();
        public List<String> deletedBonds = new ArrayList<>();
        public List<String> addedBonds = new ArrayList<>();
        public List<String> unparticipatedAtoms = new ArrayList<>();
        public final List<Issue> issues = new ArrayList<>();
        public Map<String, Integer> elementDelta = new TreeMap<>();
        public int chargeDelta;
    }

    public static Product apply(RuleDef rule, Molecule input, Map<String, String> embedding) {
        Product out = new Product();
        Map<String, Atom> inputAtoms = input.atomMap();
        Map<String, Atom> patternAtoms = rule.pattern().atomMap();

        Set<String> deletedPattern = new HashSet<>(ns(rule.deleteAtoms()));
        Map<String, Atom> overrides = new HashMap<>();
        for (Atom a : ns(rule.addAtoms())) {
            overrides.put(a.id(), a);
        }
        Set<String> patternIds = patternAtoms.keySet();

        Set<String> mappedInput = new LinkedHashSet<>(embedding.values());
        Set<String> deletedInput = new LinkedHashSet<>();
        Map<String, Atom> keptOverrideByInput = new HashMap<>();
        for (Map.Entry<String, String> e : embedding.entrySet()) {
            if (deletedPattern.contains(e.getKey())) {
                deletedInput.add(e.getValue());
            } else if (overrides.containsKey(e.getKey())) {
                keptOverrideByInput.put(e.getValue(), overrides.get(e.getKey()));
            }
        }

        Map<String, String> productIdOfNew = new LinkedHashMap<>();
        Map<String, Atom> replacementByPatternId = new LinkedHashMap<>();
        Set<String> overridePatternIds = new HashSet<>();
        for (Atom a : ns(rule.addAtoms())) {
            if (deletedPattern.contains(a.id())) {
                // addAtoms 条目与 deleteAtoms 同 ID = 替换原子
                replacementByPatternId.put(a.id(), a);
            } else if (!patternIds.contains(a.id())) {
                productIdOfNew.put(a.id(), "new:" + rule.name() + "@" + rule.version() + ":" + a.id());
            } else {
                overridePatternIds.add(a.id());
            }
        }
        for (Map.Entry<String, Atom> entry : replacementByPatternId.entrySet()) {
            productIdOfNew.put(entry.getKey(),
                    "new:" + rule.name() + "@" + rule.version() + ":" + entry.getKey());
        }

        List<Atom> productAtoms = new ArrayList<>();
        for (Atom inputAtom : input.atoms()) {
            if (deletedInput.contains(inputAtom.id())) {
                out.deletedAtoms.add(inputAtom.id());
                continue;
            }
            if (mappedInput.contains(inputAtom.id())) {
                Atom override = keptOverrideByInput.get(inputAtom.id());
                if (override != null) {
                    List<String> resolvedOrder = resolveSlots(override.stereoOrder(), embedding, productIdOfNew);
                    Stereo stereo = override.stereo() == null ? Stereo.NONE : override.stereo();
                    if (stereo != Stereo.NONE && override.stereoOrder() != null) {
                        Stereo derived = deriveOverrideStereo(rule, input, embedding, override, resolvedOrder, out.issues);
                        stereo = derived != null ? derived : Stereo.NONE;
                    }
                    Atom built = new Atom(inputAtom.id(), override.element(), override.isotope(),
                            override.charge(), stereo, resolvedOrder.isEmpty() ? null : resolvedOrder,
                            inputAtom.originTag(), inputAtom.x(), inputAtom.y());
                    productAtoms.add(built);
                    out.keptAtoms.add(inputAtom.id());
                } else {
                    Atom copy = inputAtom;
                    if (hasBrokenStereoSlots(inputAtom, deletedInput)) {
                        copy = new Atom(copy.id(), copy.element(), copy.isotope(), copy.charge(),
                                Stereo.NONE, null, copy.originTag(), copy.x(), copy.y());
                        out.issues.add(new Issue("STEREO_UNRESOLVED",
                                "保留的立体中心 " + copy.id() + " 的配体被删除，且规则未重新定义立体方向。"));
                    }
                    productAtoms.add(copy);
                    out.keptAtoms.add(inputAtom.id());
                }
            } else {
                productAtoms.add(inputAtom);
                out.unparticipatedAtoms.add(inputAtom.id());
            }
        }
        for (Atom a : ns(rule.addAtoms())) {
            if (!patternIds.contains(a.id())) {
                String pid = productIdOfNew.get(a.id());
                String tag = a.originTag() != null ? a.originTag() : rule.name() + "@" + rule.version();
                List<String> order = resolveSlots(a.stereoOrder(), embedding, productIdOfNew);
                productAtoms.add(new Atom(pid, a.element(), a.isotope(), a.charge(),
                        a.stereo() == null ? Stereo.NONE : a.stereo(),
                        order.isEmpty() ? null : order, tag, null, null));
                out.addedAtoms.add(pid);
            }
        }
        for (Map.Entry<String, Atom> entry : replacementByPatternId.entrySet()) {
            Atom a = entry.getValue();
            String pid = productIdOfNew.get(entry.getKey());
            String tag = a.originTag() != null ? a.originTag() : rule.name() + "@" + rule.version();
            List<String> order = resolveSlots(a.stereoOrder(), embedding, productIdOfNew);
            productAtoms.add(new Atom(pid, a.element(), a.isotope(), a.charge(),
                    a.stereo() == null ? Stereo.NONE : a.stereo(),
                    order.isEmpty() ? null : order, tag, null, null));
            out.addedAtoms.add(pid);
        }

        Set<String> productAtomIds = new LinkedHashSet<>();
        for (Atom a : productAtoms) {
            productAtomIds.add(a.id());
        }

        Set<String> deleteBondKeys = new HashSet<>();
        for (String ref : ns(rule.deleteBonds())) {
            String[] parts = ref.split("\\|");
            String u = embedding.get(parts[0]);
            String v = embedding.get(parts[1]);
            deleteBondKeys.add(edgeKey(u, v));
        }

        List<Bond> productBonds = new ArrayList<>();
        for (Bond b : input.bonds()) {
            boolean aGone = !productAtomIds.contains(b.a());
            boolean bGone = !productAtomIds.contains(b.b());
            String key = edgeKey(b.a(), b.b());
            if (aGone || bGone) {
                String survivor = aGone ? (bGone ? null : b.b()) : b.a();
                String removed = aGone ? b.a() : b.b();
                boolean removedIsMapped = mappedInput.contains(removed);
                boolean survivorMapped = survivor != null && mappedInput.contains(survivor);
                if (removedIsMapped && survivorMapped) {
                    out.deletedBonds.add(bondLabel(b));
                } else if (survivor != null && !survivorMapped) {
                    out.issues.add(new Issue("DANGLING_EXTERNAL_BOND",
                            "未参与匹配的原子 " + survivor + " 与被删除原子 " + removed + " 之间的键被丢弃。"));
                    out.deletedBonds.add(bondLabel(b));
                } else {
                    out.deletedBonds.add(bondLabel(b));
                }
                continue;
            }
            if (deleteBondKeys.contains(key)) {
                out.deletedBonds.add(bondLabel(b));
                continue;
            }
            productBonds.add(b);
            out.keptBonds.add(bondLabel(b));
        }

        Set<String> productBondKeys = new HashSet<>();
        for (Bond b : productBonds) {
            productBondKeys.add(edgeKey(b.a(), b.b()));
        }
        for (Bond b : ns(rule.addBonds())) {
            String u = resolveEndpoint(b.a(), embedding, productIdOfNew);
            String v = resolveEndpoint(b.b(), embedding, productIdOfNew);
            String key = edgeKey(u, v);
            if (u == null || v == null || !productAtomIds.contains(u) || !productAtomIds.contains(v)) {
                out.issues.add(new Issue("ADD_BOND_BAD_ENDPOINT", "新增键端点无法解析: " + b.a() + " - " + b.b()));
                continue;
            }
            if (productBondKeys.add(key)) {
                Bond built = new Bond(u, v, b.order(), b.stereo() == null ? BondStereo.NONE : b.stereo());
                productBonds.add(built);
                out.addedBonds.add(bondLabel(built));
            }
        }

        out.molecule = new Molecule(productAtoms, productBonds);

        computeStereoChanges(input, out);
        computeDeltas(input, out);
        return out;
    }

    /**
     * Stereochemistry for a kept tetrahedral center after rewriting.
     *
     * Rule convention:
     *  - pattern center stereoOrder lists the input ligand slots;
     *  - override (addAtom with the same center id) stereoOrder lists the output slots
     *    position by position (a deleted pattern id names the replacement atom);
     *  - the override stereo sign is the rule intent (UP = retention of the center,
     *    DOWN = inversion).
     *
     * Result descriptor relative to the output frame:
     *   chi_out = sign(override) * chi_in(input center) * (-1)^P
     * where P is the pattern-level slot permutation. This stays independent of the
     * concrete embedding, so enantiomeric inputs yield enantiomeric outputs.
     */
    private static Stereo deriveOverrideStereo(RuleDef rule, Molecule input, Map<String, String> embedding,
                                               Atom override, List<String> slotsOut, List<Issue> issues) {
        Atom patternCenter = rule.pattern().atomMap().get(override.id());
        if (patternCenter.stereo() == Stereo.NONE || patternCenter.stereoOrder() == null) {
            return override.stereo();
        }
        if (override.stereoOrder() == null
                || override.stereoOrder().size() != patternCenter.stereoOrder().size()) {
            issues.add(new Issue("STEREO_SLOT_MISMATCH",
                    "立体中心 " + override.id() + " 的配体位数与匹配子图不一致。"));
            return Stereo.NONE;
        }
        int patternParity = StereoMath.permutationParity(override.stereoOrder(), patternCenter.stereoOrder());
        String inputCenterId = embedding.get(override.id());
        Atom inputCenter = input.atomMap().get(inputCenterId);
        if (inputCenter.stereo() == Stereo.NONE) {
            issues.add(new Issue("STEREO_ON_ACHIRAL_INPUT",
                    "规则要求保留/翻转立体，但输入中心 " + inputCenterId + " 没有四面体立体信息。"));
            return Stereo.NONE;
        }
        List<String> inputSlots = new ArrayList<>();
        for (String ref : patternCenter.stereoOrder()) {
            String resolved = embedding.get(ref);
            if (resolved == null) {
                issues.add(new Issue("STEREO_SLOT_MISMATCH", "立体槽位无法映射: " + ref));
                return Stereo.NONE;
            }
            inputSlots.add(resolved);
        }
        int chiIn = StereoMath.descriptor(inputCenter.stereo(), inputCenter.stereoOrder(), inputSlots);
        int ruleSign = StereoMath.sign(override.stereo());
        int descriptor = ruleSign * chiIn * (patternParity == 0 ? 1 : -1);
        return descriptor > 0 ? Stereo.UP : Stereo.DOWN;
    }

    private static void computeStereoChanges(Molecule input, Product out) {
        Map<String, Atom> oldAtoms = input.atomMap();
        Map<String, Atom> newAtoms = out.molecule.atomMap();
        for (String id : out.keptAtoms) {
            Atom before = oldAtoms.get(id);
            Atom after = newAtoms.get(id);
            if (before == null || after == null) {
                continue;
            }
            if (before.stereo() == Stereo.NONE && after.stereo() == Stereo.NONE) {
                continue;
            }
            if (!before.stereo().equals(after.stereo())) {
                out.stereoChangedAtoms.add(id);
                continue;
            }
            List<String> oldNeighbors = sortedNeighbors(input, id);
            List<String> newNeighbors = sortedNeighbors(out.molecule, id);
            if (!oldNeighbors.equals(newNeighbors)) {
                out.stereoChangedAtoms.add(id);
                continue;
            }
            int d1 = StereoMath.descriptor(before.stereo(), before.stereoOrder(), oldNeighbors);
            int d2 = StereoMath.descriptor(after.stereo(), after.stereoOrder(), newNeighbors);
            if (d1 != d2) {
                out.stereoChangedAtoms.add(id);
            }
        }
    }

    private static List<String> sortedNeighbors(Molecule mol, String atomId) {
        List<String> ids = new ArrayList<>();
        for (Bond b : mol.bondsOf(atomId)) {
            ids.add(mol.other(b, atomId));
        }
        ids.sort(String::compareTo);
        return ids;
    }

    private static boolean hasBrokenStereoSlots(Atom atom, Set<String> deletedInput) {
        if (atom.stereo() == Stereo.NONE || atom.stereoOrder() == null) {
            return false;
        }
        for (String n : atom.stereoOrder()) {
            if (deletedInput.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static void computeDeltas(Molecule input, Product out) {
        Map<String, Integer> before = elementCounts(input);
        Map<String, Integer> after = elementCounts(out.molecule);
        Set<String> elements = new LinkedHashSet<>();
        elements.addAll(before.keySet());
        elements.addAll(after.keySet());
        for (String e : elements) {
            int delta = after.getOrDefault(e, 0) - before.getOrDefault(e, 0);
            if (delta != 0) {
                out.elementDelta.put(e, delta);
            }
        }
        int chargeBefore = input.atoms().stream().mapToInt(Atom::charge).sum();
        int chargeAfter = out.molecule.atoms().stream().mapToInt(Atom::charge).sum();
        out.chargeDelta = chargeAfter - chargeBefore;
    }

    private static Map<String, Integer> elementCounts(Molecule mol) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Atom a : mol.atoms()) {
            counts.merge(a.element(), 1, Integer::sum);
        }
        return counts;
    }

    private static List<String> resolveSlots(List<String> refs, Map<String, String> embedding,
                                             Map<String, String> productIdOfNew) {
        List<String> out = new ArrayList<>();
        if (refs == null) {
            return out;
        }
        for (String ref : refs) {
            String resolved = resolveEndpoint(ref, embedding, productIdOfNew);
            if (resolved != null) {
                out.add(resolved);
            }
        }
        return out;
    }

    private static String resolveEndpoint(String ref, Map<String, String> embedding,
                                          Map<String, String> productIdOfNew) {
        String created = productIdOfNew.get(ref);
        if (created != null) {
            return created;
        }
        return embedding.get(ref);
    }

    private static String edgeKey(String a, String b) {
        if (a == null || b == null) {
            return "null|null";
        }
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    private static String bondLabel(Bond b) {
        return b.a() + "=" + b.order() + "=" + b.b();
    }

    private static <T> List<T> ns(List<T> list) {
        return list == null ? List.of() : list;
    }
}
