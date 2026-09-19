package chem.molgraph;

import chem.molgraph.domain.*;
import chem.molgraph.seed.SampleData;
import chem.molgraph.service.ReviewService;
import chem.molgraph.store.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "molgraph.data.dir=target/it-data",
        "molgraph.search.limit=128"
})
class ApplicationIntegrationTest {

    @Autowired ReviewService reviewService;
    @Autowired Repository repository;

    @BeforeAll
    static void cleanDataDir() throws Exception {
        Path dir = Path.of("target/it-data");
        if (Files.exists(dir)) {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void fullWorkflowAnalyzeLockConfirmAndStaleMigration() {
        StoredCase acetone = repository.caseRecord("case-acetone").orElseThrow();
        StoredRule v1 = repository.rules().stream()
                .filter(r -> r.name().equals("KETO_ENOL") && r.version().equals("1.0")).findFirst().orElseThrow();
        StoredCase analyzed = reviewService.analyze(acetone.caseId(), v1.ruleId(), repository.version());
        TransformResult result = repository.analysis(acetone.caseId()).orElseThrow();
        String fp = result.candidates().get(0).fingerprint();

        StoredCase locked = reviewService.lockMapping(acetone.caseId(), fp, repository.version());
        assertEquals(StoredCase.LOCKED, locked.status());
        assertEquals(1, locked.certificates().size());

        StoredCase confirmed = reviewService.confirm(acetone.caseId(), repository.version());
        assertEquals(StoredCase.CONFIRMED, confirmed.status());

        StoredRule v2 = repository.rules().stream()
                .filter(r -> r.name().equals("KETO_ENOL") && r.version().equals("2.0")).findFirst().orElseThrow();
        StoredCase revalidated = reviewService.revalidate(acetone.caseId(), v2.ruleId(), repository.version());
        assertEquals(StoredCase.STALE_RULE, revalidated.status(), "规则变化不能重写已确认案例");

        MigrationReport report = reviewService.migrate(acetone.caseId(), v2.ruleId(),
                "case-acetone-v2", "migration-acetone", repository.version());
        assertEquals(MigrationReport.CONCLUSION_BROKEN, report.conclusion());
        StoredCase original = repository.caseRecord(acetone.caseId()).orElseThrow();
        assertEquals(StoredCase.STALE_RULE, original.status(), "旧案例必须保留");
        StoredCase migratedCase = repository.caseRecord("case-acetone-v2").orElseThrow();
        assertEquals(acetone.caseId(), migratedCase.parentCaseId());
        assertEquals(TransformResult.NO_MATCH, report.newResult().status());
        assertTrue(report.drifts().stream().anyMatch(d -> MappingDrift.DISAPPEARED.equals(d.kind())));
    }

    @Test
    void optimisticConcurrencySecondWriterSeesConflict() {
        StoredCase sn2 = repository.caseRecord("case-sn2").orElseThrow();
        StoredRule rule = repository.rules().stream()
                .filter(r -> r.ruleId().equals("rule-sn2-v1")).findFirst().orElseThrow();
        long versionAtLoad = repository.version();
        reviewService.analyze(sn2.caseId(), rule.ruleId(), versionAtLoad);
        TransformResult result = repository.analysis(sn2.caseId()).orElseThrow();
        String fp = result.candidates().get(0).fingerprint();
        ConflictException ex = assertThrows(ConflictException.class,
                () -> reviewService.lockMapping(sn2.caseId(), fp, versionAtLoad));
        assertEquals(versionAtLoad, ex.expectedVersion);
        assertTrue(ex.actualVersion > versionAtLoad);
    }

    @Test
    void batchRecordsMultipleInputsWithStableDefault() {
        StoredRule rule = repository.rules().stream()
                .filter(r -> r.name().equals("KETO_ENOL") && r.version().equals("1.0")).findFirst().orElseThrow();
        BatchRecord batch = reviewService.runBatch("batch-it", rule.ruleId(),
                List.of("case-acetone", "case-ethanol"), repository.version());
        assertEquals(TransformResult.OK, batch.results().get(0).status());
        assertEquals(TransformResult.NO_MATCH, batch.results().get(1).status());
        assertEquals("case-acetone", batch.defaultCaseId());
        assertNotNull(batch.defaultFingerprint());
    }

    @Test
    void failedCompositionIsExplicitAndPersistedButNoHalfResult() {
        StoredCase acetone = repository.caseRecord("case-acetone").orElseThrow();
        StoredRule keto = repository.rules().stream()
                .filter(r -> r.ruleId().equals("rule-keto-enol-v1")).findFirst().orElseThrow();
        StoredRule sn2 = repository.rules().stream()
                .filter(r -> r.ruleId().equals("rule-sn2-v1")).findFirst().orElseThrow();
        CompositionRecord record = reviewService.compose("comp-it", acetone.caseId(),
                keto.ruleId(), sn2.ruleId(), true, repository.version());
        assertNotEquals("OK", record.status());
        assertNotNull(record.failedReason());
        CompositionRecord stored = repository.compositions().stream()
                .filter(c -> c.compositionId().equals("comp-it")).findFirst().orElseThrow();
        assertEquals(record.status(), stored.status());
    }

    @Test
    void provenanceDependentCompositionDetected() {
        RuleDef produces = SampleData.ketoEnolV1();
        RuleDef needsProvenance = new RuleDef("NEEDS_TAG", "1.0",
                new Molecule(List.of(
                        new Atom("o", "O", 0, 0, Stereo.NONE, null, "KETO_ENOL@1.0", null, null),
                        new Atom("h", "H", 0, 0, Stereo.NONE, null, null, null, null)),
                        List.of(new Bond("o", "h", 1, BondStereo.NONE))),
                List.of(), List.of(), List.of(), List.of(), "只接受 KETO_ENOL 临时来源的 O");
        reviewService.importRule("rule-needs-tag", needsProvenance);
        CompositionRecord record = reviewService.compose("comp-prov", "case-acetone",
                "rule-keto-enol-v1", "rule-needs-tag", false, null);
        assertEquals("TEMP_ID_PROVENANCE_DEPENDENCY", record.status());
    }

    @Test
    void confirmedCaseCannotBeOverwrittenBySameVersionContent() {
        StoredCase acetone = repository.caseRecord("case-acetone").orElseThrow();
        long countBefore = repository.cases().size();
        assertThrows(IllegalArgumentException.class,
                () -> reviewService.importCase(acetone.caseId(), "想改名", SampleData.acetone()));
        assertEquals(countBefore, repository.cases().size());
    }
}
