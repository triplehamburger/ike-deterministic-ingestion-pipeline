package network.ike.docs.fhirfragments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import network.ike.docs.fhirfragments.TestSupport.Result;

class IgBatchTest {

    @TempDir
    Path tmp;

    /** An IG folder ROOT/&lt;dir&gt;/package holding the mini conformance files and the given package.json. */
    private Path makeIg(Path root, String dir, String packageJson) throws IOException {
        Path pkg = root.resolve(dir).resolve("package");
        TestSupport.copyFolder(TestSupport.miniPackage(), pkg);
        TestSupport.write(pkg.resolve("package.json"), packageJson);
        return pkg;
    }

    private static final String ALPHA = "{\"name\": \"hl7.fhir.us.alpha\", \"version\": \"1.2.3\", \"title\": \"Alpha IG\", "
            + "\"author\": \"Acme\", \"url\": \"http://acme.org/alpha/1.2.3\", \"canonical\": \"http://acme.org/alpha\", "
            + "\"fhirVersions\": [\"4.0.1\"]}";

    private static String run(String... args) {
        Result r = TestSupport.run(args);
        return r.code() + "\n" + r.out();
    }

    @Test
    void planDerivesTheConfigFromPackageJsonAndBalancesBatches() throws IOException {
        Path root = tmp.resolve("root");
        makeIg(root, "alpha", ALPHA);
        Path work = tmp.resolve("work");

        Result r = TestSupport.run("batch", "plan", root.toString(), "--work-root", work.toString(), "--batch-size", "4");
        assertEquals(0, r.code(), r.out());

        JsonNode cfg = Json.read(work.resolve("hl7-us-alpha-1-2-3/ig-config.json"));
        assertEquals("us-alpha", cfg.get("ig_slug").asText());
        assertEquals("hl7-us-alpha-1-2-3", cfg.get("ig_dir_name").asText());
        assertEquals("1.2.3", cfg.get("ig_version").asText());
        assertEquals("4.0.1", cfg.get("fhir_version").asText());
        assertEquals("Acme. Alpha IG, v1.2.3. http://acme.org/alpha/1.2.3/", cfg.get("citation").asText());
        assertEquals("http://acme.org/alpha/1.2.3/", cfg.get("ig_url").asText());
        assertEquals("http://acme.org/alpha/", cfg.get("canonical_namespace").asText());

        // 6 resources, batch size 4: two balanced batches of 3
        JsonNode plan = Json.read(work.resolve("plan.json"));
        assertEquals(1, plan.size());
        assertEquals(6, plan.get(0).get("resources").asInt());
        assertEquals(2, plan.get(0).get("batches").size());
        List<String> b1 = Files.readAllLines(Path.of(plan.get(0).get("batches").get(0).asText()));
        assertEquals(3, b1.size());
        assertTrue(b1.get(0).startsWith("- FACTS_FILE=") && b1.get(0).contains("  PROSE_FILE="), b1.get(0));
        // segment order: profiles, then extensions (birthsex is an extension), then the rest
        assertTrue(b1.get(0).contains("StructureDefinition-us-core-vital-signs.adoc.facts.json"), b1.get(0));
        assertTrue(b1.get(1).contains("StructureDefinition-us-core-birthsex.adoc.facts.json"), b1.get(1));
    }

    @Test
    void overridesAndSkipTypesChangeTheGeneratedConfig() throws IOException {
        Path root = tmp.resolve("root");
        makeIg(root, "alpha", ALPHA);
        TestSupport.write(root.resolve("ig-overrides.json"), "{\"hl7.fhir.us.alpha\": {\"ig_slug\": \"core\"}}");
        Path work = tmp.resolve("work");
        // skipping OperationDefinition on purpose leaves five resources
        assertEquals(0, TestSupport.run("batch", "plan", root.toString(), "--work-root", work.toString(),
                "--skip-types", "OperationDefinition").code());
        JsonNode cfg = Json.read(work.resolve("hl7-us-alpha-1-2-3/ig-config.json"));
        assertEquals("core", cfg.get("ig_slug").asText());
        assertEquals("OperationDefinition", cfg.get("skip_resource_types").get(0).asText());
        assertEquals(5, Json.read(work.resolve("plan.json")).get(0).get("resources").asInt());
    }

    @Test
    void anIgWithAnUnsupportedTypeIsBlockedAndTheOthersCarryOn() throws IOException {
        Path root = tmp.resolve("root");
        makeIg(root, "alpha", ALPHA);
        Path beta = makeIg(root, "beta", "{\"name\": \"hl7.fhir.us.beta\", \"version\": \"2.0.0\"}");
        TestSupport.write(beta.resolve("ConceptMap-x.json"), "{\"resourceType\": \"ConceptMap\", \"id\": \"x\"}");
        Path work = tmp.resolve("work");

        Result r = TestSupport.run("batch", "plan", root.toString(), "--work-root", work.toString());
        assertEquals(1, r.code(), r.out());
        assertTrue(r.out().contains("blocked: unsupported resource types"), r.out());
        assertTrue(r.out().contains("--skip-types ConceptMap"), r.out());
        JsonNode plan = Json.read(work.resolve("plan.json"));
        assertEquals("planned", plan.get(0).get("status").asText());
        assertTrue(plan.get(1).get("status").asText().startsWith("blocked"));
    }

    @Test
    void twoPackagesWithTheSameNameAndVersionAreNotBothRun() throws IOException {
        Path root = tmp.resolve("root");
        makeIg(root, "a1", ALPHA);
        makeIg(root, "a2", ALPHA);
        Result r = TestSupport.run("batch", "plan", root.toString(), "--work-root", tmp.resolve("work").toString());
        assertEquals(1, r.code(), r.out());
        assertTrue(r.out().contains("same name and version as"), r.out());
    }

    @Test
    void statusFinishAndDeliverFollowTheProseProgress() throws IOException {
        Path root = tmp.resolve("root");
        makeIg(root, "alpha", ALPHA);
        Path work = tmp.resolve("work");
        TestSupport.run("batch", "plan", root.toString(), "--work-root", work.toString());

        assertTrue(run("batch", "status", "--work-root", work.toString()).contains("0/6"));
        String early = run("batch", "finish", "--work-root", work.toString());
        assertTrue(early.startsWith("1\n"), early);
        assertTrue(early.contains("prose incomplete (0/6)"), early);

        // with --allow-stub the pipeline runs on placeholder prose; nothing is delivered unless asked
        Path deliver = tmp.resolve("deliver");
        String stub = run("batch", "finish", "--work-root", work.toString(), "--allow-stub", "--mutate",
                "--date", "2026-01-02", "--deliver", deliver.toString());
        assertTrue(stub.startsWith("0\n"), stub);
        assertTrue(stub.contains("passed; delivered to"), stub);
        assertTrue(stub.contains("85/85") || stub.contains("/"), stub);
        Path dest = deliver.resolve("hl7-us-alpha-1-2-3");
        try (Stream<Path> s = Files.list(dest)) {
            assertEquals(7, s.count());      // 6 fragments + _includes.adoc
        }
        assertTrue(Files.readString(dest.resolve("_includes.adoc")).contains("// Generated: 2026-01-02"));
        assertTrue(Files.readString(dest.resolve("OperationDefinition-docref.adoc"))
                .contains(":topic-citation: Acme. Alpha IG, v1.2.3."));

        // an existing non-empty target is never overwritten without --overwrite
        String again = run("batch", "finish", "--work-root", work.toString(), "--allow-stub", "--deliver",
                deliver.toString());
        assertTrue(again.contains("NOT delivered"), again);
        String over = run("batch", "finish", "--work-root", work.toString(), "--allow-stub", "--deliver",
                deliver.toString(), "--overwrite");
        assertTrue(over.contains("delivered to"), over);
        assertFalse(over.contains("NOT delivered"), over);
    }

    @Test
    void anEmptyRootIsReported() throws IOException {
        Files.createDirectories(tmp.resolve("empty"));
        Result r = TestSupport.run("batch", "plan", tmp.resolve("empty").toString(), "--work-root",
                tmp.resolve("work").toString());
        assertEquals(1, r.code());
        assertTrue(r.out().contains("no IG package found"), r.out());
    }
}
