package com.molmap;

import com.molmap.service.*;
import com.molmap.store.*;
import static com.molmap.Graphs.atom;
import static com.molmap.Graphs.graph;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceAndExportTest extends AbstractIntegrationTest {

    @Test
    void replayRebuildsAllStateAfterRestart() {
        Path dir = newDataDir();
        String ruleId;
        String caseId;
        String certHash;

        // Session 1: create rule + case + lock.
        {
            StoragePaths paths = new StoragePaths(dir.toString());
            EvidenceStore ev = new EvidenceStore(paths.evidenceDir());
            RuleRepository rr = new RuleRepository(new JsonlStore(paths.rulesLog()));
            CaseRepository cr = new CaseRepository(new JsonlStore(paths.casesLog()));
            RuleService rs = new RuleService(rr, ev);
            CaseService cs = new CaseService(cr, rr, rs, ev);
            RuleState rule = rs.createOrVersion(
                    new RuleService.Submission("rule-k", "keto", ketoLhs(), ketoRhs()), 1L);
            ruleId = rule.id();
            CaseState c = cs.create(new CreateCaseRequest("c", ruleId, 1, inputGraph(), 1000), 1L).state();
            caseId = c.id();
            certHash = cs.lock(c.id(), new LockRequest(1, 0), 2L).certificate().certificateHash();
        }

        // Session 2: fresh repositories over the same directory (simulated restart).
        {
            StoragePaths paths = new StoragePaths(dir.toString());
            EvidenceStore ev = new EvidenceStore(paths.evidenceDir());
            RuleRepository rr = new RuleRepository(new JsonlStore(paths.rulesLog()));
            CaseRepository cr = new CaseRepository(new JsonlStore(paths.casesLog()));
            RuleService rs = new RuleService(rr, ev);
            CaseService cs = new CaseService(cr, rr, rs, ev);

            RuleState rule = rr.findLatest(ruleId).orElseThrow();
            assertEquals("rule-k", rule.id());
            CaseState c = cs.get(caseId).orElseThrow();
            assertEquals(CaseService.ST_CONFIRMED, c.status());
            assertEquals(2, c.version());
            assertEquals(certHash, c.certificate().certificateHash());
            assertEquals(c.inputFingerprint(), c.certificate().inputFingerprint());
        }
    }

    @Test
    void rawEvidenceIsImmutableAndContentAddressed() throws Exception {
        Path dir = newDataDir();
        StoragePaths paths = new StoragePaths(dir.toString());
        EvidenceStore ev = new EvidenceStore(paths.evidenceDir());
        String json = "{\"hello\":\"world\"}";
        String h1 = ev.put(json);
        Path file = paths.evidenceDir().resolve(h1 + ".json");
        long mtime = file.toFile().lastModified();
        String h2 = ev.put(json);
        assertEquals(h1, h2, "same content -> same address");
        assertEquals(mtime, file.toFile().lastModified(), "repeat put must not rewrite bytes");
        assertEquals(json, ev.get(h1));
        assertTrue(Files.list(paths.evidenceDir()).count() >= 1);
    }

    @Test
    void newRuleVersionDoesNotMutateOldVersion() {
        Path dir = newDataDir();
        StoragePaths paths = new StoragePaths(dir.toString());
        EvidenceStore ev = new EvidenceStore(paths.evidenceDir());
        RuleRepository rr = new RuleRepository(new JsonlStore(paths.rulesLog()));
        RuleService rs = new RuleService(rr, ev);
        RuleState v1 = rs.createOrVersion(
                new RuleService.Submission("rule-k", "keto", ketoLhs(), ketoRhs()), 1L);
        RuleState v2 = rs.createOrVersion(
                new RuleService.Submission("rule-k", "keto renamed", ketoLhs(), ketoLhs()), 2L);
        assertEquals(1, v1.version());
        assertEquals(2, v2.version());
        assertNotEquals(v1.fingerprint(), v2.fingerprint());
        // Old version still retrievable with its own fingerprint and name.
        RuleState old = rr.findVersion("rule-k", 1).orElseThrow();
        assertEquals("keto", old.name());
        assertEquals(v1.fingerprint(), old.fingerprint());
    }

    @Test
    void invalidRuleIsSavedAsDraftWithReasons() {
        Path dir = newDataDir();
        StoragePaths paths = new StoragePaths(dir.toString());
        EvidenceStore ev = new EvidenceStore(paths.evidenceDir());
        RuleRepository rr = new RuleRepository(new JsonlStore(paths.rulesLog()));
        RuleService rs = new RuleService(rr, ev);
        // Preserved atom changing element is an invalid rewrite.
        RuleState st = rs.createOrVersion(
                new RuleService.Submission("bad", "bad",
                        ketoLhs(),
                        com.molmap.rule.Json.mapper().valueToTree(
                                graph(java.util.List.of(atom("a", "N"), atom("b", "O"),
                                        atom("c", "C"), atom("h", "H")),
                                        java.util.List.of()))),
                1L);
        assertFalse(st.valid());
        assertTrue(st.validationReasons().stream().anyMatch(r -> r.contains("ELEMENT_MUTATION")),
                st.validationReasons().toString());
    }
}
