package com.molmap;

import com.molmap.service.BatchService;
import com.molmap.service.CaseService;
import com.molmap.service.CompositionService;
import com.molmap.service.MigrationService;
import com.molmap.service.RuleService;
import com.molmap.store.CaseRepository;
import com.molmap.store.CompositionRepository;
import com.molmap.store.EvidenceStore;
import com.molmap.store.JsonlStore;
import com.molmap.store.RuleRepository;
import com.molmap.store.StoragePaths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    public StoragePaths storagePaths(@Value("${molmap.data-dir:data}") String dir) {
        return new StoragePaths(dir);
    }

    @Bean
    public EvidenceStore evidenceStore(StoragePaths paths) {
        return new EvidenceStore(paths.evidenceDir());
    }

    @Bean
    public RuleRepository ruleRepository(StoragePaths paths) {
        return new RuleRepository(new JsonlStore(paths.rulesLog()));
    }

    @Bean
    public CaseRepository caseRepository(StoragePaths paths) {
        return new CaseRepository(new JsonlStore(paths.casesLog()));
    }

    @Bean
    public CompositionRepository compositionRepository(StoragePaths paths) {
        return new CompositionRepository(new JsonlStore(paths.compositionsLog()));
    }

    @Bean
    public RuleService ruleService(RuleRepository repo, EvidenceStore evidence) {
        return new RuleService(repo, evidence);
    }

    @Bean
    public CaseService caseService(CaseRepository cases, RuleRepository rules,
                                   RuleService ruleService, EvidenceStore evidence) {
        return new CaseService(cases, rules, ruleService, evidence);
    }

    @Bean
    public MigrationService migrationService(CaseService cases, RuleService rules, EvidenceStore evidence) {
        return new MigrationService(cases, rules, evidence);
    }

    @Bean
    public BatchService batchService(CaseService cases) {
        return new BatchService(cases);
    }

    @Bean
    public CompositionService compositionService(CaseService cases, RuleService rules,
                                                 EvidenceStore evidence, CompositionRepository repo) {
        return new CompositionService(cases, rules, evidence, repo);
    }

    @Bean
    public com.molmap.service.ExportService exportService(RuleRepository rules, CaseRepository cases,
                                                          CompositionRepository compositions) {
        return new com.molmap.service.ExportService(rules, cases, compositions);
    }
}
