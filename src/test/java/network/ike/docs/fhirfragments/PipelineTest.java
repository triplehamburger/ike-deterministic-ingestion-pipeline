package network.ike.docs.fhirfragments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import network.ike.docs.fhirfragments.TestSupport.Result;

/** The full single-IG pipeline on the 6-resource mini package, checked against files the Python version wrote. */
class PipelineTest {

    @TempDir
    Path tmp;

    private static List<String> names(Path dir, String suffix) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(suffix)).sorted().toList();
        }
    }

    @Test
    void extractRenderLintCorpusMutateMatchGoldenFiles() throws IOException {
        Path cfg = TestSupport.usCoreConfig(tmp, TestSupport.miniPackage());
        Path facts = tmp.resolve("facts");
        Path out = tmp.resolve("out");
        Path prose = TestSupport.golden().resolve("prose");

        Result r = TestSupport.run("extract-facts", "--config", cfg.toString(), facts.toString());
        assertEquals(0, r.code(), r.out());
        assertTrue(r.out().contains("facts written: 6, warnings: 0"), r.out());

        r = TestSupport.run("render", "--config", cfg.toString(), facts.toString(), prose.toString(), out.toString());
        assertEquals(0, r.code(), r.out());
        assertTrue(r.out().contains("rendered: 6"), r.out());

        Path expected = TestSupport.golden().resolve("expected");
        assertEquals(names(expected, ".adoc").stream().filter(n -> !n.equals("_includes.adoc")).toList(),
                names(out, ".adoc"));
        for (String n : names(out, ".adoc")) {
            assertEquals(TestSupport.read(expected.resolve(n)), TestSupport.read(out.resolve(n)), n);
        }

        r = TestSupport.run("lint", "--config", cfg.toString(), out.toString(), facts.toString(), prose.toString());
        assertEquals(0, r.code(), r.out());
        assertTrue(r.out().contains("files checked: 6, files with failures: 0"), r.out());

        r = TestSupport.run("corpus-check", "--config", cfg.toString(), out.toString(), facts.toString(),
                "--write-includes", "2026-11-19");
        assertEquals(0, r.code(), r.out());
        assertEquals(TestSupport.read(expected.resolve("_includes.adoc")), TestSupport.read(out.resolve("_includes.adoc")));

        r = TestSupport.run("lint", "--config", cfg.toString(), out.toString(), facts.toString(), "--derive-prose");
        assertEquals(0, r.code(), r.out());

        r = TestSupport.run("mutate", "--config", cfg.toString(), out.toString(), facts.toString(), prose.toString());
        assertEquals(0, r.code(), r.out());
        assertTrue(r.out().contains("seeded defects caught: 85 / 85 (not applicable: 0, unmutated files failing: 0)"),
                r.out());
    }

    @Test
    void renderFailsWhenAProseFileIsMissingUnlessStubbed() {
        Path cfg = TestSupport.usCoreConfig(tmp, TestSupport.miniPackage());
        Path facts = tmp.resolve("facts");
        Path emptyProse = tmp.resolve("prose");
        TestSupport.run("extract-facts", "--config", cfg.toString(), facts.toString());

        Result r = TestSupport.run("render", "--config", cfg.toString(), facts.toString(), emptyProse.toString(),
                tmp.resolve("out").toString());
        assertEquals(1, r.code(), r.out());
        assertTrue(r.out().contains("NO PROSE FILE (not rendered)"), r.out());

        r = TestSupport.run("render", "--config", cfg.toString(), facts.toString(), emptyProse.toString(),
                tmp.resolve("out").toString(), "--stub");
        assertEquals(0, r.code(), r.out());
        // stub prose is rejected by lint unless it is allowed
        r = TestSupport.run("lint", "--config", cfg.toString(), tmp.resolve("out").toString(), facts.toString(),
                emptyProse.toString());
        assertEquals(1, r.code(), r.out());
        r = TestSupport.run("lint", "--config", cfg.toString(), tmp.resolve("out").toString(), facts.toString(),
                "--allow-stub");
        assertEquals(0, r.code(), r.out());
    }

    @Test
    void lintAndRenderRefuseAnEmptyFactsFolder() {
        Path cfg = TestSupport.usCoreConfig(tmp, TestSupport.miniPackage());
        Result r = TestSupport.run("lint", "--config", cfg.toString(), tmp.toString(), tmp.resolve("none").toString(),
                "--allow-stub");
        assertEquals(1, r.code());
        assertTrue(r.out().contains("nothing was checked"), r.out());
        r = TestSupport.run("render", "--config", cfg.toString(), tmp.resolve("none").toString(), tmp.toString(),
                tmp.resolve("out").toString());
        assertEquals(1, r.code());
        assertTrue(r.out().contains("nothing rendered"), r.out());
    }

    @Test
    void usageErrorsReturnOneWithoutAStackTrace() {
        Result r = TestSupport.run("lint");
        assertEquals(1, r.code());
        assertTrue(r.out().contains("config required"), r.out());
        r = TestSupport.run("frobnicate", "--config", TestSupport.usCoreConfig(tmp, TestSupport.miniPackage()).toString());
        assertEquals(1, r.code());
        assertTrue(r.out().contains("unknown command"), r.out());
    }
}
