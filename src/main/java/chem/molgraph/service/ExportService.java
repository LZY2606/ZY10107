package chem.molgraph.service;

import chem.molgraph.domain.TransformResult;
import chem.molgraph.store.BatchRecord;
import chem.molgraph.store.CompositionRecord;
import chem.molgraph.store.Event;
import chem.molgraph.store.EventStore;
import chem.molgraph.store.MappingCertificate;
import chem.molgraph.store.MigrationReport;
import chem.molgraph.store.Repository;
import chem.molgraph.store.StoredCase;
import chem.molgraph.store.StoredRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ExportService {

    private final Repository repository;
    private final ObjectMapper mapper;
    private final EventStore eventStore;

    public ExportService(Repository repository, ObjectMapper mapper, EventStore eventStore) {
        this.repository = repository;
        this.mapper = mapper;
        this.eventStore = eventStore;
    }

    public byte[] exportZip() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            writeJson(zip, "rules.json", repository.rules());
            writeJson(zip, "cases.json", repository.cases());
            writeJson(zip, "mapping-certificates.json", allCertificates());
            writeJson(zip, "pending-reasons.json", pendingReasons());
            writeJson(zip, "batches.json", repository.batches());
            writeJson(zip, "compositions.json", repository.compositions());
            writeJson(zip, "migrations.json", repository.migrations());
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("exportedAt", System.currentTimeMillis());
            manifest.put("eventVersion", repository.version());
            manifest.put("headHash", repository.headHash());
            manifest.put("ruleCount", repository.rules().size());
            manifest.put("caseCount", repository.cases().size());
            manifest.put("certificateCount", allCertificates().size());
            manifest.put("pendingCount", pendingReasons().size());
            writeJson(zip, "manifest.json", manifest);
            List<String> raw = new ArrayList<>();
            for (Event e : eventStore.events()) {
                raw.add(e.seq() + "\t" + e.type() + "\t" + e.chainHash() + "\t" + e.payloadHash());
            }
            writeText(zip, "event-chain.txt", String.join("\n", raw));
        } catch (IOException e) {
            throw new IllegalStateException("导出失败", e);
        }
        return bos.toByteArray();
    }

    private List<Map<String, Object>> allCertificates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StoredCase c : repository.cases()) {
            for (MappingCertificate cert : c.certificates()) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("certificateId", cert.certificateId());
                view.put("caseId", cert.caseId());
                view.put("candidateFingerprint", cert.candidateFingerprint());
                view.put("ruleName", cert.ruleName());
                view.put("ruleVersion", cert.ruleVersion());
                view.put("ruleFingerprint", cert.ruleFingerprint());
                view.put("evidenceFingerprint", cert.evidenceFingerprint());
                view.put("mapping", cert.mapping());
                view.put("baseVersion", cert.baseVersion());
                view.put("chainHash", cert.chainHash());
                out.add(view);
            }
        }
        return out;
    }

    /** Every unresolved reason across cases, analysis results and compositions. */
    private List<Map<String, Object>> pendingReasons() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StoredCase c : repository.cases()) {
            TransformResult result = repository.analysis(c.caseId()).orElse(null);
            if (result == null) {
                addPending(out, c.caseId(), "CASE_NOT_ANALYZED", "案例尚未运行规则分析。");
                continue;
            }
            if (TransformResult.INVALID_RULE.equals(result.status())) {
                for (var issue : result.issues()) {
                    addPending(out, c.caseId(), "INVALID_RULE:" + issue.code(), issue.message());
                }
            }
            if (TransformResult.INVALID_INPUT.equals(result.status())) {
                for (var issue : result.issues()) {
                    addPending(out, c.caseId(), "INVALID_INPUT:" + issue.code(), issue.message());
                }
            }
            if (TransformResult.NO_MATCH.equals(result.status())) {
                addPending(out, c.caseId(), "NO_MATCH", "规则在该案例上没有任何匹配。");
            }
            if (result.limitReached()) {
                addPending(out, c.caseId(), "LIMIT_REACHED",
                        "匹配数达到搜索上限 " + result.searchLimit() + "，映射枚举不完整。");
            }
            result.candidates().stream()
                    .flatMap(v -> v.issues().stream())
                    .forEach(issue -> addPending(out, c.caseId(), "CANDIDATE:" + issue.code(), issue.message()));
            if (!StoredCase.CONFIRMED.equals(c.status()) && !c.certificates().isEmpty()) {
                addPending(out, c.caseId(), "AWAITING_CONFIRMATION", "映射已锁定，等待人工确认。");
            }
            if (c.certificates().isEmpty() && !StoredCase.CONFIRMED.equals(c.status())
                    && TransformResult.OK.equals(result.status())) {
                addPending(out, c.caseId(), "AWAITING_LOCK", "存在候选产物，但尚未锁定映射。");
            }
            if (StoredCase.STALE_RULE.equals(c.status())) {
                addPending(out, c.caseId(), "STALE_RULE",
                        "规则已变更：已确认结论未被重写，需要迁移到新版本。");
            }
        }
        for (CompositionRecord comp : repository.compositions()) {
            if (!"OK".equals(comp.status())) {
                addPending(out, comp.compositionId(), "COMPOSITION:" + comp.status(),
                        comp.failedReason());
            }
        }
        for (MigrationReport migration : repository.migrations()) {
            if (!MigrationReport.CONCLUSION_COMPATIBLE.equals(migration.conclusion())) {
                addPending(out, migration.newCaseId(), "MIGRATION:" + migration.conclusion(),
                        "源案例 " + migration.sourceCaseId() + " 迁移到 "
                                + migration.newRuleVersion() + " 后结论或映射发生变化。");
            }
        }
        if (eventStore.isReadOnly()) {
            addPending(out, "event-log", "STORE_READ_ONLY", eventStore.recoveryError());
        }
        return out;
    }

    private void addPending(List<Map<String, Object>> out, String ref, String code, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("ref", ref);
        item.put("code", code);
        item.put("message", message);
        out.add(item);
    }

    private void writeJson(ZipOutputStream zip, String name, Object payload) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload));
        zip.closeEntry();
    }

    private void writeText(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
