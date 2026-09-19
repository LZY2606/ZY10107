package chem.molgraph.engine;

import chem.molgraph.domain.Atom;
import chem.molgraph.domain.Bond;
import chem.molgraph.domain.Issue;
import chem.molgraph.domain.Molecule;
import chem.molgraph.domain.RuleDef;
import chem.molgraph.domain.Stereo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RuleValidator {

    private RuleValidator() {
    }

    public static List<Issue> validateMolecule(Molecule mol) {
        List<Issue> issues = new ArrayList<>();
        if (mol == null) {
            issues.add(new Issue("MOLECULE_MISSING", "结构缺失。"));
            return issues;
        }
        Set<String> ids = new HashSet<>();
        for (Atom a : mol.atoms()) {
            if (a.id() == null || a.id().isBlank()) {
                issues.add(new Issue("ATOM_ID_BLANK", "存在没有编号的原子。"));
            } else if (!ids.add(a.id())) {
                issues.add(new Issue("DUPLICATE_ATOM_ID", "原子编号重复: " + a.id()));
            }
            if (!Elements.valid(a.element())) {
                issues.add(new Issue("UNKNOWN_ELEMENT", "未知元素符号: " + a.element() + "（原子 " + a.id() + "）"));
            }
            if (a.isotope() < 0) {
                issues.add(new Issue("BAD_ISOTOPE", "同位素质量数不能为负: " + a.id()));
            }
            if (a.stereo() == null) {
                issues.add(new Issue("BAD_STEREO", "立体字段为 null: " + a.id()));
            }
            if (a.stereo() != Stereo.NONE && (a.stereoOrder() == null || a.stereoOrder().size() < 3)) {
                issues.add(new Issue("BAD_TETRAHEDRAL_ORDER", "四面体立体原子至少需要 3 个有序配体: " + a.id()));
            }
        }
        Set<String> edgeKeys = new HashSet<>();
        for (Bond b : mol.bonds()) {
            String key = edgeKey(b);
            if (!edgeKeys.add(key)) {
                issues.add(new Issue("DUPLICATE_BOND", "重复键: " + b.a() + " - " + b.b()));
            }
            if (!ids.contains(b.a()) || !ids.contains(b.b())) {
                issues.add(new Issue("DANGLING_BOND", "键引用了不存在的原子: " + b.a() + " - " + b.b()));
            }
            if (b.order() < 1 || b.order() > 3) {
                issues.add(new Issue("BAD_BOND_ORDER", "键阶只允许 1..3: " + b.a() + " - " + b.b()));
            }
        }
        for (Atom a : mol.atoms()) {
            if (a.stereoOrder() != null) {
                for (String n : a.stereoOrder()) {
                    if (!ids.contains(n)) {
                        issues.add(new Issue("DANGLING_STEREO_REF", "立体顺序引用了不存在的原子: " + a.id() + " -> " + n));
                    }
                }
            }
        }
        return issues;
    }

    public static List<Issue> validateRule(RuleDef rule) {
        List<Issue> issues = new ArrayList<>();
        if (rule == null) {
            issues.add(new Issue("RULE_MISSING", "规则缺失。"));
            return issues;
        }
        if (rule.name() == null || rule.name().isBlank()) {
            issues.add(new Issue("RULE_NAME_BLANK", "规则名称不能为空。"));
        }
        if (rule.version() == null || rule.version().isBlank()) {
            issues.add(new Issue("RULE_VERSION_BLANK", "规则版本不能为空。"));
        }
        Molecule pattern = rule.pattern();
        if (pattern == null || pattern.atoms().isEmpty()) {
            issues.add(new Issue("PATTERN_EMPTY", "匹配子图为空。"));
            return issues;
        }
        issues.addAll(validateMolecule(pattern));

        Set<String> pids = new HashSet<>();
        for (Atom a : pattern.atoms()) {
            pids.add(a.id());
            if (a.originTag() != null && a.originTag().isBlank()) {
                issues.add(new Issue("BAD_ORIGIN_TAG", "来源标记为空字符串。"));
            }
        }
        if (!isConnected(pattern)) {
            issues.add(new Issue("PATTERN_DISCONNECTED", "匹配子图必须连通。"));
        }

        Set<String> delete = new HashSet<>(safe(rule.deleteAtoms()));
        for (String id : delete) {
            if (!pids.contains(id)) {
                issues.add(new Issue("DELETE_UNKNOWN_ATOM", "deleteAtoms 引用了子图外原子: " + id));
            }
        }
        Set<String> addIds = new HashSet<>();
        for (Atom a : safe(rule.addAtoms())) {
            addIds.add(a.id());
        }
        Set<String> replacementIds = new HashSet<>();
        for (String id : delete) {
            if (addIds.contains(id)) {
                replacementIds.add(id);
            }
        }
        Set<String> kept = new HashSet<>(pids);
        kept.removeAll(delete);

        Set<String> newIds = new HashSet<>();
        for (Atom a : safe(rule.addAtoms())) {
            if (!Elements.valid(a.element())) {
                issues.add(new Issue("UNKNOWN_ELEMENT", "新原子元素未知: " + a.id()));
            }
            if (!newIds.add(a.id())) {
                issues.add(new Issue("DUPLICATE_ADD_ATOM_ID", "addAtoms 中编号重复: " + a.id()));
            }
            if (pids.contains(a.id()) && delete.contains(a.id())) {
                // addAtoms 中与 deleteAtoms 同 ID = 替换原子（合法的重写原语）
            }
        }
        for (Atom a : safe(rule.addAtoms())) {
            if (a.stereoOrder() != null) {
                for (String n : a.stereoOrder()) {
                    boolean known = kept.contains(n) || newIds.contains(n) || replacementIds.contains(n);
                    if (!known) {
                        issues.add(new Issue("ADD_ATOM_STEREO_BAD_REF", "新原子立体顺序引用未知原子: " + a.id() + " -> " + n));
                    }
                }
            }
        }
        if (kept.isEmpty() && newIds.isEmpty()) {
            issues.add(new Issue("REWRITE_EMPTY", "重写结果中没有任何原子。"));
        }

        Set<String> bondEdges = new HashSet<>();
        for (Bond b : pattern.bonds()) {
            bondEdges.add(edgeKey(b));
        }
        Set<String> deleteBonds = new HashSet<>();
        for (String ref : safe(rule.deleteBonds())) {
            String[] parts = ref.split("\\|");
            if (parts.length != 2 || !pids.contains(parts[0]) || !pids.contains(parts[1])) {
                issues.add(new Issue("DELETE_UNKNOWN_BOND", "deleteBonds 必须是 a|b 形式且端点在子图内: " + ref));
                continue;
            }
            deleteBonds.add(edgeKey(parts[0], parts[1]));
        }
        Set<String> addEdges = new HashSet<>();
        for (Bond b : safe(rule.addBonds())) {
            if (!addEdges.add(edgeKey(b))) {
                issues.add(new Issue("DUPLICATE_ADD_BOND", "addBonds 中重复: " + b.a() + " - " + b.b()));
            }
            boolean aKept = kept.contains(b.a()) || newIds.contains(b.a()) || replacementIds.contains(b.a());
            boolean bKept = kept.contains(b.b()) || newIds.contains(b.b()) || replacementIds.contains(b.b());
            if (!aKept || !bKept) {
                issues.add(new Issue("ADD_BOND_BAD_ENDPOINT", "addBonds 端点必须保留或新增: " + b.a() + " - " + b.b()));
            }
            boolean touchesReplacement = replacementIds.contains(b.a()) || replacementIds.contains(b.b());
            if (bondEdges.contains(edgeKey(b)) && !deleteBonds.contains(edgeKey(b)) && !touchesReplacement) {
                issues.add(new Issue("ADD_EXISTING_BOND", "键在子图中已存在且未被删除: " + b.a() + " - " + b.b()));
            }
            if (b.order() < 1 || b.order() > 3) {
                issues.add(new Issue("BAD_BOND_ORDER", "新增键阶只允许 1..3: " + b.a() + " - " + b.b()));
            }
        }
        return issues;
    }

    public static boolean isConnected(Molecule mol) {
        if (mol.atoms().isEmpty()) {
            return true;
        }
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (Atom a : mol.atoms()) {
            adj.put(a.id(), new ArrayList<>());
        }
        for (Bond b : mol.bonds()) {
            if (adj.containsKey(b.a()) && adj.containsKey(b.b())) {
                adj.get(b.a()).add(b.b());
                adj.get(b.b()).add(b.a());
            }
        }
        Set<String> seen = new HashSet<>();
        List<String> stack = new ArrayList<>();
        stack.add(mol.atoms().get(0).id());
        while (!stack.isEmpty()) {
            String cur = stack.remove(stack.size() - 1);
            if (!seen.add(cur)) {
                continue;
            }
            stack.addAll(adj.get(cur));
        }
        return seen.size() == mol.atoms().size();
    }

    static String edgeKey(Bond b) {
        return edgeKey(b.a(), b.b());
    }

    static String edgeKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
