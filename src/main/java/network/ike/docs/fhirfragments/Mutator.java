package network.ike.docs.fhirfragments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seeded-defect (mutation) test for the validators. Each fault is applied to up to three applicable files
 * (first, middle, last) and the run reports whether a validator layer caught it. Faults that apply to no file are
 * NOT APPLICABLE and not counted. Success needs every applied fault caught and every unmutated file clean.
 */
final class Mutator {
    private static final String NL = "\n";
    private static final String BS = "\\";
    private static final Pattern UNESCAPED_PIPE = Pattern.compile("(?<!\\\\)\\|");

    private final IgConfig cfg;
    private final Linter linter;

    Mutator(IgConfig cfg, Linter linter) {
        this.cfg = cfg;
        this.linter = linter;
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> cells(String line) {
        return new ArrayList<>(List.of(UNESCAPED_PIPE.split(line.substring(1), -1)));
    }

    private static String rowOf(List<String> cells) {
        return "|" + String.join("|", cells);
    }

    /** Apply {@code fn(rowLine)} to the first data row of the first table in {@code == section}; null if none. */
    static String subFirstRow(String text, String section, Function<String, String> fn) {
        List<String> lines = Text.lines(text);
        boolean inSec = false;
        int state = 0;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.startsWith("== ")) {
                inSec = l.substring(3).equals(section);
                state = 0;
            }
            if (!inSec) {
                continue;
            }
            if (l.equals("|===")) {
                state++;
                continue;
            }
            if (state == 1 && l.startsWith("|")) {
                if (lines.get(i - 1).equals("|===")) {
                    continue;      // header row
                }
                String n = fn.apply(l);
                if (n == null) {
                    return null;
                }
                lines.set(i, n);
                return String.join(NL, lines);
            }
        }
        return null;
    }

    private static String firstOf(String t, Function<String, String> fn, String... sections) {
        for (String sec : sections) {
            String r = subFirstRow(t, sec, fn);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private static String sub1(String t, String regex, Function<java.util.regex.MatchResult, String> repl, int flags) {
        return Text.re(regex, flags).matcher(t).replaceFirst(mr -> Matcher.quoteReplacement(repl.apply(mr)));
    }

    // ------------------------------------------------------------------ text mutations (null = not applicable)

    private static String minChanged(String t) {
        return subFirstRow(t, "Conformance Table", l -> {
            List<String> c = cells(l);
            if (c.size() < 3) {
                return null;
            }
            String v = Text.strip(c.get(1));
            if (v.isEmpty() || !v.chars().allMatch(Character::isDigit)) {
                return null;
            }
            c.set(1, !v.equals("0") ? " 0 " : " 1 ");
            return rowOf(c);
        });
    }

    private static String strengthSwapped(String t) {
        Map<String, String> swap = Map.of("required", "example", "extensible", "required", "preferred", "required",
                "example", "required");
        return subFirstRow(t, "Terminology Bindings", l -> {
            List<String> c = cells(l);
            if (c.size() < 3 || !swap.containsKey(Text.strip(c.get(1)))) {
                return null;
            }
            c.set(1, " " + swap.get(Text.strip(c.get(1))) + " ");
            return rowOf(c);
        });
    }

    private static String bindingRowDropped(String t) {
        List<String> out = new ArrayList<>();
        boolean inSec = false;
        boolean done = false;
        int state = 0;
        for (String l : Text.lines(t)) {
            if (l.startsWith("== ")) {
                inSec = l.substring(3).equals("Terminology Bindings");
                state = 0;
            }
            if (inSec && l.equals("|===")) {
                state++;
            } else if (inSec && state == 1 && l.startsWith("|") && !done && !l.startsWith("|Element")) {
                done = true;
                continue;
            }
            out.add(l);
        }
        return done ? String.join(NL, out) : null;
    }

    private static String codeChanged(String t) {
        return firstOf(t, l -> {
            List<String> c = cells(l);
            if (c.isEmpty() || Text.strip(c.get(0)).isEmpty()) {
                return null;
            }
            c.set(0, Text.rstrip(c.get(0)) + "X ");
            return rowOf(c);
        }, "Concepts", "Compose");
    }

    private static String inventedUrl(String t) {
        return t.contains("|Canonical URL |")
                ? Text.replaceOnce(t, "|Canonical URL |", "|Canonical URL |http://example.org/invented/x ") : null;
    }

    private static String inventedOid(String t) {
        return t.contains("|Version |") ? Text.replaceOnce(t, "|Version |", "|Version |1.2.3.4.5.6 ") : null;
    }

    private static String sectionDeleted(String t) {
        return t.contains("== Identity")
                ? Text.re("^== Identity.*?(?=^== )", Pattern.MULTILINE | Pattern.DOTALL).matcher(t).replaceFirst("")
                : null;
    }

    private static final Pattern SECTION_START = Text.re("^(?=== )", Pattern.MULTILINE);

    private static String sectionsSwapped(String t) {
        List<String> parts = Text.splitBefore(SECTION_START, t);
        List<Integer> secs = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).startsWith("== ")) {
                secs.add(i);
            }
        }
        if (secs.size() < 4) {
            return null;
        }
        int a = secs.get(1);
        int b = secs.get(2);
        String tmp = parts.get(a);
        parts.set(a, parts.get(b));
        parts.set(b, tmp);
        return String.join("", parts);
    }

    private static String attrOrder(String t) {
        List<String> lines = Text.lines(t);
        int i = -1;
        int j = -1;
        for (int k = 0; k < lines.size(); k++) {
            if (i < 0 && lines.get(k).startsWith(":topic-summary:")) {
                i = k;
            }
            if (j < 0 && lines.get(k).startsWith(":topic-canonical:")) {
                j = k;
            }
        }
        if (i < 0 || j < 0) {
            return null;
        }
        String tmp = lines.get(i);
        lines.set(i, lines.get(j));
        lines.set(j, tmp);
        return String.join(NL, lines);
    }

    private static String citationWrapped(String t) {
        return sub1(t, "^(:topic-citation: \\S+(?: \\S+){5}) ", m -> m.group(1) + " " + BS + NL + "  ",
                Pattern.MULTILINE);
    }

    private static String crlf(String t) {
        return t.replace(NL, "\r\n");
    }

    private static String bom(String t) {
        return "\uFEFF" + t;
    }

    private static String doubleBlank(String t) {
        return Text.replaceOnce(t, NL + "[[", NL + NL + "[[");
    }

    private static String anchorMismatch(String t) {
        return sub1(t, "^\\[\\[(.+?)\\]\\]$", m -> "[[" + m.group(1) + "-x]]", Pattern.MULTILINE);
    }

    private static String extraColumn(String t) {
        return firstOf(t, l -> l + " |extra", "Conformance Table", "Compose", "Concepts", "Parameters",
                "Conformance Expectations", "Extension Structure", "Identity");
    }

    private static String unescapedPipe(String t) {
        return firstOf(t, l -> {
            List<String> c = cells(l);
            c.set(0, Text.rstrip(c.get(0)) + " a|b ");
            return rowOf(c);
        }, "Identity and Derivation", "Identity", "Conformance Expectations");
    }

    private static String comparatorChanged(String t) {
        return subFirstRow(t, "Conformance Expectations", l -> {
            String[][] pairs = {{"SHALL", "MAY"}, {"MAY", "SHALL"}, {"SHOULD", "SHALL"}};
            for (String[] ab : pairs) {
                if (l.contains(ab[0])) {
                    return Text.replaceOnce(l, ab[0], ab[1]);
                }
            }
            return null;
        });
    }

    private static String titleChanged(String t) {
        return sub1(t, "^= (.+)$", m -> "= " + m.group(1) + " (edited)", Pattern.MULTILINE);
    }

    private static String trailingBlank(String t) {
        return t + NL;
    }

    private static String canonicalWrong(String t) {
        return sub1(t, "^(:topic-canonical: .*?)([A-Za-z0-9-])$", m -> m.group(1) + "Z", Pattern.MULTILINE);
    }

    /** A hand edit inside a deterministic section (the Publishing Metadata date). */
    private static String dateEdited(String t) {
        return sub1(t, "^\\|Date \\|(\\d{4})", m -> "|Date |" + (Integer.parseInt(m.group(1)) + 1), Pattern.MULTILINE);
    }

    private static String summaryEdited(String t) {
        return sub1(t, "^(:topic-summary:) ", m -> m.group(1) + " Hand-edited words. ", Pattern.MULTILINE);
    }

    private static String keywordsEdited(String t) {
        return sub1(t, "^(:topic-keywords: .*)$", m -> m.group(1) + ", hand-added", Pattern.MULTILINE);
    }

    private static String overviewTextAdded(String t) {
        return Text.replaceOnce(t, "== Overview" + NL + NL, "== Overview" + NL + NL + "Invented sentence added by hand. ");
    }

    /** Replace the {topic-summary} reference in the Overview by a pasted copy of the summary text. */
    private static String overviewCopyOfSummary(String t) {
        Matcher m = Text.re("^:topic-summary: (.*?)\n:topic-canonical:", Pattern.MULTILINE | Pattern.DOTALL).matcher(t);
        if (!m.find() || !t.contains("== Overview" + NL + NL + "{topic-summary}")) {
            return null;
        }
        List<String> bits = new ArrayList<>();
        for (String x : m.group(1).split(NL, -1)) {
            bits.add(Text.strip(Text.rstripChars(Text.strip(x), BS)));
        }
        return Text.replaceOnce(t, "{topic-summary}", String.join(" ", bits));
    }

    private static String overviewReferenceRemoved(String t) {
        return Text.replaceOnce(t, "== Overview" + NL + NL + "{topic-summary}",
                "== Overview" + NL + NL + "Not specified in source");
    }

    /** Join two URLs of one table cell with a comma instead of a line break. */
    private static String commaSeparatedUrls(String t) {
        String n = Text.re("^(http\\S*) \\+\n(http)", Pattern.MULTILINE).matcher(t).replaceFirst("$1, $2");
        return n.equals(t) ? null : n;
    }

    private static String missingRequiredSection(String t) {
        return t.contains("== Publishing Metadata")
                ? Text.re("^== Publishing Metadata.*\\z", Pattern.MULTILINE | Pattern.DOTALL).matcher(t)
                .replaceFirst("") : null;
    }

    private record Mutation(String name, Function<String, String> fn) {
    }

    private static final List<Mutation> MUTATIONS = List.of(
            new Mutation("min cardinality changed", Mutator::minChanged),
            new Mutation("binding strength changed", Mutator::strengthSwapped),
            new Mutation("binding row dropped", Mutator::bindingRowDropped),
            new Mutation("code changed", Mutator::codeChanged),
            new Mutation("invented URL in a table", Mutator::inventedUrl),
            new Mutation("invented OID-like number", Mutator::inventedOid),
            new Mutation("section deleted", Mutator::sectionDeleted),
            new Mutation("sections swapped", Mutator::sectionsSwapped),
            new Mutation("attribute order swapped", Mutator::attrOrder),
            new Mutation("citation wrapped", Mutator::citationWrapped),
            new Mutation("CRLF line endings", Mutator::crlf),
            new Mutation("BOM added", Mutator::bom),
            new Mutation("double blank before anchor", Mutator::doubleBlank),
            new Mutation("anchor mismatch", Mutator::anchorMismatch),
            new Mutation("table row extra column", Mutator::extraColumn),
            new Mutation("unescaped pipe in cell", Mutator::unescapedPipe),
            new Mutation("comparator expectation changed", Mutator::comparatorChanged),
            new Mutation("title changed", Mutator::titleChanged),
            new Mutation("extra trailing newline", Mutator::trailingBlank),
            new Mutation("canonical value changed", Mutator::canonicalWrong),
            new Mutation("Publishing Metadata date edited", Mutator::dateEdited),
            new Mutation("Publishing Metadata section removed", Mutator::missingRequiredSection),
            new Mutation("summary edited by hand", Mutator::summaryEdited),
            new Mutation("keywords edited by hand", Mutator::keywordsEdited),
            new Mutation("text added to Overview by hand", Mutator::overviewTextAdded),
            new Mutation("Overview holds a pasted copy of the summary", Mutator::overviewCopyOfSummary),
            new Mutation("Overview reference removed", Mutator::overviewReferenceRemoved),
            new Mutation("URLs comma-separated in a table cell", Mutator::commaSeparatedUrls));

    // ------------------------------------------------------------------ prose faults (L4)

    private static ObjectNode proseBase() {
        ObjectNode p = Json.object();
        p.put("id", "x");
        p.put("resource_type", "ValueSet");
        p.set("keywords", Json.arrayOf(List.of("terminology", "category")));
        p.put("summary", "Defines the value set of observation categories allowed for screening results.");
        return p;
    }

    private record ProseFault(String name, Function<ObjectNode, ObjectNode> apply) {
    }

    private List<ProseFault> proseFaults() {
        return List.of(
                new ProseFault("prose: fixed keyword included", p -> with(p, "keywords", kw(cfg.igSlug, "category"))),
                new ProseFault("prose: uppercase keyword", p -> with(p, "keywords", kw("Category", "terminology"))),
                new ProseFault("prose: only one keyword", p -> with(p, "keywords", kw("category"))),
                new ProseFault("prose: summary too short", p -> with(p, "summary", "Defines categories.")),
                new ProseFault("prose: summary two sentences", p -> with(p, "summary",
                        "Defines the value set of observation categories allowed. It is used for screening results here.")),
                new ProseFault("prose: summary with markdown", p -> with(p, "summary",
                        "Defines the value set of *observation* categories allowed for screening results.")),
                new ProseFault("prose: summary with pipe", p -> with(p, "summary",
                        "Defines the value set of observation | categories allowed for screening results.")),
                new ProseFault("prose: summary with em dash", p -> with(p, "summary",
                        "Defines the value set of observation categories \u2014 allowed for screening results.")),
                new ProseFault("prose: banned opening phrase", p -> with(p, "summary",
                        "This resource defines the value set of observation categories for screening.")),
                new ProseFault("prose: unexpected key", p -> with(p, "extra", "x")),
                new ProseFault("prose: leftover overview_lead key", p -> with(p, "overview_lead", "")),
                new ProseFault("prose: missing key", p -> {
                    p.remove("summary");
                    return p;
                }));
    }

    private static JsonNode kw(String... k) {
        return Json.arrayOf(List.of(k));
    }

    private static ObjectNode with(ObjectNode p, String key, JsonNode v) {
        p.set(key, v);
        return p;
    }

    private static ObjectNode with(ObjectNode p, String key, String v) {
        p.put(key, v);
        return p;
    }

    // ------------------------------------------------------------------ run

    private record Cached(String text, String srcText, JsonNode src, JsonNode prose) {
    }

    /** {@code mutate --config CFG OUT_DIR FACTS_DIR [PROSE_DIR]}. */
    int run(Args args, PrintStream out) {
        List<String> pos = args.positional();
        if (pos.size() < 2) {
            throw new CliException("usage: mutate --config CFG OUT_DIR FACTS_DIR [PROSE_DIR]");
        }
        Path outDir = Path.of(pos.get(0));
        Path factsDir = Path.of(pos.get(1));
        Path proseDir = pos.size() > 2 ? Path.of(pos.get(2)) : null;
        List<JsonNode> files = new ArrayList<>();
        for (Path fp : Renderer.factsFiles(factsDir)) {
            files.add(Json.read(fp));
        }
        Map<String, Cached> cache = new HashMap<>();
        int[] baselineBad = {0};

        Function<JsonNode, Cached> get = f -> cache.computeIfAbsent(Json.text(f, "out_file"), k -> {
            String srcText = Json.readText(cfg.srcDir.resolve(Json.text(f, "source_file")));
            Path pp = proseDir == null ? null : proseDir.resolve(k + ".prose.json");
            JsonNode prose = (pp != null && Files.exists(pp)) ? Json.read(pp) : Renderer.stubProse(f);
            String text = Linter.readExact(outDir.resolve(k));
            JsonNode src = Json.parse(srcText);
            Map<String, List<String>> base = linter.runChecks(k, text, f, srcText, src, prose);
            if (base.values().stream().anyMatch(v -> !v.isEmpty())) {
                baselineBad[0]++;
                Map<String, String> first = new java.util.LinkedHashMap<>();
                base.forEach((layer, v) -> {
                    if (!v.isEmpty()) {
                        first.put(layer, v.get(0));
                    }
                });
                out.println("BASELINE FAILS (unmutated file) " + k + ": " + first);
            }
            return new Cached(text, srcText, src, prose);
        });

        int caught = 0;
        int total = 0;
        int notApplicable = 0;
        out.println(String.format("%-40s %-45s %s", "mutation", "applied to", "caught by"));
        for (Mutation mu : MUTATIONS) {
            record Hit(JsonNode facts, String text, Cached c) {
            }
            List<Hit> applicable = new ArrayList<>();
            for (JsonNode f : files) {
                Cached c = get.apply(f);
                String nt = mu.fn().apply(c.text());
                if (nt == null || nt.equals(c.text())) {
                    continue;
                }
                applicable.add(new Hit(f, nt, c));
            }
            if (applicable.isEmpty()) {
                notApplicable++;
                out.println(String.format("%-40s %-45s %s", mu.name(), "-", "NOT APPLICABLE (no file)"));
                continue;
            }
            int n = applicable.size();
            for (int k : new TreeSet<>(List.of(0, n / 2, n - 1))) {
                Hit h = applicable.get(k);
                String outFile = Json.text(h.facts(), "out_file");
                Map<String, List<String>> res = linter.runChecks(outFile, h.text(), h.facts(), h.c().srcText(),
                        h.c().src(), h.c().prose());
                List<String> layers = new ArrayList<>();
                res.forEach((layer, v) -> {
                    if (!v.isEmpty()) {
                        layers.add(layer);
                    }
                });
                total++;
                if (!layers.isEmpty()) {
                    caught++;
                }
                out.println(String.format("%-40s %-45s %s", mu.name(), Text.head(outFile, 45),
                        layers.isEmpty() ? "*** MISSED ***" : String.join(", ", layers)));
            }
        }
        for (ProseFault pf : proseFaults()) {
            List<String> errs = linter.checkProseObj(pf.apply().apply(proseBase()));
            total++;
            if (!errs.isEmpty()) {
                caught++;
            }
            out.println(String.format("%-40s %-45s %s", pf.name(), "-", errs.isEmpty() ? "*** MISSED ***" : "L4 prose"));
        }
        List<String> sane = linter.checkProseObj(proseBase());
        if (!sane.isEmpty()) {
            throw new IllegalStateException("unmutated prose must pass: " + sane);
        }
        out.println("seeded defects caught: " + caught + " / " + total + " (not applicable: " + notApplicable
                + ", unmutated files failing: " + baselineBad[0] + ")");
        return caught == total && baselineBad[0] == 0 ? 0 : 1;
    }
}
