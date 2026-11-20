package network.ike.docs.fhirfragments;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Paths, config files and a command runner shared by the tests. */
final class TestSupport {
    private TestSupport() {
    }

    record Result(int code, String out) {
    }

    static Path basedir() {
        return Path.of(System.getProperty("project.basedir", ".")).toAbsolutePath().normalize();
    }

    /** The skill source folder (the eval fixtures live inside it). */
    static Path skill() {
        return basedir().resolve("src/skill/fhir-ig-to-asciidoc-fragments");
    }

    static Path golden() {
        return basedir().resolve("src/test/resources/golden/mini");
    }

    static Path miniPackage() {
        return skill().resolve("evals/ingest-mini-package/files/package");
    }

    /** An ig-config.json with the US Core 9.0.0 values used by every fixture. */
    static Path usCoreConfig(Path dir, Path sourceDir) {
        ObjectNode c = Json.object();
        c.put("source_dir", sourceDir.toString());
        c.put("work_dir", ".");
        c.put("ig_slug", "us-core");
        c.put("ig_dir_name", "hl7-us-core-9-0-0");
        c.put("ig_version", "9.0.0");
        c.put("fhir_version", "4.0.1");
        c.put("citation", "HL7 International / Cross-Group Projects. US Core Implementation Guide, v9.0.0. "
                + "http://hl7.org/fhir/us/core/STU9/");
        c.put("ig_url", "http://hl7.org/fhir/us/core/STU9/");
        c.put("canonical_namespace", "http://hl7.org/fhir/us/core/");
        Path p = dir.resolve("ig-config.json");
        Json.writeText(p, Json.dumps(c, 1, false));
        return p;
    }

    /** Run a command in-process; output is captured. */
    static Result run(String... args) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (PrintStream ps = new PrintStream(bos, true, StandardCharsets.UTF_8)) {
            int code = Main.run(args, ps);
            ps.flush();
            return new Result(code, bos.toString(StandardCharsets.UTF_8));
        }
    }

    static String read(Path p) {
        return Linter.readExact(p);
    }

    static void copyFolder(Path from, Path to) {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path src : (Iterable<Path>) s::iterator) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void write(Path p, String text) {
        Json.writeText(p, text);
    }
}
