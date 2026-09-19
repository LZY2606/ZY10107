package com.molmap.web;

import com.molmap.service.MigrationService;
import com.molmap.store.CaseState;
import com.molmap.store.CaseRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/migrate")
public class MigrationController {

    private final MigrationService migration;
    private final CaseRepository cases;

    public MigrationController(MigrationService migration, CaseRepository cases) {
        this.migration = migration;
        this.cases = cases;
    }

    /**
     * Dry-run migration report for all cases pinned to a rule. Confirmed cases
     * are never rewritten; this only lists conclusion changes and mapping drift.
     */
    @PostMapping("/rules/{ruleId}/to/{version}")
    public MigrationService.MigrationReport migrate(@PathVariable String ruleId,
                                                    @PathVariable int version) {
        List<CaseState> all = cases.findAll();
        return migration.migrateRule(ruleId, version, all);
    }
}
