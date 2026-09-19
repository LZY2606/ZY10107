package com.molmap;

import com.fasterxml.jackson.databind.JsonNode;
import com.molmap.rule.Json;
import com.molmap.model.MolGraph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.molmap.Graphs.atom;
import static com.molmap.Graphs.bond;
import static com.molmap.Graphs.graph;

/** Shared fixtures for full-stack tests using a temporary data directory. */
abstract class AbstractIntegrationTest {

    protected Path dataDir;

    protected Path newDataDir() {
        try {
            dataDir = Files.createTempDirectory("molmap-test-");
            return dataDir;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
      }

    protected JsonNode ketoLhs() {
        MolGraph g = graph(
                List.of(atom("a", "C"), atom("b", "O"), atom("c", "C"), atom("h", "H")),
                List.of(bond("a", "b", "2"), bond("a", "c", "1"), bond("c", "h", "1")));
        return Json.mapper().valueToTree(g);
    }

    protected JsonNode ketoRhs() {
        MolGraph g = graph(
                List.of(atom("a", "C"), atom("b", "O"), atom("c", "C"), atom("h", "H")),
                List.of(bond("a", "b", "1"), bond("b", "h", "1"), bond("a", "c", "2")));
        return Json.mapper().valueToTree(g);
    }

    protected JsonNode inputGraph() {
        MolGraph g = graph(
                List.of(atom("m1", "C"), atom("m2", "O"), atom("m3", "C"),
                        atom("m4", "H"), atom("m5", "H")),
                List.of(bond("m1", "m2", "2"), bond("m1", "m3", "1"),
                        bond("m3", "m4", "1"), bond("m1", "m5", "1")));
        return Json.mapper().valueToTree(g);
    }

    protected JsonNode identityCarbonRuleRhs() {
        MolGraph g = graph(
                List.of(atom("a", "C"), atom("b", "O"), atom("c", "C"), atom("h", "H")),
                List.of(bond("a", "b", "2"), bond("a", "c", "1"), bond("c", "h", "1")));
        return Json.mapper().valueToTree(g);
    }
}
