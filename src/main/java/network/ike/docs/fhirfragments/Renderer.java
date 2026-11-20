package network.ike.docs.fhirfragments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * facts.json + prose.json to an {@code .adoc} topic fragment. Fully deterministic: the only free text is the
 * prose's keywords and one-sentence summary (see render-spec.md).
 */
final class Renderer {
    static final String OVERVIEW_REF = "{topic-summary}";

    static final Map<String, String> RT_KEBAB = Map.of(
            "StructureDefinition", "structuredefinition", "SearchParameter", "searchparameter",
            "ValueSet", "valueset", "CodeSystem", "codesystem", "CapabilityStatement", "capabilitystatement",
            "OperationDefinition", "operationdefinition");

    private final IgConfig cfg;

    Renderer(IgConfig cfg) {
        this.cfg = cfg;
    }

    // ------------------------------------------------------------------ building blocks

    /** Fallback text for a missing value. */
    private static String fb(String v) {
        return (v == null || v.isEmpty()) ? Text.NOT_SPEC : v;
    }

    private static String fb(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode() || (v.isArray() && v.size() == 0)) {
            return Text.NOT_SPEC;
        }
        return fb(Json.str(v));
    }

    private static String mm(JsonNode v) {
        String s = Json.strOrNull(v);
        return s == null ? Text.NOT_SPEC : s;
    }

    private static String table(List<String> headers, List<String[]> rows) {
        int n = headers.size();
        List<String> out = new ArrayList<>();
        out.add("[cols=\"" + n + "*\",options=\"header\"]");
        out.add("|===");
        out.add("|" + String.join(" |", headers));
        for (String[] r : rows) {
            if (r.length != n) {
                throw new IllegalStateException("table row has " + r.length + " cells, expected " + n);
            }
            List<String> cells = new ArrayList<>();
            for (String c : r) {
                cells.add(Text.cell(c));
            }
            out.add("|" + String.join(" |", cells));
        }
        out.add("|===");
        return String.join("\n", out);
    }

    private static String[] row(String... cells) {
        return cells;
    }

    private static List<String> h(String... headers) {
        return List.of(headers);
    }

    /** Two-column Property/Value table; a missing (null or empty) value reads "Not specified in source". */
    private static String props(List<String[]> rows) {
        List<String> out = new ArrayList<>();
        out.add("[cols=\"1,3\",options=\"header\"]");
        out.add("|===");
        out.add("|Property |Value");
        for (String[] kv : rows) {
            out.add("|" + Text.cell(kv[0]) + " |" + Text.cell(fb(kv[1])));
        }
        out.add("|===");
        return String.join("\n", out);
    }

    private static String[] kv(String k, String v) {
        return new String[]{k, v};
    }

    private static String section(int level, String title, String... blocks) {
        List<String> parts = new ArrayList<>();
        parts.add("=".repeat(level) + " " + title);
        parts.add("");
        for (String b : blocks) {
            if (b != null && !b.isEmpty()) {
                parts.add(b);
                parts.add("");
            }
        }
        return String.join("\n", parts);
    }

    private static String section(String title, String... blocks) {
        return section(2, title, blocks);
    }

    /** The Overview only references the :topic-summary: attribute; the summary is written once, in the header. */
    private static String overview() {
        return section("Overview", OVERVIEW_REF);
    }

    private static String publishing(JsonNode f) {
        JsonNode p = f.get("publishing");
        return section("Publishing Metadata", props(List.of(
                kv("Publisher", Json.text(p, "publisher")), kv("Contact", Json.text(p, "contact")),
                kv("Date", Json.text(p, "date")), kv("Canonical URL", Json.text(p, "url")))));
    }

    // ------------------------------------------------------------------ bodies by segment

    private static List<String> bodyProfile(JsonNode f) {
        JsonNode m = f.get("meta");
        JsonNode d = f.get("data");
        String ident = props(List.of(
                kv("Id", Json.text(m, "id")), kv("URL", Json.text(m, "url")), kv("Version", Json.text(m, "version")),
                kv("Name", Json.text(m, "name")), kv("Title", Json.text(m, "title")),
                kv("Status", Json.text(m, "status")), kv("Date", Json.text(m, "date")),
                kv("Publisher", Json.text(m, "publisher")), kv("FHIR version", Json.text(m, "fhir_version")),
                kv("Base definition", Json.text(m, "base_definition")), kv("Kind", Json.text(m, "kind")),
                kv("Type", Json.text(m, "type")), kv("Derivation", Json.text(m, "derivation")),
                kv("FMM", Json.strOrNull(m.get("fmm"))), kv("Working group", Json.strOrNull(m.get("wg"))),
                kv("Standards status", Json.strOrNull(m.get("standards_status")))));
        List<String[]> rows = new ArrayList<>();
        for (JsonNode r : d.get("rows")) {
            List<String> notes = new ArrayList<>();
            if (!Json.text(r, "slice").isEmpty()) {
                notes.add("slice: " + Json.text(r, "slice"));
            }
            if (!Json.text(r, "types").isEmpty()) {
                notes.add("type: " + Json.text(r, "types"));
            }
            if (!Json.text(r, "short").isEmpty()) {
                notes.add(Json.text(r, "short"));
            }
            List<String> fromSnap = Json.strings(r, "from_snapshot");
            if (!fromSnap.isEmpty()) {
                notes.add(String.join("/", fromSnap) + " from snapshot");
            }
            rows.add(row(Json.text(r, "id"), mm(r.get("min")), mm(r.get("max")),
                    Json.truthy(r.get("ms")) ? "Y" : "", String.join("; ", notes)));
        }
        List<String> secs = new ArrayList<>();
        secs.add(overview());
        secs.add(section("Identity and Derivation", ident));
        secs.add(section("Conformance Table", table(h("Element", "Min", "Max", "MS", "Notes"), rows)));
        if (d.get("bindings").size() > 0) {
            List<String[]> br = new ArrayList<>();
            for (JsonNode b : d.get("bindings")) {
                String vs = Json.text(b, "value_set");
                br.add(row(Json.text(b, "element"), Json.text(b, "strength"),
                        (vs.isEmpty() ? Text.NOT_SPEC : vs) + (Json.truthy(b.get("from_snapshot")) ? " (from snapshot)" : "")));
            }
            secs.add(section("Terminology Bindings", table(h("Element", "Strength", "Value Set URL"), br)));
        }
        if (d.get("fixed").size() > 0) {
            List<String[]> fr = new ArrayList<>();
            for (JsonNode x : d.get("fixed")) {
                fr.add(row(Json.text(x, "element"), Json.text(x, "kind"), Json.text(x, "value")));
            }
            secs.add(section("Fixed Codes and Pattern Constraints", table(h("Element", "Constraint", "Value"), fr)));
        }
        if (d.get("constraints").size() > 0) {
            List<String[]> cr = new ArrayList<>();
            for (JsonNode c : d.get("constraints")) {
                cr.add(row(Json.text(c, "key"), Json.text(c, "severity"), Json.text(c, "human"),
                        Json.text(c, "expression")));
            }
            secs.add(section("Invariants", table(h("Key", "Severity", "Human-readable", "FHIRPath Expression"), cr)));
        }
        if (d.get("uscdi").size() > 0) {
            List<String[]> ur = new ArrayList<>();
            for (JsonNode u : d.get("uscdi")) {
                ur.add(row(Json.text(u, "element"), Json.text(u, "slice"), mm(u.get("min")), mm(u.get("max")),
                        Json.text(u, "profile_url")));
            }
            secs.add(section("USCDI Extensions",
                    table(h("Element", "Slice name", "Min", "Max", "Extension profile URL"), ur)));
        }
        secs.add(publishing(f));
        return secs;
    }

    private static List<String> bodyExtension(JsonNode f) {
        JsonNode m = f.get("meta");
        JsonNode d = f.get("data");
        String ident = props(List.of(
                kv("URL", Json.text(m, "url")), kv("Version", Json.text(m, "version")),
                kv("Context", String.join(", ", Json.strings(d, "contexts"))),
                kv("FHIR version", Json.text(m, "fhir_version")), kv("FMM", Json.strOrNull(m.get("fmm"))),
                kv("Standards status", Json.strOrNull(m.get("standards_status")))));
        Map<String, JsonNode> bind = new LinkedHashMap<>();
        for (JsonNode b : d.get("bindings")) {
            bind.put(Json.text(b, "element"), b);
        }
        List<String[]> rows = new ArrayList<>();
        for (JsonNode r : d.get("rows")) {
            JsonNode b = bind.get(Json.text(r, "id"));
            String bt = "";
            if (b != null) {
                String vs = Json.text(b, "value_set");
                bt = Json.text(b, "strength") + ": " + (vs.isEmpty() ? Text.NOT_SPEC : vs);
            }
            rows.add(row(Json.text(r, "id"), Json.text(r, "types"), bt));
        }
        return List.of(overview(), section("Identity", ident),
                section("Extension Structure", table(h("Path", "Type", "Binding"), rows)), publishing(f));
    }

    private static List<String> bodySearchParam(JsonNode f) {
        JsonNode d = f.get("data");
        String ident = props(List.of(
                kv("Code", Json.text(d, "code")), kv("Base", String.join(", ", Json.strings(d, "base"))),
                kv("Type", Json.text(d, "type")), kv("Expression", Json.text(d, "expression")),
                kv("Derived from", Json.text(d, "derived_from"))));
        List<String[]> rows = new ArrayList<>();
        rows.add(row("multipleOr", fb(d.get("multiple_or"))));
        rows.add(row("multipleAnd", fb(d.get("multiple_and"))));
        for (JsonNode c : d.get("comparators")) {
            rows.add(row(Json.text(c, "code"), fb(c.get("expectation"))));
        }
        return List.of(overview(), section("Identity", ident),
                section("Conformance Expectations", table(h("Parameter", "Expectation"), rows)), publishing(f));
    }

    private static List<String> composeBlock(String kind, JsonNode rules) {
        List<String> secs = new ArrayList<>();
        for (JsonNode r : rules) {
            String system = Json.text(r, "system");
            String head = kind + ": " + (system.isEmpty() ? "value set import" : system);
            if (!Json.text(r, "version").isEmpty()) {
                head += " (version " + Json.text(r, "version") + ")";
            }
            List<String> blocks = new ArrayList<>();
            JsonNode concepts = r.get("concepts");
            JsonNode filters = r.get("filters");
            JsonNode valueSets = r.get("value_sets");
            if (concepts.size() > 0) {
                List<String[]> rows = new ArrayList<>();
                for (JsonNode c : concepts) {
                    rows.add(row(Json.text(c, "code"), fb(Json.text(c, "display"))));
                }
                blocks.add(table(h("Code", "Display"), rows));
            }
            if (filters.size() > 0) {
                List<String[]> rows = new ArrayList<>();
                for (JsonNode x : filters) {
                    rows.add(row(Json.text(x, "property"), Json.text(x, "op"), Json.text(x, "value")));
                }
                blocks.add(table(h("Property", "Operator", "Value"), rows));
            }
            if (valueSets.size() > 0) {
                List<String> lines = new ArrayList<>();
                for (JsonNode v : valueSets) {
                    lines.add("* " + v.asText());
                }
                blocks.add(String.join("\n", lines));
            }
            if (concepts.size() == 0 && filters.size() == 0 && valueSets.size() == 0) {
                blocks.add("All codes in the code system.");
            }
            secs.add(section(3, head, blocks.toArray(new String[0])));
        }
        return secs;
    }

    private static List<String> bodyValueSet(JsonNode f) {
        JsonNode m = f.get("meta");
        JsonNode d = f.get("data");
        String ident = props(List.of(kv("URL", Json.text(m, "url")), kv("Version", Json.text(m, "version")),
                kv("Status", Json.text(m, "status"))));
        List<String> comp = new ArrayList<>(composeBlock("Include", d.get("include")));
        comp.addAll(composeBlock("Exclude", d.get("exclude")));
        if (comp.isEmpty()) {
            comp.add(Text.NOT_SPEC + "\n");
        }
        return List.of(overview(), section("Identity", ident), section("Compose", String.join("\n", comp)),
                publishing(f));
    }

    private static List<String> bodyCodeSystem(JsonNode f) {
        JsonNode m = f.get("meta");
        JsonNode d = f.get("data");
        String ident = props(List.of(kv("URL", Json.text(m, "url")), kv("Version", Json.text(m, "version")),
                kv("Content", Json.text(d, "content"))));
        List<String[]> rows = new ArrayList<>();
        for (JsonNode c : d.get("concepts")) {
            rows.add(row(Json.text(c, "code"), fb(Json.text(c, "display")), fb(Json.text(c, "definition"))));
        }
        return List.of(overview(), section("Identity", ident),
                section("Concepts", table(h("Code", "Display", "Definition"), rows)), publishing(f));
    }

    private static List<String> bodyCapability(JsonNode f) {
        JsonNode m = f.get("meta");
        JsonNode d = f.get("data");
        String ident = props(List.of(kv("Kind", Json.text(d, "kind")),
                kv("FHIR version", Json.text(m, "fhir_version")),
                kv("Formats", String.join(", ", Json.strings(d, "formats")))));
        List<String> subs = new ArrayList<>();
        for (JsonNode r : d.get("resources")) {
            List<String> blocks = new ArrayList<>();
            JsonNode interactions = r.get("interactions");
            JsonNode searchParams = r.get("search_params");
            JsonNode profiles = r.get("profiles");
            if (interactions.size() > 0) {
                List<String[]> rows = new ArrayList<>();
                for (JsonNode i : interactions) {
                    rows.add(row(Json.text(i, "code"), "Yes", Json.text(i, "expectation")));
                }
                blocks.add(table(h("Interaction", "Supported?", "Notes"), rows));
            }
            if (searchParams.size() > 0) {
                List<String[]> rows = new ArrayList<>();
                for (JsonNode p : searchParams) {
                    rows.add(row(Json.text(p, "name"), Json.text(p, "type"), Json.text(p, "definition"),
                            Json.text(p, "expectation")));
                }
                blocks.add(table(h("Name", "Type", "Definition", "Notes"), rows));
            }
            if (profiles.size() > 0) {
                List<String> lines = new ArrayList<>();
                for (JsonNode p : profiles) {
                    String ex = Json.text(p, "expectation");
                    lines.add("* " + Json.text(p, "url") + (ex.isEmpty() ? "" : " (" + ex + ")"));
                }
                blocks.add(String.join("\n", lines));
            }
            List<String[]> brows = new ArrayList<>();
            if (!Json.text(r, "expectation").isEmpty()) {
                brows.add(kv("Expectation", Json.text(r, "expectation")));
            }
            Map<String, String> behavior = new TreeMap<>();
            JsonNode beh = r.get("behavior");
            for (String k : Json.fieldNames(beh)) {
                behavior.put(k, Json.str(beh.get(k)));
            }
            behavior.forEach((k, v) -> brows.add(kv(k, v)));
            if (!brows.isEmpty()) {
                blocks.add(props(brows));
            }
            String type = Json.text(r, "type");
            subs.add(section(3, type.isEmpty() ? Text.NOT_SPEC : type, blocks.toArray(new String[0])));
        }
        return List.of(overview(), section("Identity", ident),
                section("Supported Resources", String.join("\n", subs)), publishing(f));
    }

    private static String lowerOrNull(JsonNode v) {
        String s = Json.strOrNull(v);
        return s == null ? null : s.toLowerCase(java.util.Locale.ROOT);
    }

    private static List<String> bodyOperation(JsonNode f) {
        JsonNode d = f.get("data");
        String ident = props(List.of(kv("Kind", Json.text(d, "kind")), kv("Code", Json.text(d, "code")),
                kv("Applies to", String.join(", ", Json.strings(d, "resource"))),
                kv("System", lowerOrNull(d.get("system"))), kv("Type", lowerOrNull(d.get("type"))),
                kv("Instance", lowerOrNull(d.get("instance")))));
        List<String[]> rows = new ArrayList<>();
        for (JsonNode p : d.get("parameters")) {
            rows.add(row(Json.text(p, "name"), Json.text(p, "use"), mm(p.get("min")), Json.text(p, "max"),
                    fb(Json.text(p, "type")), Text.mdToAdoc(Json.text(p, "documentation"))));
        }
        return List.of(overview(), section("Identity", ident),
                section("Parameters", table(h("Name", "Use", "Min", "Max", "Type", "Description"), rows)),
                publishing(f));
    }

    private List<String> body(String seg, JsonNode f) {
        return switch (seg) {
            case "profiles" -> bodyProfile(f);
            case "extensions" -> bodyExtension(f);
            case "searchparams" -> bodySearchParam(f);
            case "valuesets" -> bodyValueSet(f);
            case "codesystems" -> bodyCodeSystem(f);
            case "capabilitystatements" -> bodyCapability(f);
            case "operations" -> bodyOperation(f);
            default -> throw new IllegalStateException("unknown segment " + seg);
        };
    }

    // ------------------------------------------------------------------ whole file

    /** The fragment text for one resource. {@code prose} holds {@code keywords} and {@code summary}. */
    String render(JsonNode facts, JsonNode prose) {
        String topic = Json.text(facts, "topic_id");
        String title = Text.normalizeSpace(Json.text(facts, "title"));
        List<String> kws = new ArrayList<>(List.of(cfg.igSlug, RT_KEBAB.get(Json.text(facts, "resource_type")), "fhir"));
        for (String k : Json.strings(prose, "keywords")) {
            if (!kws.contains(k)) {
                kws.add(k);
            }
        }
        List<String> head = List.of("// " + topic, "// Topic: " + title, "// Type: reference",
                "// Status: published", ":topic-id: " + topic, ":topic-type: reference",
                ":topic-status: published", ":topic-provenance: external", ":topic-citation: " + cfg.citation,
                ":topic-license: " + cfg.license, ":topic-keywords: " + String.join(", ", kws));
        StringBuilder text = new StringBuilder(String.join("\n", head)).append('\n');
        text.append(Text.attributeLine("topic-summary", Json.text(prose, "summary")));
        text.append(Text.attributeLine("topic-canonical", Json.text(facts, "canonical")));
        text.append(Text.attributeLine("topic-resource-type", Json.text(facts, "resource_type")));
        text.append(Text.attributeLine("topic-ig-version", cfg.igVersion));
        text.append(Text.attributeLine("topic-fhir-version", cfg.fhirVersion));
        text.append("\n[[").append(topic).append("]]\n= ").append(title).append("\n\n");
        String body = Text.rstripChars(String.join("\n", body(Json.text(facts, "seg"), facts)), "\n") + "\n";
        return text + body;
    }

    /** Placeholder prose for renderer tests only. */
    static ObjectNode stubProse(JsonNode facts) {
        ObjectNode p = Json.object();
        p.put("id", Json.text(facts, "id"));
        p.put("resource_type", Json.text(facts, "resource_type"));
        p.set("keywords", Json.arrayOf(List.of("stub")));
        p.put("summary", "Stub summary sentence used only for renderer testing.");
        return p;
    }

    /** {@code _includes.adoc}: one include per fragment, grouped by segment. */
    String includesText(List<JsonNode> factsList, String date) {
        Map<String, List<JsonNode>> bySeg = new LinkedHashMap<>();
        for (JsonNode f : factsList) {
            bySeg.computeIfAbsent(Json.text(f, "seg"), k -> new ArrayList<>()).add(f);
        }
        List<String> out = new ArrayList<>(List.of("// IKE-ASCIIDOC-FRAGMENT includes manifest",
                "// IG: " + cfg.igDirName, "// Generated: " + date, ""));
        for (Map.Entry<String, String> seg : IgConfig.SEGMENTS.entrySet()) {
            List<JsonNode> items = bySeg.get(seg.getKey());
            if (items == null) {
                continue;
            }
            out.add("== " + seg.getValue());
            out.add("");
            items.sort(Comparator.comparing((JsonNode x) -> Json.text(x, "id")));
            for (JsonNode f : items) {
                out.add("include::" + Json.text(f, "out_file") + "[leveloffset=+1]");
                out.add("");
            }
        }
        return String.join("\n", out);
    }

    // ------------------------------------------------------------------ CLI

    /** {@code render --config CFG FACTS_DIR PROSE_DIR OUT_DIR [--stub]}. */
    int run(Args args, PrintStream out) {
        List<String> pos = args.positional();
        if (pos.size() < 3) {
            throw new CliException("usage: render --config CFG FACTS_DIR PROSE_DIR OUT_DIR [--stub]");
        }
        Path factsDir = Path.of(pos.get(0));
        Path proseDir = Path.of(pos.get(1));
        Path outDir = Path.of(pos.get(2));
        boolean stub = args.flag("--stub");
        int n = 0;
        List<String> skipped = new ArrayList<>();
        for (Path p : factsFiles(factsDir)) {
            JsonNode facts = Json.read(p);
            String outFile = Json.text(facts, "out_file");
            Path pp = proseDir.resolve(outFile + ".prose.json");
            JsonNode prose;
            if (Files.exists(pp)) {
                prose = Json.read(pp);
            } else if (stub) {
                prose = stubProse(facts);
            } else {
                skipped.add(pp.getFileName().toString());
                continue;
            }
            Json.writeText(outDir.resolve(outFile), render(facts, prose));
            n++;
        }
        out.println("rendered: " + n);
        skipped.stream().limit(10).forEach(s -> out.println("NO PROSE FILE (not rendered): " + s));
        if (!skipped.isEmpty() || n == 0) {
            out.println(!skipped.isEmpty()
                    ? "FAILED: " + skipped.size() + " resource(s) not rendered (a prose file is named "
                    + "<out_file>.prose.json, e.g. OperationDefinition-docref.adoc.prose.json)"
                    : "FAILED: nothing rendered (check the facts folder path)");
            return 1;
        }
        return 0;
    }

    /** The {@code *.facts.json} files of a folder, sorted by name. */
    static List<Path> factsFiles(Path dir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".facts.json")).forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }
}
