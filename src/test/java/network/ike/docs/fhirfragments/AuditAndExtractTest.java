package network.ike.docs.fhirfragments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import network.ike.docs.fhirfragments.TestSupport.Result;

class AuditAndExtractTest {

    @TempDir
    Path tmp;

    /** The "detects-corrupted-fragment" eval: two of six fragments were edited by hand. */
    @Test
    void deriveProseAuditFlagsExactlyTheTwoTamperedFragments() {
        Path evalFiles = TestSupport.skill().resolve("evals/detects-corrupted-fragment/files");
        Path cfg = TestSupport.usCoreConfig(tmp, evalFiles.resolve("package"));
        Path facts = tmp.resolve("facts");
        assertEquals(0, TestSupport.run("extract-facts", "--config", cfg.toString(), facts.toString()).code());

        Result r = TestSupport.run("lint", "--config", cfg.toString(), evalFiles.resolve("fragments").toString(),
                facts.toString(), "--derive-prose");
        assertEquals(1, r.code(), r.out());
        List<String> failing = r.out().lines().filter(l -> l.startsWith("FAIL ")).map(l -> l.split(" ")[1]).distinct()
                .sorted().toList();
        assertEquals(List.of("StructureDefinition-us-core-birthsex.adoc", "StructureDefinition-us-core-vital-signs.adoc"),
                failing, r.out());
        assertTrue(r.out().contains("files checked: 6, files with failures: 2"), r.out());
        // the BOM and the changed cardinality are named
        assertTrue(r.out().contains("BOM present"), r.out());
        assertTrue(r.out().contains("file differs from deterministic render"), r.out());
    }

    private Path packageWith(String fileName, String json) {
        Path pkg = tmp.resolve("pkg");
        TestSupport.write(pkg.resolve("package.json"), "{\"name\": \"t\", \"version\": \"1\"}");
        TestSupport.write(pkg.resolve(fileName), json);
        return pkg;
    }

    @Test
    void anUnsupportedResourceTypeStopsWithExitTwoUntilSkippedOnPurpose() {
        Path pkg = packageWith("ConceptMap-x.json", "{\"resourceType\": \"ConceptMap\", \"id\": \"x\"}");
        Path cfg = TestSupport.usCoreConfig(tmp, pkg);
        Result r = TestSupport.run("extract-facts", "--config", cfg.toString(), tmp.resolve("facts").toString());
        assertEquals(2, r.code(), r.out());
        assertTrue(r.out().contains("UNSUPPORTED resource type 'ConceptMap' in ConceptMap-x.json"), r.out());

        // skip it on purpose
        ObjectNode c = (ObjectNode) Json.read(cfg);
        c.set("skip_resource_types", Json.arrayOf(List.of("ConceptMap")));
        TestSupport.write(cfg, Json.dumps(c, 1, false));
        r = TestSupport.run("extract-facts", "--config", cfg.toString(), tmp.resolve("facts").toString());
        assertEquals(0, r.code(), r.out());
        assertTrue(r.out().contains("facts written: 0, warnings: 0"), r.out());
    }

    @Test
    void aSourceShapeTheRendererCannotHandleIsAWarningWithExitOne() {
        Path pkg = packageWith("CodeSystem-nested.json", """
                {"resourceType": "CodeSystem", "id": "nested", "url": "http://x/CodeSystem/nested",
                 "content": "complete", "concept": [{"code": "a", "concept": [{"code": "b"}]}]}""");
        Path cfg = TestSupport.usCoreConfig(tmp, pkg);
        Result r = TestSupport.run("extract-facts", "--config", cfg.toString(), tmp.resolve("facts").toString());
        assertEquals(1, r.code(), r.out());
        assertTrue(r.out().contains("nested concepts in code system"), r.out());
    }

    @Test
    void outputFilesAreNamedAfterTypeAndId() {
        Path cfg = TestSupport.usCoreConfig(tmp, TestSupport.miniPackage());
        TestSupport.run("extract-facts", "--config", cfg.toString(), tmp.resolve("facts").toString());
        assertTrue(Files.exists(tmp.resolve("facts/OperationDefinition-docref.adoc.facts.json")));
        assertTrue(Files.exists(tmp.resolve("facts/ValueSet-us-core-clinical-note-type.adoc.facts.json")));
        assertFalse(Files.exists(tmp.resolve("facts/docref.adoc.facts.json")));
    }
}
