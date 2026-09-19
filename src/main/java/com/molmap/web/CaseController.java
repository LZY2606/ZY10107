package com.molmap.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.molmap.service.CaseService;
import com.molmap.service.LockRequest;
import com.molmap.service.CreateCaseRequest;
import com.molmap.store.CaseState;
import com.molmap.store.EvidenceStore;
import com.molmap.rule.Json;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private final CaseService cases;
    private final EvidenceStore evidence;

    public CaseController(CaseService cases, EvidenceStore evidence) {
        this.cases = cases;
        this.evidence = evidence;
    }

    /** Returns the original, immutable input graph received for a case. */
    @GetMapping("/{id}/input")
    public JsonNode input(@PathVariable String id) {
        CaseState state = cases.get(id).orElseThrow(() ->
                new com.molmap.service.NotFoundException("case not found: " + id));
        try {
            return Json.mapper().readTree(evidence.get(state.inputEvidenceHash()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("stored evidence is corrupt for case " + id, e);
        }
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateCaseRequest req) {
        CaseService.Created created = cases.create(req, System.currentTimeMillis());
        return Map.of("state", created.state(), "run", created.result());
    }

    @GetMapping
    public List<CaseState> list() {
        return cases.list();
    }

    @GetMapping("/{id}")
    public CaseState get(@PathVariable String id) {
        return cases.get(id).orElseThrow(() ->
                new com.molmap.service.NotFoundException("case not found: " + id));
    }

    /** Lock a mapping; the server re-validates it against the pinned rule version. */
    @PostMapping("/{id}/lock")
    public CaseState lock(@PathVariable String id, @RequestBody LockRequest req) {
        return cases.lock(id, req, System.currentTimeMillis());
    }

    @PostMapping("/{id}/unlock")
    public CaseState unlock(@PathVariable String id, @RequestBody Map<String, Long> body) {
        long version = body.getOrDefault("caseVersion", 0L);
        return cases.unlock(id, version, System.currentTimeMillis());
    }
}
