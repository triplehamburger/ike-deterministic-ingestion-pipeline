package network.ike.docs.fhirfragments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs the pipeline over a DIRECTORY of IGs.
 *
 * <ul>
 *   <li>{@code plan}: find every IG package under ROOT (a folder holding a package.json), write
 *       WORK/&lt;ig&gt;/ig-config.json from that package.json, extract facts and split the resources into prose
 *       batches. The prose agents are not run here: the caller runs one per manifest, then calls finish.</li>
 *   <li>{@code status}: prose progress per IG.</li>
 *   <li>{@code finish}: for every IG whose prose is complete, render, lint, corpus-check (writes
 *       _includes.adoc), optionally the seeded-defect test and delivery to OUT_ROOT/&lt;ig_dir_name&gt;/.</li>
 * </ul>
 *
 * <p>One IG failing never stops the others; the exit code is 1 if any IG did not finish cleanly. ROOT/ig-overrides.json
 * (or --overrides FILE) can change any generated config key per package name.
 */
final class IgBatch {
    private static final Set<String> SKIP_DIRS = Set.of("node_modules", ".git", "example", "examples", "__pycache__");
    private static final List<String> SEG_ORDER = List.of("profiles", "extensions", "searchparams", "valuesets",
            "codesystems", "capabilitystatements", "operations");
    private static final Pattern UNSUPPORTED = Pattern.compile("UNSUPPORTED resource type '([^']*)'");
    private static final Pattern LINT_COUNTS = Pattern.compile("files checked: (\\d+), files with failures: (\\d+)");
    private static final Pattern MUTATE_COUNTS = Pattern.compile("seeded defects caught: (\\d+) / (\\d+)");

    private final PrintStream out;

    IgBatch(PrintStream out) {
        this.out = out;
    }

    // ------------------------------------------------------------------ discovery and config

    /** Folders under root that hold a package.json; the search does not descend into a package. */
    static List<Path> findPackages(Path root, int maxDepth) {
        List<Path> found = new ArrayList<>();
        walk(root.toAbsolutePath().normalize(), 0, maxDepth, found);
        return found;
    }

    private static void walk(Path d, int depth, int maxDepth, List<Path> found) {
        if (Files.isRegularFile(d.resolve("package.json"))) {
            found.add(d);
            return;
        }
        if (depth >= maxDepth) {
            return;
        }
        List<Path> kids = new ArrayList<>();
        try (Stream<Path> s = Files.list(d)) {
            s.forEach(kids::add);
        } catch (IOException e) {
            return;
        }
        kids.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path p : kids) {
            if (Files.isDirectory(p) && !SKIP_DIRS.contains(p.getFileName().toString())) {
                walk(p, depth + 1, maxDepth, found);
            }
        }
    }

    static String slugify(String s) {
        return Text.stripChar(s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("-+", "-"), '-');
    }

    private static String rstripSlash(String s) {
        return Text.rstripChars(s, "/");
    }

    /** ig-config.json content from a package.json. */
    static ObjectNode deriveConfig(Path pkgDir, JsonNode pkg, Path workIgDir) {
        String name = Json.firstNonEmpty(Json.text(pkg, "name"),
                pkgDir.getParent() == null ? "" : pkgDir.getParent().getFileName().toString());
        String version = Json.firstNonEmpty(Json.text(pkg, "version"), "0.0.0");
        String title = Json.firstNonEmpty(Json.text(pkg, "title"), name);
        String author = Json.text(pkg, "author");
        String url = rstripSlash(Json.firstNonEmpty(Json.text(pkg, "url"), Json.text(pkg, "canonical")));
        String canonical = rstripSlash(Json.firstNonEmpty(Json.text(pkg, "canonical"), Json.text(pkg, "url")));
        List<String> fhir = Json.strings(pkg, "fhirVersions");
        if (fhir.isEmpty() && !Json.text(pkg, "fhirVersion").isEmpty()) {
            fhir = List.of(Json.text(pkg, "fhirVersion"));
        }
        String lower = name.toLowerCase(Locale.ROOT);
        String shortName = lower.replaceFirst("^hl7\\.fhir\\.", "");
        String cit = (author.isEmpty() ? "" : author + ". ") + title + ", v" + version + "."
                + (url.isEmpty() ? "" : " " + url + "/");
        String src;
        try {
            src = workIgDir.toAbsolutePath().normalize().relativize(pkgDir.toAbsolutePath().normalize()).toString()
                    .replace('\\', '/');
        } catch (IllegalArgumentException e) {      // different drive on Windows
            src = pkgDir.toString();
        }
        ObjectNode cfg = Json.object();
        cfg.put("source_dir", src);
        cfg.put("work_dir", ".");
        cfg.put("ig_slug", slugify(shortName));
        cfg.put("ig_dir_name", slugify(lower.replace(".fhir.", ".") + "-" + version));
        cfg.put("ig_version", version);
        cfg.put("fhir_version", fhir.isEmpty() ? "Not specified in source" : fhir.get(0));
        cfg.put("citation", cit);
        cfg.put("ig_url", url.isEmpty() ? "" : url + "/");
        cfg.put("canonical_namespace", canonical.isEmpty() ? "" : canonical + "/");
        return cfg;
    }

    private static void writeJson(Path p, JsonNode node) {
        Json.writeText(p, Json.dumps(node, 1, false) + "\n");
    }

    /** Run a tool in-process; returns the exit code and fills {@code captured} with its output. */
    private interface Tool {
        int run(PrintStream ps);
    }

    private static int capture(Tool t, StringBuilder captured) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (PrintStream ps = new PrintStream(bos, true, StandardCharsets.UTF_8)) {
            int rc = t.run(ps);
            ps.flush();
            captured.append(bos.toString(StandardCharsets.UTF_8));
            return rc;
        }
    }

    private static String tail(String text, int n) {
        List<String> lines = new ArrayList<>();
        for (String l : text.strip().split("\n")) {
            if (!l.isBlank()) {
                lines.add(l);
            }
        }
        return String.join("\n    ", lines.subList(Math.max(0, lines.size() - n), lines.size()));
    }

    // ------------------------------------------------------------------ plan

    /** Balanced prose batches of about {@code size} resources in segment order; returns the manifest paths. */
    static List<String> makeBatches(Path factsDir, Path proseDir, Path batchDir, int size) {
        record Item(int seg, String id, String outFile, Path path) {
        }
        List<Item> items = new ArrayList<>();
        for (Path p : Renderer.factsFiles(factsDir)) {
            JsonNode d = Json.read(p);
            int idx = SEG_ORDER.indexOf(Json.text(d, "seg"));
            items.add(new Item(idx < 0 ? 99 : idx, Json.text(d, "id"), Json.text(d, "out_file"), p));
        }
        items.sort(Comparator.comparingInt(Item::seg).thenComparing(Item::id).thenComparing(Item::outFile)
                .thenComparing(i -> i.path().toString()));
        try {
            Files.createDirectories(batchDir);
            try (Stream<Path> s = Files.list(batchDir)) {
                for (Path old : (Iterable<Path>) s::iterator) {
                    String n = old.getFileName().toString();
                    if (n.startsWith("batch-") && n.endsWith(".txt")) {
                        Files.delete(old);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> manifests = new ArrayList<>();
        if (items.isEmpty()) {
            return manifests;
        }
        int n = Math.max(1, (int) Math.ceil(items.size() / (double) size));
        int base = items.size() / n;
        int extra = items.size() % n;
        int i = 0;
        for (int b = 0; b < n; b++) {
            int cnt = base + (b < extra ? 1 : 0);
            Path path = batchDir.resolve(String.format("batch-%02d.txt", b + 1));
            StringBuilder sb = new StringBuilder();
            for (Item it : items.subList(i, i + cnt)) {
                sb.append("- FACTS_FILE=").append(it.path()).append("  PROSE_FILE=")
                        .append(proseDir.resolve(it.outFile() + ".prose.json")).append('\n');
            }
            i += cnt;
            Json.writeText(path, sb.toString());
            manifests.add(path.toString());
        }
        return manifests;
    }

    int plan(Args a) {
        List<String> pos = a.positional();
        if (pos.isEmpty() || a.value("--work-root") == null) {
            throw new CliException("usage: batch plan ROOT --work-root WORK [--batch-size N] [--only NAME ...] "
                    + "[--overrides FILE] [--skip-types T1,T2]");
        }
        Path root = Path.of(pos.get(0)).toAbsolutePath().normalize();
        Path workRoot = Path.of(a.value("--work-root")).toAbsolutePath().normalize();
        int batchSize = a.value("--batch-size") == null ? 10 : Integer.parseInt(a.value("--batch-size"));
        Path ovPath = a.value("--overrides") != null ? Path.of(a.value("--overrides")) : root.resolve("ig-overrides.json");
        JsonNode overrides = Files.isRegularFile(ovPath) ? Json.read(ovPath) : Json.object();
        List<Path> pkgs = findPackages(root, 5);
        if (pkgs.isEmpty()) {
            out.println("no IG package found under " + root + " (an IG folder must contain a package.json)");
            return 1;
        }
        List<ObjectNode> plan = new ArrayList<>();
        Map<String, Path> seen = new HashMap<>();
        int bad = 0;
        for (Path pkgDir : pkgs) {
            JsonNode pkg;
            try {
                pkg = Json.read(pkgDir.resolve("package.json"));
            } catch (RuntimeException e) {
                out.println("SKIP " + pkgDir + ": package.json unreadable (" + e.getMessage() + ")");
                bad++;
                continue;
            }
            String name = Json.firstNonEmpty(Json.text(pkg, "name"), pkgDir.getFileName().toString());
            if (!a.values("--only").isEmpty() && !matchesOnly(a.values("--only"), name)) {
                continue;
            }
            String key = deriveConfig(pkgDir, pkg, workRoot).get("ig_dir_name").asText();
            ObjectNode entry = Json.object();
            entry.put("ig", key);
            entry.put("package", name);
            entry.put("package_dir", pkgDir.toString());
            entry.put("status", "planned");
            ArrayNode notes = entry.putArray("notes");
            if (seen.containsKey(key)) {
                entry.put("status", "skipped");
                notes.add("same name and version as " + seen.get(key));
                plan.add(entry);
                bad++;
                continue;
            }
            seen.put(key, pkgDir);
            Path w = workRoot.resolve(key);
            ObjectNode cfgNode = deriveConfig(pkgDir, pkg, w);
            JsonNode ov = overrides.get(name);
            if (ov != null && ov.isObject()) {
                cfgNode.setAll((ObjectNode) ov);
            }
            if (a.value("--skip-types") != null) {
                Set<String> types = new TreeSet<>(Json.strings(cfgNode, "skip_resource_types"));
                types.addAll(List.of(a.value("--skip-types").split(",")));
                cfgNode.set("skip_resource_types", Json.arrayOf(new ArrayList<>(types)));
            }
            Path cfgPath = w.resolve("ig-config.json");
            writeJson(cfgPath, cfgNode);
            Path factsDir = w.resolve("facts");
            Path proseDir = w.resolve("prose").resolve("run1");
            try {
                Files.createDirectories(proseDir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            entry.put("config", cfgPath.toString());
            entry.put("work_dir", w.toString());
            entry.put("facts_dir", factsDir.toString());
            entry.put("prose_dir", proseDir.toString());
            entry.put("out_dir", w.resolve("out").toString());
            entry.put("ig_name", Json.firstNonEmpty(Json.text(pkg, "title"), name) + " v" + cfgNode.get("ig_version").asText());
            entry.put("ig_slug", cfgNode.get("ig_slug").asText());
            entry.set("skip_types", Json.arrayOf(Json.strings(cfgNode, "skip_resource_types")));
            IgConfig cfg = IgConfig.load(cfgPath);
            StringBuilder o = new StringBuilder();
            int rc = capture(ps -> new FactsExtractor(cfg).run(new Args(List.of(factsDir.toString()), Set.of()), ps), o);
            if (rc != 0) {
                Set<String> types = new TreeSet<>();
                Matcher m = UNSUPPORTED.matcher(o);
                while (m.find()) {
                    types.add(m.group(1));
                }
                if (rc == 2 && !types.isEmpty()) {
                    entry.put("status", "blocked: unsupported resource types");
                    String list = String.join(",", types);
                    notes.add("types: " + String.join(", ", types) + ". Re-run plan with --skip-types " + list
                            + " to skip them on purpose, or add them to skip_resource_types in ig-overrides.json");
                } else {
                    entry.put("status", "blocked: extract exit " + rc);
                    notes.add(tail(o.toString(), 6));
                }
                bad++;
                plan.add(entry);
                continue;
            }
            entry.put("resources", Renderer.factsFiles(factsDir).size());
            entry.set("batches", Json.arrayOf(makeBatches(factsDir, proseDir, w.resolve("batches"), batchSize)));
            plan.add(entry);
        }
        ArrayNode planJson = Json.F.arrayNode();
        plan.forEach(planJson::add);
        writeJson(workRoot.resolve("plan.json"), planJson);
        printPlan(plan);
        out.println("\nplan written: " + workRoot.resolve("plan.json"));
        long ready = plan.stream().filter(p -> p.get("status").asText().equals("planned")).count();
        out.println(ready + " IG(s) ready for prose, " + (plan.size() - ready) + " blocked or skipped. Next: run one "
                + "prose agent per manifest in each IG's batches folder (see SKILL.md), then: batch finish "
                + "--work-root \"" + workRoot + "\"");
        return (bad > 0 || ready == 0) ? 1 : 0;
    }

    private static boolean matchesOnly(List<String> only, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String o : only) {
            String ol = o.toLowerCase(Locale.ROOT);
            if (ol.equals(lower) || ol.equals(slugify(name)) || lower.contains(ol)) {
                return true;
            }
        }
        return false;
    }

    private void printPlan(List<ObjectNode> plan) {
        out.println(String.format("%-28s %-9s %-8s %s", "IG", "RESOURCES", "BATCHES", "STATUS"));
        for (ObjectNode p : plan) {
            out.println(String.format("%-28s %-9s %-8s %s", p.get("ig").asText(),
                    p.has("resources") ? p.get("resources").asText() : "-",
                    p.has("batches") ? String.valueOf(p.get("batches").size()) : "-", p.get("status").asText()));
            for (JsonNode n : p.get("notes")) {
                out.println("    " + n.asText());
            }
        }
    }

    // ------------------------------------------------------------------ status / finish

    private static List<ObjectNode> loadPlan(String workRoot) {
        Path path = Path.of(workRoot).toAbsolutePath().resolve("plan.json");
        if (!Files.isRegularFile(path)) {
            throw new CliException("no plan.json in " + workRoot + "; run \"plan\" first");
        }
        List<ObjectNode> out = new ArrayList<>();
        Json.read(path).forEach(n -> out.add((ObjectNode) n));
        return out;
    }

    private record Progress(int total, int have, List<String> missing) {
    }

    private static Progress proseProgress(JsonNode p) {
        List<String> missing = new ArrayList<>();
        int total = 0;
        for (Path f : Renderer.factsFiles(Path.of(p.get("facts_dir").asText()))) {
            String n = f.getFileName().toString();
            String base = n.substring(0, n.length() - ".facts.json".length());
            total++;
            if (!Files.isRegularFile(Path.of(p.get("prose_dir").asText()).resolve(base + ".prose.json"))) {
                missing.add(base);
            }
        }
        return new Progress(total, total - missing.size(), missing);
    }

    int status(Args a) {
        if (a.value("--work-root") == null) {
            throw new CliException("usage: batch status --work-root WORK");
        }
        out.println(String.format("%-28s %-10s %s", "IG", "PROSE", "STATUS"));
        for (ObjectNode p : loadPlan(a.value("--work-root"))) {
            if (!p.get("status").asText().equals("planned")) {
                out.println(String.format("%-28s %-10s %s", p.get("ig").asText(), "-", p.get("status").asText()));
                continue;
            }
            Progress pr = proseProgress(p);
            out.println(String.format("%-28s %-10s %s", p.get("ig").asText(), pr.have() + "/" + pr.total(),
                    pr.missing().isEmpty() ? "complete" : "waiting for " + pr.missing().size()));
        }
        return 0;
    }

    private static String deliver(JsonNode p, String outRoot, boolean overwrite) {
        Path dest = Path.of(outRoot).toAbsolutePath().resolve(p.get("ig").asText());
        try {
            if (Files.isDirectory(dest)) {
                try (Stream<Path> s = Files.list(dest)) {
                    if (s.findAny().isPresent() && !overwrite) {
                        return "NOT delivered: " + dest + " exists and is not empty (use --overwrite)";
                    }
                }
            }
            Files.createDirectories(dest);
            if (overwrite) {
                try (Stream<Path> s = Files.list(dest)) {
                    for (Path old : (Iterable<Path>) s::iterator) {
                        if (old.getFileName().toString().endsWith(".adoc")) {
                            Files.delete(old);
                        }
                    }
                }
            }
            try (Stream<Path> s = Files.list(Path.of(p.get("out_dir").asText()))) {
                for (Path f : (Iterable<Path>) s::iterator) {
                    if (f.getFileName().toString().endsWith(".adoc")) {
                        Files.copy(f, dest.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return "delivered to " + dest;
    }

    int finish(Args a) {
        if (a.value("--work-root") == null) {
            throw new CliException("usage: batch finish --work-root WORK [--only NAME ...] [--mutate] [--allow-stub] "
                    + "[--date YYYY-MM-DD] [--deliver OUT_ROOT [--overwrite]]");
        }
        List<ObjectNode> plan = loadPlan(a.value("--work-root"));
        String date = a.value("--date") != null ? a.value("--date") : LocalDate.now().toString();
        boolean stub = a.flag("--allow-stub");
        List<Map<String, String>> rows = new ArrayList<>();
        int worst = 0;
        for (ObjectNode p : plan) {
            String ig = p.get("ig").asText();
            if (!a.values("--only").isEmpty()
                    && a.values("--only").stream().noneMatch(o -> ig.toLowerCase(Locale.ROOT).contains(o.toLowerCase(Locale.ROOT))
                    || p.get("package").asText().toLowerCase(Locale.ROOT).contains(o.toLowerCase(Locale.ROOT)))) {
                continue;
            }
            Map<String, String> row = new java.util.LinkedHashMap<>();
            row.put("ig", ig);
            row.put("resources", p.has("resources") ? p.get("resources").asText() : "-");
            row.put("lint", "-");
            row.put("corpus", "-");
            row.put("mutate", "-");
            row.put("result", "");
            rows.add(row);
            if (!p.get("status").asText().equals("planned")) {
                row.put("result", p.get("status").asText());
                worst = 1;
                continue;
            }
            Progress pr = proseProgress(p);
            if (!pr.missing().isEmpty() && !stub) {
                row.put("result", "prose incomplete (" + pr.have() + "/" + pr.total() + ")");
                worst = 1;
                continue;
            }
            Path cfgPath = Path.of(p.get("config").asText());
            String facts = p.get("facts_dir").asText();
            String prose = p.get("prose_dir").asText();
            Path outDir = Path.of(p.get("out_dir").asText());
            IgConfig cfg = IgConfig.load(cfgPath);
            Renderer renderer = new Renderer(cfg);
            clearFolder(outDir);
            StringBuilder o = new StringBuilder();
            List<String> renderArgs = new ArrayList<>(List.of(facts, prose, outDir.toString()));
            if (stub) {
                renderArgs.add("--stub");
            }
            int rc = capture(ps -> renderer.run(new Args(renderArgs, Set.of()), ps), o);
            if (rc != 0) {
                row.put("result", "render failed: " + tail(o.toString(), 3));
                worst = 1;
                continue;
            }
            Linter linter = new Linter(cfg, renderer);
            List<String> lintArgs = new ArrayList<>(List.of(outDir.toString(), facts, prose));
            if (stub) {
                lintArgs.add("--allow-stub");
            }
            o = new StringBuilder();
            int rcL = capture(ps -> linter.run(new Args(lintArgs, Set.of()), ps), o);
            Matcher lm = LINT_COUNTS.matcher(o);
            row.put("lint", rcL == 0 ? "ok" : (lm.find() ? lm.group(2) : "None") + " failing");
            if (rcL != 0) {
                Path report = Path.of(p.get("work_dir").asText()).resolve("lint-report.txt");
                row.put("result", "lint failed (see " + report + ")");
                Json.writeText(report, o.toString());
            }
            o = new StringBuilder();
            int rcC = capture(ps -> new CorpusChecker(cfg, renderer).run(
                    new Args(List.of(outDir.toString(), facts, "--write-includes", date), Set.of("--write-includes")), ps), o);
            row.put("corpus", rcC == 0 ? "ok" : "errors");
            if (rcC != 0 && row.get("result").isEmpty()) {
                row.put("result", "corpus check failed: " + tail(o.toString(), 3));
            }
            if (a.flag("--mutate") && rcL == 0 && rcC == 0) {
                o = new StringBuilder();
                int rcM = capture(ps -> new Mutator(cfg, linter).run(
                        new Args(List.of(outDir.toString(), facts, prose), Set.of()), ps), o);
                Matcher mm = MUTATE_COUNTS.matcher(o);
                row.put("mutate", mm.find() ? mm.group(1) + "/" + mm.group(2) : "error");
                if (rcM != 0 && row.get("result").isEmpty()) {
                    row.put("result", "seeded-defect test failed");
                }
            }
            if (row.get("result").isEmpty()) {
                String res = "passed";
                if (a.value("--deliver") != null) {
                    res += "; " + deliver(p, a.value("--deliver"), a.flag("--overwrite"));
                }
                row.put("result", res);
            } else {
                worst = 1;
            }
        }
        out.println(String.format("%-28s %-9s %-9s %-8s %-9s %s", "IG", "RESOURCES", "LINT", "CORPUS", "FAULTS", "RESULT"));
        for (Map<String, String> r : rows) {
            out.println(String.format("%-28s %-9s %-9s %-8s %-9s %s", r.get("ig"), r.get("resources"), r.get("lint"),
                    r.get("corpus"), r.get("mutate"), r.get("result")));
        }
        return worst;
    }

    private static void clearFolder(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path old : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(old)) {
                    Files.delete(old);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Subcommand dispatch: {@code plan}, {@code status}, {@code finish}. */
    static int main(List<String> argv, PrintStream out) {
        if (argv.isEmpty()) {
            throw new CliException("usage: batch plan|status|finish ...");
        }
        String cmd = argv.get(0);
        List<String> rest = argv.subList(1, argv.size());
        Set<String> valueOpts = new LinkedHashSet<>(List.of("--work-root", "--batch-size", "--only", "--overrides",
                "--skip-types", "--date", "--deliver"));
        Args a = new Args(rest, valueOpts, Set.of("--only"));
        IgBatch b = new IgBatch(out);
        return switch (cmd) {
            case "plan" -> b.plan(a);
            case "status" -> b.status(a);
            case "finish" -> b.finish(a);
            default -> throw new CliException("unknown batch command: " + cmd + " (plan, status, finish)");
        };
    }
}
