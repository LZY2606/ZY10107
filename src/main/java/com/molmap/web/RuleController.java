package com.molmap.web;

import com.molmap.service.RuleService;
import com.molmap.store.RuleState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleService rules;

    public RuleController(RuleService rules) {
        this.rules = rules;
    }

    @PostMapping
    public RuleState submit(@RequestBody RuleService.Submission sub) {
        return rules.createOrVersion(sub, System.currentTimeMillis());
    }

    @GetMapping
    public List<RuleState> latest() {
        return rules.findAllLatest();
    }

    @GetMapping("/{id}/versions")
    public List<RuleState> versions(@PathVariable String id) {
        return rules.findAllVersions(id);
    }

    @GetMapping("/{id}/versions/{version}")
    public Map<String, Object> version(@PathVariable String id, @PathVariable int version) {
        RuleState state = rules.findVersion(id, version)
                .orElseThrow(() -> new com.molmap.service.NotFoundException("rule version not found"));
        return Map.of("state", state, "rule", rules.toRule(state));
    }
}
