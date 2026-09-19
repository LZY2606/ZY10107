package chem.molgraph.api;

import chem.molgraph.domain.Molecule;
import chem.molgraph.domain.RuleDef;
import chem.molgraph.engine.RuleValidator;
import chem.molgraph.engine.TransformService;
import chem.molgraph.domain.Issue;
import chem.molgraph.service.ExportService;
import chem.molgraph.service.ReviewService;
import chem.molgraph.store.BatchRecord;
import chem.molgraph.store.CompositionRecord;
import chem.molgraph.store.MigrationReport;
import chem.molgraph.store.Repository;
import chem.molgraph.store.StoredCase;
import chem.molgraph.store.StoredRule;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final ReviewService reviewService;
    private final ExportService exportService;
    private final Repository repository;
    private final TransformService transformService;

    public ApiController(ReviewService reviewService, ExportService exportService,
                         Repository repository, TransformService transformService) {
        this.reviewService = reviewService;
        this.exportService = exportService;
        this.repository = repository;
        this.transformService = transformService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", repository.eventStore().isReadOnly() ? "DEGRADED" : "OK");
        body.put("version", repository.version());
        body.put("headHash", repository.headHash());
        body.put("readOnly", repository.eventStore().isReadOnly());
        body.put("recoveryError", repository.eventStore().recoveryError());
        body.put("quarantinedFrames", repository.eventStore().quarantinedFrames());
        return body;
    }

    @GetMapping("/rules")
    public List<StoredRule> rules() {
        return repository.rules();
    }

    @PostMapping("/rules/validate")
    public Map<String, Object> validateRule(@RequestBody RuleDef def) {
        List<Issue> issues = RuleValidator.validateRule(def);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", issues.isEmpty());
        out.put("issues", issues);
        if (issues.isEmpty()) {
            out.put("fingerprint", transformService.ruleFingerprint(def));
        }
        return out;
    }

    @PostMapping("/rules")
    public StoredRule createRule(@RequestParam(required = false) String ruleId,
                                 @RequestHeader(value = "If-Match", required = false) Long expectedVersion,
                                 @RequestBody RuleDef def) {
        return reviewService.importRule(ruleId != null ? ruleId : "rule-" + UUID.randomUUID(), def);
    }

    @PostMapping("/cases")
    public StoredCase createCase(@RequestParam(required = false) String caseId,
                                 @RequestParam(required = false) String title,
                                 @RequestBody Molecule evidence) {
        return reviewService.importCase(caseId != null ? caseId : "case-" + UUID.randomUUID(),
                title != null ? title : "未命名案例", evidence);
    }

    @GetMapping("/cases")
    public List<StoredCase> cases() {
        return repository.cases();
    }

    @GetMapping("/cases/{caseId}")
    public Map<String, Object> caseDetail(@PathVariable String caseId) {
        StoredCase c = repository.caseRecord(caseId)
                .orElseThrow(() -> new chem.molgraph.store.NotFoundException("案例不存在: " + caseId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caseRecord", c);
        repository.analysis(caseId).ifPresent(t -> out.put("analysis", t));
        return out;
    }

    @PostMapping("/cases/{caseId}/analyze")
    public Map<String, Object> analyze(@PathVariable String caseId,
                                       @RequestParam String ruleId,
                                       @RequestHeader(value = "If-Match", required = false) Long expectedVersion) {
        StoredCase updated = reviewService.analyze(caseId, ruleId, expectedVersion);
        return detail(caseId, updated);
    }

    @PostMapping("/cases/{caseId}/lock")
    public Map<String, Object> lock(@PathVariable String caseId,
                                    @RequestParam String candidateFingerprint,
                                    @RequestHeader(value = "If-Match", required = false) Long expectedVersion) {
        StoredCase updated = reviewService.lockMapping(caseId, candidateFingerprint, expectedVersion);
        return detail(caseId, updated);
    }

    @PostMapping("/cases/{caseId}/revalidate")
    public Map<String, Object> revalidate(@PathVariable String caseId,
                                          @RequestParam String ruleId,
                                          @RequestHeader(value = "If-Match", required = false) Long expectedVersion) {
        StoredCase updated = reviewService.revalidate(caseId, ruleId, expectedVersion);
        return detail(caseId, updated);
    }

    @PostMapping("/cases/{caseId}/confirm")
    public Map<String, Object> confirm(@PathVariable String caseId,
                                       @RequestHeader(value = "If-Match", required = false) Long expectedVersion) {
        StoredCase updated = reviewService.confirm(caseId, expectedVersion);
        return detail(caseId, updated);
    }

    @PostMapping("/cases/{caseId}/migrate")
    public MigrationReport migrate(@PathVariable String caseId,
                                   @RequestParam String newRuleId,
                                   @RequestParam(required = false) String newCaseId,
                                   @RequestParam(required = false) String migrationId,
                                   @RequestHeader(value = "If-Match", required = false) Long expectedVersion) {
        return reviewService.migrate(caseId, newRuleId,
                newCaseId != null ? newCaseId : "case-" + UUID.randomUUID(),
                migrationId != null ? migrationId : "migration-" + UUID.randomUUID(), expectedVersion);
    }

    @PostMapping("/batch")
    public BatchRecord batch(@RequestBody BatchRequest request,
                             @RequestHeader(value = "If-Match", required = false) Long expectedVersion) {
        return reviewService.runBatch(
                request.batchId() != null ? request.batchId() : "batch-" + UUID.randomUUID(),
                request.ruleId(), request.caseIds(), expectedVersion);
    }

    @GetMapping("/batches")
    public List<BatchRecord> batches() {
        return repository.batches();
    }

    @PostMapping("/compose")
    public CompositionRecord compose(@RequestBody ComposeRequest request,
                                     @RequestParam(defaultValue = "false") boolean persist,
                                     @RequestHeader(value = "If-Match", required = false) Long expectedVersion) {
        return reviewService.compose(
                request.compositionId() != null ? request.compositionId() : "comp-" + UUID.randomUUID(),
                request.caseId(), request.firstRuleId(), request.secondRuleId(), persist, expectedVersion);
    }

    @GetMapping("/compositions")
    public List<CompositionRecord> compositions() {
        return repository.compositions();
    }

    @GetMapping("/migrations")
    public List<MigrationReport> migrations() {
        return repository.migrations();
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=molgraph-export.zip")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(exportService.exportZip());
    }

    private Map<String, Object> detail(String caseId, StoredCase updated) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caseRecord", updated);
        repository.analysis(caseId).ifPresent(t -> out.put("analysis", t));
        return out;
    }

    public record BatchRequest(String batchId, String ruleId, List<String> caseIds) {
    }

    public record ComposeRequest(String compositionId, String caseId,
                                 String firstRuleId, String secondRuleId) {
    }
}
