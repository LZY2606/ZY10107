package com.molmap;

import com.molmap.model.MolGraph;
import com.molmap.rule.Json;
import com.molmap.service.*;
import com.molmap.store.*;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CaseWorkflowTest extends AbstractIntegrationTest {

    private RuleService rules;
    private CaseService cases;
    private String ruleId;

    @BeforeEach
    void setUp() {
        Path dir = newDataDir();
        StoragePaths paths = new StoragePaths(dir.toString());
        EvidenceStore evidence = new EvidenceStore(paths.evidenceDir());
        RuleRepository ruleRepo = new RuleRepository(new JsonlStore(paths.rulesLog()));
        CaseRepository caseRepo = new CaseRepository(new JsonlStore(paths.casesLog()));
        rules = new RuleService(ruleRepo, evidence);
        cases = new CaseService(caseRepo, ruleRepo, rules, evidence);

        RuleState rs = rules.createOrVersion(
                new RuleService.Submission("rule-k", "keto", ketoLhs(), ketoRhs()), 1L);
        assertTrue(rs.valid(), rs.validationReasons().toString());
        ruleId = rs.id();
    }

    @Test
    void runLockRevalidateAndCertificate() {
        CreateCaseRequest req = new CreateCaseRequest("c", ruleId, 1, inputGraph(), 1000);
        CaseService.Created created = cases.create(req, 2L);
        CaseState st = created.state();
        assertEquals(CaseService.ST_PENDING, st.status());
        assertFalse(st.candidates().isEmpty());

        CaseState locked = cases.lock(st.id(), new LockRequest(1, 0), 3L);
        assertEquals(CaseService.ST_CONFIRMED, locked.status());
        assertNotNull(locked.certificate());
        assertEquals(locked.certificate().ruleFingerprint(), locked.ruleFingerprint());
    }

    @Test
    void optimisticConflictWhenTwoBrowsersLockSameCase() {
        CaseState st = cases.create(new CreateCaseRequest("c", ruleId, 1, inputGraph(), 1000), 1L).state();
        CaseState first = cases.lock(st.id(), new LockRequest(1, 0), 2L);
        assertEquals(2, first.version());
        // Second browser still holds version 1: it must see the conflict, not a silent overwrite.
        CaseRepository.Conflict ex = assertThrows(CaseRepository.Conflict.class,
                () -> cases.lock(st.id(), new LockRequest(1, 0), 3L));
        assertEquals(1, ex.expected);
        assertEquals(2, ex.actual);
        // Re-merging on the fresh version succeeds.
        CaseState merged = cases.lock(st.id(), new LockRequest(2, 0), 4L);
        assertEquals(CaseService.ST_CONFIRMED, merged.status());
    }

    @Test
    void derivedCandidatesCarryRuleVersionAndSourceFingerprint() {
        CaseState st = cases.create(new CreateCaseRequest("c", ruleId, 1, inputGraph(), 1000), 1L).state();
        for (PersistedCandidate c : st.candidates()) {
            assertEquals(ruleId + "@v1", c.derivedFromRuleVersion());
            assertNotNull(c.sourceFingerprint());
            assertEquals(st.ruleFingerprint(), c.sourceFingerprint());
        }
    }
}
