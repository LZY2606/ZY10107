package chem.molgraph.seed;

import chem.molgraph.domain.Atom;
import chem.molgraph.domain.Bond;
import chem.molgraph.domain.BondStereo;
import chem.molgraph.domain.Molecule;
import chem.molgraph.domain.RuleDef;
import chem.molgraph.domain.Stereo;

import java.util.List;

/** Built-in sample library (only seeded into an empty event log). */
public final class SampleData {

    private SampleData() {
    }

    public static RuleDef ketoEnolV1() {
        Molecule pattern = new Molecule(List.of(
                new Atom("oc", "C", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("o", "O", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("ac", "C", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("h", "H", 0, 0, Stereo.NONE, null, null, null, null)),
                List.of(new Bond("oc", "o", 2, BondStereo.NONE),
                        new Bond("oc", "ac", 1, BondStereo.NONE),
                        new Bond("ac", "h", 1, BondStereo.NONE)));
        return new RuleDef("KETO_ENOL", "1.0", pattern,
                List.of(),
                List.of(new Atom("h", "H", 0, 0, Stereo.NONE, null, null, null, null)),
                List.of("oc|o", "ac|h"),
                List.of(new Bond("oc", "o", 1, BondStereo.NONE),
                        new Bond("o", "h", 1, BondStereo.NONE)),
                "羰基重写为烯醇：C=O 降为 C-O，α-H 迁移到氧上（显式氢）。");
    }

    public static RuleDef ketoEnolV2() {
        Molecule pattern = new Molecule(List.of(
                new Atom("oc", "C", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("o", "O", 18, 0, Stereo.NONE, null, null, null, null),
                new Atom("ac", "C", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("h", "H", 0, 0, Stereo.NONE, null, null, null, null)),
                List.of(new Bond("oc", "o", 2, BondStereo.NONE),
                        new Bond("oc", "ac", 1, BondStereo.NONE),
                        new Bond("ac", "h", 1, BondStereo.NONE)));
        return new RuleDef("KETO_ENOL", "2.0", pattern,
                List.of(),
                List.of(new Atom("h", "H", 0, 0, Stereo.NONE, null, null, null, null)),
                List.of("oc|o", "ac|h"),
                List.of(new Bond("oc", "o", 1, BondStereo.NONE),
                        new Bond("o", "h", 1, BondStereo.NONE)),
                "2.0：仅匹配 18O 标记羰基（用于演示迁移漂移）。");
    }

    public static RuleDef sn2InversionV1() {
        Molecule pattern = new Molecule(List.of(
                new Atom("c", "C", 0, 0, Stereo.UP, List.of("lg", "g1", "g2", "g3"), null, null, null),
                new Atom("lg", "Cl", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("g1", "C", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("g2", "H", 0, 0, Stereo.NONE, null, null, null, null),
                new Atom("g3", "H", 0, 0, Stereo.NONE, null, null, null, null)),
                List.of(new Bond("c", "lg", 1, BondStereo.NONE),
                        new Bond("c", "g1", 1, BondStereo.NONE),
                        new Bond("c", "g2", 1, BondStereo.NONE),
                        new Bond("c", "g3", 1, BondStereo.NONE)));
        Atom centerOverride = new Atom("c", "C", 0, 0, Stereo.UP,
                List.of("lg", "g2", "g1", "g3"), null, null, null);
        Atom incoming = new Atom("lg", "O", 0, -1, Stereo.NONE, null, null, null, null);
        return new RuleDef("SN2_INVERSION", "1.0", pattern,
                List.of("lg"),
                List.of(centerOverride, incoming),
                List.of("c|lg"),
                List.of(new Bond("lg", "c", 1, BondStereo.NONE)),
                "SN2 亲核取代：离去基 Cl 被 O- 替换，四面体构型按槽位奇偶翻转。");
    }

    public static RuleDef brokenRuleV1() {
        Molecule pattern = new Molecule(List.of(
                new Atom("x", "Xx", 0, 0, Stereo.NONE, null, null, null, null)),
                List.of());
        return new RuleDef("BROKEN_RULE", "0.1", pattern,
                List.of(), List.of(), List.of(), List.of(),
                "故意无效的规则（未知元素），用于演示 INVALID_RULE 状态。");
    }

    public static Molecule acetone() {
        // 对称羰基底物：羰基碳两侧分支完全相同，各含一个显式 α-H。
        // 两个嵌入产生同一烯醇（对称归并为 1 个候选，映射数量保留为 2）。
        return new Molecule(List.of(
                new Atom("C0", "C", 0, 0, Stereo.NONE, null, null, 0.0, 0.0),
                new Atom("O0", "O", 0, 0, Stereo.NONE, null, null, 0.0, 1.4),
                new Atom("CL", "C", 0, 0, Stereo.NONE, null, null, -1.3, -0.7),
                new Atom("CR", "C", 0, 0, Stereo.NONE, null, null, 1.3, -0.7),
                new Atom("HL", "H", 0, 0, Stereo.NONE, null, null, -2.4, -0.7),
                new Atom("HR", "H", 0, 0, Stereo.NONE, null, null, 2.4, -0.7)),
                List.of(new Bond("C0", "O0", 2, BondStereo.NONE),
                        new Bond("C0", "CL", 1, BondStereo.NONE),
                        new Bond("C0", "CR", 1, BondStereo.NONE),
                        new Bond("CL", "HL", 1, BondStereo.NONE),
                        new Bond("CR", "HR", 1, BondStereo.NONE)));
    }

    public static Molecule chiralChloride() {
        return new Molecule(List.of(
                new Atom("C*", "C", 0, 0, Stereo.UP, List.of("Cl", "R", "H1", "H2"), null, 0.0, 0.0),
                new Atom("Cl", "Cl", 0, 0, Stereo.NONE, null, null, 1.4, 0.9),
                new Atom("R", "C", 0, 0, Stereo.NONE, null, null, -1.3, 0.9),
                new Atom("H1", "H", 0, 0, Stereo.NONE, null, null, -0.6, -1.2),
                new Atom("H2", "H", 0, 0, Stereo.NONE, null, null, 0.9, -1.3)),
                List.of(new Bond("C*", "Cl", 1, BondStereo.NONE),
                        new Bond("C*", "R", 1, BondStereo.NONE),
                        new Bond("C*", "H1", 1, BondStereo.NONE),
                        new Bond("C*", "H2", 1, BondStereo.NONE)));
    }

    public static Molecule ethanol() {
        return new Molecule(List.of(
                new Atom("C1", "C", 0, 0, Stereo.NONE, null, null, 0.0, 0.0),
                new Atom("C2", "C", 0, 0, Stereo.NONE, null, null, 1.4, 0.0),
                new Atom("O1", "O", 0, 0, Stereo.NONE, null, null, 2.8, 0.0),
                new Atom("H1", "H", 0, 0, Stereo.NONE, null, null, 0.0, 1.1)),
                List.of(new Bond("C1", "C2", 1, BondStereo.NONE),
                        new Bond("C2", "O1", 1, BondStereo.NONE),
                        new Bond("C1", "H1", 1, BondStereo.NONE)));
    }
}
