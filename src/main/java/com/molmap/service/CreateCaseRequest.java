package com.molmap.service;

import com.fasterxml.jackson.databind.JsonNode;

public record CreateCaseRequest(String name, String ruleId, Integer ruleVersion,
                                JsonNode input, Integer matchLimit) {}
