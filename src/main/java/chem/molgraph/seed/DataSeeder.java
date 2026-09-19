package chem.molgraph.seed;

import chem.molgraph.service.ReviewService;
import chem.molgraph.store.Repository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final Repository repository;
    private final ReviewService reviewService;

    public DataSeeder(Repository repository, ReviewService reviewService) {
        this.repository = repository;
        this.reviewService = reviewService;
    }

    @PostConstruct
    public void seed() {
        if (!repository.rules().isEmpty() || repository.eventStore().isReadOnly()) {
            return;
        }
        reviewService.importRule("rule-keto-enol-v1", SampleData.ketoEnolV1());
        reviewService.importRule("rule-keto-enol-v2", SampleData.ketoEnolV2());
        reviewService.importRule("rule-sn2-v1", SampleData.sn2InversionV1());
        try {
            reviewService.importRule("rule-broken", SampleData.brokenRuleV1());
        } catch (IllegalArgumentException expected) {
            log.info("内置无效规则按预期被拒绝入库: {}", expected.getMessage());
        }

        reviewService.importCase("case-acetone", "丙酮羰基-烯醇（对称匹配示例）", SampleData.acetone());
        reviewService.importCase("case-sn2", "手性氯代物 SN2（立体翻转示例）", SampleData.chiralChloride());
        reviewService.importCase("case-ethanol", "乙醇（无羰基双键，NO_MATCH 示例）", SampleData.ethanol());
        log.info("种子数据已加载：3 条规则（含 1 个无效定义），3 个案例。");
    }
}
