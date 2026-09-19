package com.molmap;

import com.fasterxml.jackson.databind.JsonNode;
import com.molmap.model.MolGraph;
import com.molmap.rule.Json;
import com.molmap.service.*;
import com.molmap.store.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static com.molmap.Graphs.atom;
import static com.molmap.Graphs.bond;
import static com.molmap.Graphs.graph;
import static org.junit.jupiter.api.Assertions.*;

class MigrationAndCompositionTest extends AbstractIntegrationTest {

    private StoragePaths paths;
    private RuleService rules;
    private CaseService cases;
    private MigrationService migration;
    private CompositionService composition;
    private CompositionRepository compRepo;

    @BeforeEach
    void setUp() {
        Path dir = newDataDir();
        paths = new StoragePaths(dir.toString());
        EvidenceStore evidence = new EvidenceStore(paths.evidenceDir());
        RuleRepository ruleRepo = new RuleRepository(new JsonlStore(paths.rulesLog()));
        CaseRepository caseRepo = new CaseRepository(new JsonlStore(paths.casesLog()));
        compRepo = new CompositionRepository(new JsonlStore(paths.compositionsLog()));
        rules = new RuleService(ruleRepo, evidence);
        cases = new CaseService(caseRepo, ruleRepo, rules, evidence);
        migration = new MigrationService(cases, rules, evidence);
        composition = new CompositionService(cases, rules, evidence, compRepo);
    }

    @Test
    void confirmedCaseIsNotRewrittenButReportShowsStatusChange() {
        RuleState v1 = rules.createOrVersion(
                new RuleService.Submission("rule-k", "keto", ketoLhs(), ketoRhs()), 1L);
        CaseState st = cases.create(new CreateCaseRequest("c", v1.id(), 1, inputGraph(), 1000), 1L).state();
        cases.lock(st.id(), new LockRequest(1, 0), 2L);

        // v2: a still-valid rule whose pattern additionally demands a second O
        // attached to c. The input lacks it, so the rule is valid but does not
        // match -> NO_MATCH (distinct from INVALID_RULE).
        JsonNode lhs2 = Json.mapper().valueToTree(graph(
                List.of(atom("a", "C"), atom("b", "O"), atom("c", "C"),
                        atom("h", "H"), atom("o2", "O")),
                List.of(bond("a", "b", "2"), bond("a", "c", "1"),
                        bond("c", "h", "1"), bond("c", "o2", "1"))));
        JsonNode rhs2 = Json.mapper().valueToTree(graph(
                List.of(atom("a", "C"), atom("b", "O"), atom("c", "C"),
                        atom("h", "H"), atom("o2", "O")),
                List.of(bond("a", "b", "1"), bond("b", "h", "1"),
                        bond("a", "c", "2"), bond("c", "o2", "1"))));
        RuleState v2 = rules.createOrVersion(
                new RuleService.Submission(v1.id(), "keto", lhs2, rhs2), 3L);
        assertEquals(2, v2.version());

        MigrationService.MigrationReport report = migration.migrateRule(v1.id(), 2, cases.list());
        MigrationService.CaseMigration cm = report.cases().get(0);
        assertTrue(cm.conclusionChanged());
        assertTrue(cm.changes().stream().anyMatch(x -> x.contains("STATUS OK -> NO_MATCH")),
                cm.changes().toString());

        // The confirmed case itself remains pinned to v1 and CONFIRMED.
        CaseState still = cases.get(st.id()).orElseThrow();
        assertEquals("rule-k@v1", still.ruleVersionRef());
        assertEquals(CaseService.ST_CONFIRMED, still.status());
    }

    @Test
    void compositionFailsWhenSecondRuleDependsOnTempIdAndLeavesNoState() {
        // Rule 1 creates a new H attached to a C. Rule 2 matches a C-H bond: since
        // the new H is a temporary id, composition must be rejected atomically.
        JsonNode r1lhs = Json.mapper().valueToTree(graph(
                List.of(atom("a", "C"), atom("x", "H")),
                List.of(bond("a", "x", "1"))));
        JsonNode r1rhs = Json.mapper().valueToTree(graph(
                List.of(atom("a", "C"), atom("x", "H"), atom("n", "H")),
                List.of(bond("a", "x", "1"), bond("a", "n", "1"))));
        RuleState r1 = rules.createOrVersion(new RuleService.Submission("r1", "addH", r1lhs, r1rhs), 1L);
        assertTrue(r1.valid(), r1.validationReasons().toString());

        JsonNode r2lhs = Json.mapper().valueToTree(graph(
                List.of(atom("p", "C"), atom("q", "H")), List.of(bond("p", "q", "1"))));
        JsonNode r2rhs = r2lhs;
        RuleState r2 = rules.createOrVersion(new RuleService.Submission("r2", "ch", r2lhs, r2rhs), 1L);
        assertTrue(r2.valid());

        JsonNode input = Json.mapper().valueToTree(graph(
                List.of(atom("a", "C", null, 0, null), atom("x", "H", null, 0, null)),
                List.of(bond("a", "x", "1"))));
        CompositionService.CompositionResponse resp = composition.compose(
                new CompositionService.ComposeRequest("r1", 1, "r2", 1, input, 0, 1000), 1L);
        assertFalse(resp.state().succeeded());
        assertEquals("RULE2_DEPENDS_ON_TEMP_IDS", resp.state().status());
        // Only a failure record exists; there is no successful half-result.
        assertTrue(compRepo.findAll().stream().noneMatch(CompositionState::succeeded));
    }

    @Test
    void validCompositionSucceedsWithoutTempDependency() {
        // Two identity rules over a C-H motif: second rule only touches atoms the
        // first rule preserved (no newly created atom), so composition succeeds.
        JsonNode lhs = Json.mapper().valueToTree(graph(
                List.of(atom("p", "C"), atom("q", "H")), List.of(bond("p", "q", "1"))));
        RuleState r1 = rules.createOrVersion(new RuleService.Submission("i1", "id1", lhs, lhs), 1L);
        RuleState r2 = rules.createOrVersion(new RuleService.Submission("i2", "id2", lhs, lhs), 1L);
        JsonNode input = lhs;
        CompositionService.CompositionResponse resp = composition.compose(
                new CompositionService.ComposeRequest("i1", 1, "i2", 1, input, 0, 1000), 1L);
        assertTrue(resp.state().succeeded(), resp.state().reason());
        assertNotNull(resp.state().productFingerprint());
    }
}
