package network.ike.docs.fhirfragments;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** L5 corpus checks across all fragments of one IG; optionally writes {@code _includes.adoc}. */
final class CorpusChecker {
    private static final Pattern INCLUDE = Text.re("include::(.+?)\\[leveloffset=\\+1\\]");

    private final IgConfig cfg;
    private final Renderer renderer;

    CorpusChecker(IgConfig cfg, Renderer renderer) {
        this.cfg = cfg;
        this.renderer = renderer;
    }

    /** {@code corpus-check --config CFG OUT_DIR FACTS_DIR [--write-includes YYYY-MM-DD]}. */
    int run(Args args, PrintStream out) {
        List<String> pos = args.positional();
        if (pos.size() < 2) {
            throw new CliException("usage: corpus-check --config CFG OUT_DIR FACTS_DIR [--write-includes YYYY-MM-DD]");
        }
        Path outDir = Path.of(pos.get(0));
        Path factsDir = Path.of(pos.get(1));
        List<JsonNode> facts = new ArrayList<>();
        for (Path p : Renderer.factsFiles(factsDir)) {
            facts.add(Json.read(p));
        }
        List<String> errs = new ArrayList<>();
        if (facts.isEmpty()) {
            errs.add("no facts files found in " + factsDir);
        }
        int manifest = cfg.sourceFiles().size();
        if (facts.size() != manifest) {
            errs.add("facts count " + facts.size() + " != source manifest " + manifest);
        }
        Map<String, Integer> ids = new LinkedHashMap<>();
        Map<String, Integer> names = new LinkedHashMap<>();
        for (JsonNode f : facts) {
            ids.merge(Json.text(f, "topic_id"), 1, Integer::sum);
            names.merge(Json.text(f, "out_file"), 1, Integer::sum);
        }
        ids.forEach((k, v) -> {
            if (v > 1) {
                errs.add("duplicate topic id: " + k);
            }
        });
        names.forEach((k, v) -> {
            if (v > 1) {
                errs.add("duplicate filename: " + k);
            }
        });
        Set<String> expected = new LinkedHashSet<>();
        facts.forEach(f -> expected.add(Json.text(f, "out_file")));
        Set<String> present = new LinkedHashSet<>();
        try (Stream<Path> s = Files.list(outDir)) {
            s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".adoc") && !n.equals("_includes.adoc")).sorted().forEach(present::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        expected.stream().sorted().filter(n -> !present.contains(n)).forEach(n -> errs.add("missing output: " + n));
        present.stream().sorted().filter(n -> !expected.contains(n)).forEach(n -> errs.add("unexpected output: " + n));
        Map<String, Integer> bySeg = new LinkedHashMap<>();
        facts.forEach(f -> bySeg.merge(Json.text(f, "seg"), 1, Integer::sum));
        out.println("counts by segment: " + bySeg);
        // the topic id inside each file matches the facts
        for (JsonNode f : facts) {
            Path p = outDir.resolve(Json.text(f, "out_file"));
            if (Files.exists(p) && !Linter.readExact(p).contains(":topic-id: " + Json.text(f, "topic_id") + "\n")) {
                errs.add("topic id mismatch in " + Json.text(f, "out_file"));
            }
        }
        // includes
        String date = args.value("--write-includes");
        String inc = renderer.includesText(facts, date == null ? "0000-00-00" : date);
        if (date != null) {
            Json.writeText(outDir.resolve("_includes.adoc"), inc);
        }
        List<String> refs = new ArrayList<>();
        Matcher im = INCLUDE.matcher(inc);
        while (im.find()) {
            refs.add(im.group(1));
        }
        refs.stream().filter(r -> !present.contains(r)).forEach(r -> errs.add("include does not resolve: " + r));
        if (refs.size() != facts.size() || new HashSet<>(refs).size() != refs.size()) {
            errs.add("includes count " + refs.size() + " / unique " + new HashSet<>(refs).size() + " / facts "
                    + facts.size());
        }
        // dangling references into the IG namespace (informational)
        Set<String> canon = new HashSet<>();
        facts.forEach(f -> canon.add(Json.text(f, "canonical")));
        Map<String, Integer> dangling = new LinkedHashMap<>();
        Pattern ref = Text.re(Pattern.quote(cfg.canonicalNamespace) + "[A-Za-z]+/[A-Za-z0-9._-]+");
        for (JsonNode f : facts) {
            Path p = outDir.resolve(Json.text(f, "out_file"));
            if (!Files.exists(p)) {
                continue;
            }
            Matcher m = ref.matcher(Linter.readExact(p));
            while (m.find()) {
                if (!canon.contains(m.group())) {
                    dangling.merge(m.group(), 1, Integer::sum);
                }
            }
        }
        out.println("references into the IG namespace with no topic (info): " + dangling.size() + " distinct");
        dangling.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue(), a.getValue())).limit(8)
                .forEach(e -> out.println("   " + e.getValue() + " " + e.getKey()));
        errs.forEach(e -> out.println("FAIL " + e));
        out.println("corpus errors: " + errs.size());
        return errs.isEmpty() ? 0 : 1;
    }
}
