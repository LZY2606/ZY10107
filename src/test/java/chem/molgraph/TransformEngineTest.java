package chem.molgraph;

import chem.molgraph.domain.*;
import chem.molgraph.engine.RuleValidator;
import chem.molgraph.engine.TransformService;
import chem.molgraph.seed.SampleData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TransformEngineTest {

    private final TransformService service = new TransformService(64);

    @Test
    void ketoEnolOnAcetoneProducesOneGroupedCandidateWithSymmetryCount() {
        TransformResult result = service.transform(SampleData.ketoEnolV1(), SampleData.acetone());
        assertEquals(TransformResult.OK, result.status());
        assertEquals(1, result.candidates().size(), "两个对称甲基上的映射应归并为同一候选");
        CandidateView candidate = result.candidates().get(0);
        assertEquals(2, candidate.mappingCount(), "保留对称等价映射数量");
        assertTrue(candidate.keptAtoms().contains("C0"));
        assertTrue(candidate.addedAtoms().isEmpty(), "H 迁移而不是新增原子");
        assertTrue(candidate.deletedBonds().stream().anyMatch(b -> b.contains("HL") || b.contains("HR")));
        assertTrue(candidate.addedBonds().stream().anyMatch(b -> b.contains("O0")));
        assertTrue(candidate.stereoChangedAtoms().isEmpty());
        assertTrue(candidate.elementDelta().isEmpty(), "元素守恒");
        assertEquals(0, candidate.chargeDelta());
    }

    @Test
    void noMatchIsDistinctStatus() {
        TransformResult result = service.transform(SampleData.ketoEnolV1(), SampleData.ethanol());
        assertEquals(TransformResult.NO_MATCH, result.status());
        assertTrue(result.candidates().isEmpty());
        assertEquals("NO_MATCH", result.issues().get(0).code());
    }

    @Test
    void invalidRuleIsDistinctStatus() {
        TransformResult result = service.transform(SampleData.brokenRuleV1(), SampleData.acetone());
        assertEquals(TransformResult.INVALID_RULE, result.status());
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("UNKNOWN_ELEMENT")));
    }

    @Test
    void invalidInputIsDistinctStatus() {
        Molecule broken = new Molecule(List.of(
                new Atom("a", "Xx", 0, 0, Stereo.NONE, null, null, null, null)), List.of());
        TransformResult result = service.transform(SampleData.ketoEnolV1(), broken);
        assertEquals(TransformResult.INVALID_INPUT, result.status());
    }

    @Test
    void ruleValidatorRejectsDisconnectedAndUnknownRefs() {
        Molecule pattern = new Molecule(List.of(
                new Atom("a", "C", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("b", "C", 0, 0, Stereo.NONE, null, null, null, null)), List.of());
        RuleDef rule = new RuleDef("R", "1", pattern, List.of("zzz"), List.of(), List.of(), List.of(), null);
        List<Issue> issues = RuleValidator.validateRule(rule);
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("PATTERN_DISCONNECTED")));
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("DELETE_UNKNOWN_ATOM")));
    }

    @Test
    void searchLimitIsItsOwnStatusAndKeepsPartialCandidates() {
        TransformService limited = new TransformService(1);
        TransformResult result = limited.transform(SampleData.ketoEnolV1(), SampleData.acetone());
        assertEquals(TransformResult.LIMIT_REACHED, result.status());
        assertTrue(result.limitReached());
        assertEquals(1, result.enumeratedMappings());
        assertFalse(result.candidates().isEmpty());
    }

    @Test
    void sn2RuleInvertsTetrahedralCenter() {
        TransformResult result = service.transform(SampleData.sn2InversionV1(), SampleData.chiralChloride());
        assertEquals(TransformResult.OK, result.status());
        CandidateView candidate = result.candidates().get(0);
        assertTrue(candidate.stereoChangedAtoms().contains("C*"), "四面体中心应被标记为立体变化");
        Atom before = SampleData.chiralChloride().atomMap().get("C*");
        Atom after = candidate.product().atomMap().get("C*");
        int beforeDescriptor = chem.molgraph.engine.StereoMath.descriptor(
                before.stereo(), before.stereoOrder(), before.stereoOrder());
        int afterDescriptor = chem.molgraph.engine.StereoMath.descriptor(
                after.stereo(), after.stereoOrder(), after.stereoOrder());
        assertEquals(-beforeDescriptor, afterDescriptor, "翻转规则应改变手性描述符");
        assertTrue(candidate.addedAtoms().stream().anyMatch(id -> id.endsWith(":lg")), "离去基位置由新原子替换");
        assertTrue(candidate.deletedAtoms().contains("Cl"));
        assertEquals(1, candidate.elementDelta().get("O"));
        assertEquals(-1, candidate.elementDelta().get("Cl"));
        assertEquals(-1, candidate.chargeDelta(), "O- 取代中性 Cl，电荷差 -1");
        assertTrue(candidate.issues().isEmpty());
    }

    @Test
    void fingerprintStableAcrossIterationOrder() {
        TransformResult r1 = service.transform(SampleData.ketoEnolV1(), SampleData.acetone());
        TransformResult r2 = service.transform(SampleData.ketoEnolV1(), SampleData.acetone());
        assertEquals(r1.candidates().get(0).fingerprint(), r2.candidates().get(0).fingerprint());
    }

    @Test
    void multipleCandidatesSortedByCanonicalNotEnumeration() {
        // 两个化学上不等价的羰基亚甲基位点时，默认候选永远是规范序最小者
        // O=C(-CH2? )...: 羰基碳 cc 连一个普通 O= 双键不可能两边都 C=O;
        // 构造一个分子含两个羰基亚甲基位点：O=C-C... 和 13C 羰基区别
        Molecule molecule = new Molecule(List.of(
                new Atom("oc1", "C", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("o1", "O", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("ac1", "C", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("h1", "H", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("oc2", "C", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("o2", "O", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("ac2", "C", 13, 0, Stereo.NONE, null, null, null, null),
                new Atom("h2", "H", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("bridge", "C", 0, 0, Stereo.NONE, null, null, null, null)),
                List.of(new Bond("oc1", "o1", 2, BondStereo.NONE),
                        new Bond("oc1", "ac1", 1, BondStereo.NONE),
                        new Bond("ac1", "h1", 1, BondStereo.NONE),
                        new Bond("oc2", "o2", 2, BondStereo.NONE),
                        new Bond("oc2", "ac2", 1, BondStereo.NONE),
                        new Bond("ac2", "h2", 1, BondStereo.NONE),
                        new Bond("oc1", "bridge", 1, BondStereo.NONE),
                        new Bond("oc2", "bridge", 1, BondStereo.NONE)));
        TransformResult result = service.transform(SampleData.ketoEnolV1(), molecule);
        assertEquals(2, result.candidates().size());
        assertTrue(result.candidates().get(0).canonical().compareTo(result.candidates().get(1).canonical()) < 0);
    }

    @Test
    void danglingExternalBondIsReportedNotSilent() {
        // 规则删除匹配原子 h；未参与的 He 原本只挂在 h 上，键必须被丢弃并给出诊断。
        Molecule pattern = new Molecule(List.of(
                new Atom("c", "C", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("h", "H", 0, 0, Stereo.NONE, null, null, null, null)),
                List.of(new Bond("c", "h", 1, BondStereo.NONE)));
        RuleDef deleteRule = new RuleDef("DEL_H", "1.0", pattern,
                List.of("h"), List.of(), List.of(), List.of(), null);
        Molecule molecule = new Molecule(List.of(
                new Atom("c", "C", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("h", "H", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("x", "He", 0, 0, Stereo.NONE, null, null, null, null)),
                List.of(new Bond("c", "h", 1, BondStereo.NONE),
                        new Bond("h", "x", 1, BondStereo.NONE)));
        TransformResult result = service.transform(deleteRule, molecule);
        CandidateView candidate = result.candidates().get(0);
        assertTrue(candidate.issues().stream().anyMatch(i -> i.code().equals("DANGLING_EXTERNAL_BOND")));
        assertTrue(candidate.unparticipatedAtoms().contains("x"));
        assertTrue(candidate.product().atoms().stream().noneMatch(a -> a.id().equals("h")));
    }

    @Test
    void chargeAndIsotopeAreEnforced() {
        RuleDef rule = SampleData.ketoEnolV2();
        TransformResult onUnlabeled = service.transform(rule, SampleData.acetone());
        assertEquals(TransformResult.NO_MATCH, onUnlabeled.status());
        Molecule labeled = SampleData.acetone();
        List<Atom> relabeled = labeled.atoms().stream()
                .map(a -> a.id().equals("O0")
                        ? new Atom(a.id(), a.element(), 18, a.charge(), a.stereo(), a.stereoOrder(), a.originTag(), a.x(), a.y())
                        : a).toList();
        TransformResult onLabeled = service.transform(rule, new Molecule(relabeled, labeled.bonds()));
        assertEquals(TransformResult.OK, onLabeled.status());
    }
}
