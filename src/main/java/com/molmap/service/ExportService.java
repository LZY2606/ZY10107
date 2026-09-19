package com.molmap.service;

import com.molmap.rule.Fingerprints;
import com.molmap.rule.Json;
import com.molmap.store.CaseRepository;
import com.molmap.store.CaseState;
import com.molmap.store.CompositionRepository;
import com.molmap.store.RuleRepository;
import com.molmap.store.RuleState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a self-contained export bundle (zip, in memory) containing every rule
 * version, every case, the mapping certificates, all unresolved reasons, and a
 * SHA-256 manifest. No external service is contacted.
 */
public final class ExportService {

    private final RuleRepository rules;
    private final CaseRepository cases;
    private final CompositionRepository compositions;

    public ExportService(RuleRepository rules, CaseRepository cases, CompositionRepository compositions) {
        this.rules = rules;
        this.cases = cases;
        this.compositions = compositions;
    }

    public byte[] exportZip() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Map<String, String> manifest = new LinkedHashMap<>();
        try (ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            List<RuleState> allRules = rules.findAllLatest().stream()
                    .flatMap(r -> rules.findAllVersions(r.id()).stream()).toList();
            writeJson(zip, "rules/all-rules.json", allRules, manifest);

            List<CaseState> allCases = cases.findAll();
            writeJson(zip, "cases/all-cases.json", allCases, manifest);

            List<?> certs = allCases.stream().filter(c -> c.certificate() != null)
                    .map(CaseState::certificate).toList();
            writeJson(zip, "certificates/mapping-certificates.json", certs, manifest);

            Map<String, List<String>> unresolved = new LinkedHashMap<>();
            for (CaseState c : allCases) unresolved.put(c.id(), c.unresolvedReasons());
            writeJson(zip, "unresolved/all-unresolved-reasons.json", unresolved, manifest);

            writeJson(zip, "compositions/all-compositions.json", compositions.findAll(), manifest);

            manifest.put("generatedAt", String.valueOf(System.currentTimeMillis()));
            String manifestJson = Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
            zip.putNextEntry(new ZipEntry("MANIFEST.json"));
            zip.write(manifestJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException("failed building export", e);
        }
        return bos.toByteArray();
    }

    private void writeJson(ZipOutputStream zip, String name, Object payload, Map<String, String> manifest)
            throws IOException {
        String json = Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        zip.putNextEntry(new ZipEntry(name));
        zip.write(json.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        manifest.put(name, Fingerprints.sha256(json));
    }
}
