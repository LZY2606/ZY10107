package chem.molgraph.engine;

import chem.molgraph.domain.Atom;
import chem.molgraph.domain.CandidateView;
import chem.molgraph.domain.Issue;
import chem.molgraph.domain.Molecule;
import chem.molgraph.domain.RuleDef;
import chem.molgraph.domain.TransformResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class TransformService {

    private final int searchLimit;

    public TransformService(@Value("${molgraph.search.limit:1024}") int searchLimit) {
        this.searchLimit = searchLimit;
    }

    public int searchLimit() {
        return searchLimit;
    }

    public TransformResult transform(RuleDef rule, Molecule input) {
        List<Issue> inputIssues = RuleValidator.validateMolecule(input);
        if (!inputIssues.isEmpty()) {
            return new TransformResult(TransformResult.INVALID_INPUT,
                    rule == null ? null : rule.name(),
                    rule == null ? null : rule.version(),
                    rule == null ? null : ruleFingerprint(rule),
                    List.of(), inputIssues, 0, searchLimit, false,
                    input == null ? null : Canonical.canonicalize(input).fingerprint());
        }
        List<Issue> ruleIssues = RuleValidator.validateRule(rule);
        String ruleFp = ruleFingerprint(rule);
        if (!ruleIssues.isEmpty()) {
            return new TransformResult(TransformResult.INVALID_RULE, rule.name(), rule.version(),
                    ruleFp, List.of(), ruleIssues, 0, searchLimit, false,
                    Canonical.canonicalize(input).fingerprint());
        }

        SubgraphMatcher.Enumeration en = SubgraphMatcher.enumerate(rule.pattern(), input, searchLimit);
        int enumerated = en.mappings.size();
        if (en.mappings.isEmpty()) {
            return new TransformResult(TransformResult.NO_MATCH, rule.name(), rule.version(), ruleFp,
                    List.of(), List.of(new Issue("NO_MATCH", "匹配子图在输入结构中未找到任何嵌入。")),
                    0, searchLimit, false, Canonical.canonicalize(input).fingerprint());
        }

        List<Group> groups = new ArrayList<>();
        for (Map<String, String> mapping : en.mappings) {
            Rewriter.Product product = Rewriter.apply(rule, input, mapping);
            Canonical.Result canon = Canonical.canonicalize(product.molecule);
            Group existing = null;
            for (Group g : groups) {
                if (GraphIsomorphism.isomorphic(g.productMolecule, product.molecule)) {
                    existing = g;
                    break;
                }
            }
            if (existing == null) {
                Group g = new Group(product.molecule, canon.canonical(), product);
                g.mappings.add(mapping);
                groups.add(g);
            } else {
                existing.mappings.add(mapping);
            }
        }

        List<Group> sorted = new ArrayList<>(groups);
        sorted.sort((x, y) -> x.canonical.compareTo(y.canonical));

        List<CandidateView> candidates = new ArrayList<>();
        for (Group g : sorted) {
            Map<String, String> rep = new TreeMap<>(g.getRepresentative());
            List<Issue> issues = g.template.issues;
            candidates.add(new CandidateView(
                    g.fingerprint, g.canonical, g.mappings.size(), rep, g.productMolecule,
                    List.copyOf(g.template.keptAtoms),
                    List.copyOf(g.template.deletedAtoms),
                    List.copyOf(g.template.addedAtoms),
                    List.copyOf(g.template.stereoChangedAtoms),
                    List.copyOf(g.template.keptBonds),
                    List.copyOf(g.template.deletedBonds),
                    List.copyOf(g.template.addedBonds),
                    Map.copyOf(g.template.elementDelta),
                    g.template.chargeDelta,
                    List.copyOf(issues),
                    List.copyOf(g.template.unparticipatedAtoms)));
        }

        String status = en.limitReached ? TransformResult.LIMIT_REACHED : TransformResult.OK;
        List<Issue> statusIssues = en.limitReached
                ? List.of(new Issue("LIMIT_REACHED",
                "嵌入枚举达到上限 " + searchLimit + "，仅归并已枚举映射；候选集合可能不完整。"))
                : List.of();
        return new TransformResult(status, rule.name(), rule.version(), ruleFp, candidates,
                statusIssues, enumerated, searchLimit, en.limitReached,
                Canonical.canonicalize(input).fingerprint());
    }

    public String ruleFingerprint(RuleDef rule) {
        StringBuilder sb = new StringBuilder();
        sb.append("RULE|").append(rule.name()).append('@').append(rule.version());
        if (rule.pattern() != null) {
            sb.append('|').append(Canonical.canonicalize(rule.pattern()).canonical());
        }
        List<String> parts = new ArrayList<>();
        append(parts, "delA", rule.deleteAtoms());
        if (rule.addAtoms() != null) {
            for (Atom a : rule.addAtoms()) {
                parts.add("addA:" + a.id() + "=" + a.element() + "#" + a.isotope() + "^" + a.charge()
                        + ":" + a.stereo() + ":" + (a.stereoOrder() == null ? "" : String.join(">", a.stereoOrder()))
                        + ":" + (a.originTag() == null ? "" : a.originTag()));
            }
        }
        append(parts, "delB", rule.deleteBonds());
        if (rule.addBonds() != null) {
            for (var b : rule.addBonds()) {
                parts.add("addB:" + b.a() + "=" + b.order() + "=" + b.b());
            }
        }
        parts.sort(String::compareTo);
        sb.append('|').append(String.join(";", parts));
        return Canonical.sha256(sb.toString());
    }

    private static void append(List<String> parts, String prefix, List<String> values) {
        if (values == null) {
            return;
        }
        for (String v : values) {
            parts.add(prefix + ":" + v);
        }
    }

    private static final class Group {
        final Molecule productMolecule;
        final String fingerprint;
        final String canonical;
        final Rewriter.Product template;
        final List<Map<String, String>> mappings = new ArrayList<>();

        Group(Molecule productMolecule, String canonical, Rewriter.Product template) {
            this.productMolecule = productMolecule;
            this.canonical = canonical;
            this.fingerprint = Canonical.canonicalize(productMolecule).fingerprint();
            this.template = template;
        }

        Map<String, String> getRepresentative() {
            return mappings.get(0);
        }
    }
}
