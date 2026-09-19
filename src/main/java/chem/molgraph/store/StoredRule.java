package chem.molgraph.store;

import chem.molgraph.domain.RuleDef;

public record StoredRule(String ruleId, String name, String version, RuleDef def, String fingerprint, long streamVersion) {
}
