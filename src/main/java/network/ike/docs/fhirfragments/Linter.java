package network.ike.docs.fhirfragments;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validators L1 to L6 for rendered fragments, plus the L4 prose schema.
 *
 * <p>With prose (a prose file, or derive-prose) the WHOLE file must equal a fresh render, so a hand edit anywhere
 * fails. derive-prose rebuilds keywords and summary from the fragment itself: it cannot detect an edit to those
 * two fields (it still checks them against the prose rules) but detects every other change, including any
 * change to the Overview (which must be exactly the reference to {@code :topic-summary:}). The raw source JSON
 * text is the independent oracle for L2 and L3.
 */
final class Linter {
    private static final Map<String, List<String>> SECTION_ORDER = new LinkedHashMap<>();
    private static final Map<String, Set<String>> REQUIRED_SECTIONS = new LinkedHashMap<>();

    static {
        SECTION_ORDER.put("profiles", List.of("Overview", "Identity and Derivation", "Conformance Table",
                "Terminology Bindings", "Fixed Codes and Pattern Constraints", "Invariants", "USCDI Extensions",
                "Publishing Metadata"));
        SECTION_ORDER.put("extensions", List.of("Overview", "Identity", "Extension Structure", "Publishing Metadata"));
        SECTION_ORDER.put("searchparams", List.of("Overview", "Identity", "Conformance Expectations",
                "Publishing Metadata"));
        SECTION_ORDER.put("valuesets", List.of("Overview", "Identity", "Compose", "Publishing Metadata"));
        SECTION_ORDER.put("codesystems", List.of("Overview", "Identity", "Concepts", "Publishing Metadata"));
        SECTION_ORDER.put("capabilitystatements", List.of("Overview", "Identity", "Supported Resources",
                "Publishing Metadata"));
        SECTION_ORDER.put("operations", List.of("Overview", "Identity", "Parameters", "Publishing Metadata"));
        for (Map.Entry<String, List<String>> e : SECTION_ORDER.entrySet()) {
            REQUIRED_SECTIONS.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
        }
        REQUIRED_SECTIONS.put("profiles", new LinkedHashSet<>(List.of("Overview", "Identity and Derivation",
                "Conformance Table", "Publishing Metadata")));
    }

    private static final Pattern URL_RE = Text.re("https?://[^\\s|\\\\\"<>)\\]\\[,;`']+");
    private static final Pattern OID_RE = Text.re("\\b\\d+(?:\\.\\d+){3,}\\b");
    private static final Pattern UNESCAPED_PIPE = Pattern.compile("(?<!\\\\)\\|");
    private static final Pattern TOPIC_ATTR = Text.re("^:(topic-[a-z-]+):(.*)$");
    private static final Pattern URL_COMMA_URL = Text.re("https?://[^\\s|,]+,\\s*https?://");
    private static final Pattern COLS_STAR = Text.re("^\\[cols=\"(\\d+)\\*\"");
    private static final Pattern COLS_LIST = Text.re("^\\[cols=\"([\\d,]+)\"");
    static final Pattern KEYWORD = Text.re("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern OVERVIEW_RE = Text.re("^== Overview\n\n(.*?)\n\n== ", Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern SECTION_START = Text.re("^(?=== )", Pattern.MULTILINE);
    private static final Pattern SENTENCE_END = Text.re("[.!?](\\s|$)");
    private static final Pattern SUMMARY_MARKUP = Text.re("[|`*_\\[\\]<>\\n\\r—]");
    private static final Pattern BANNED_OPENING = Text.re("^(This (resource|profile)|The resource)\\b");
    private static final Pattern GROUND_TOKEN = Text.re("https?://\\S+|\\b\\d[\\d.]*\\b");

    static final List<String> ALLOWED_ATTR_ORDER = List.of("topic-id", "topic-type", "topic-status",
            "topic-provenance", "topic-citation", "topic-license", "topic-keywords", "topic-summary",
            "topic-canonical", "topic-resource-type", "topic-ig-version", "topic-fhir-version");
    private static final Set<String> WRAPPED = Set.of("topic-summary", "topic-canonical", "topic-resource-type",
            "topic-ig-version", "topic-fhir-version");

    private final IgConfig cfg;
    private final Renderer renderer;

    Linter(IgConfig cfg, Renderer renderer) {
        this.cfg = cfg;
        this.renderer = renderer;
    }

    // ------------------------------------------------------------------ header parsing

    record Attr(String name, String raw, String value) {
    }

    record Header(List<String> comments, List<Attr> attrs, int next, List<String> lines) {
    }

    static Header splitHeader(String text) {
        List<String> lines = Text.lines(text);
        int i = 0;
        List<String> comments = new ArrayList<>();
        while (i < lines.size() && lines.get(i).startsWith("//")) {
            comments.add(lines.get(i));
            i++;
        }
        List<Attr> attrs = new ArrayList<>();
        while (i < lines.size() && lines.get(i).startsWith(":topic-")) {
            Matcher m = TOPIC_ATTR.matcher(lines.get(i));
            if (!m.find()) {
                break;
            }
            String name = m.group(1);
            String val = m.group(2);
            String raw = lines.get(i);
            while (raw.endsWith(" " + Text.BS) && i + 1 < lines.size() && lines.get(i + 1).startsWith("  ")) {
                i++;
                raw += "\n" + lines.get(i);
                if (val.endsWith(" " + Text.BS)) {
                    val = val.substring(0, val.length() - 2) + " " + Text.strip(lines.get(i));
                }
            }
            attrs.add(new Attr(name, raw, val));
            i++;
        }
        return new Header(comments, attrs, i, lines);
    }

    // ------------------------------------------------------------------ L1 structure

    List<String> checkStructure(String fileName, String text, JsonNode facts) {
        List<String> errs = new ArrayList<>();
        boolean hasBom = text.startsWith("﻿");
        text = Text.lstripChar(text, '﻿');
        if (hasBom) {
            errs.add("BOM present (other header checks ran on the text without it)");
        }
        if (text.indexOf('\r') >= 0) {
            errs.add("CR characters present");
        }
        if (!text.endsWith("\n") || text.endsWith("\n\n")) {
            errs.add("file must end with exactly one newline");
        }
        Header hd = splitHeader(text);
        String topic = Json.text(facts, "topic_id");
        String title = Text.normalizeSpace(Json.text(facts, "title"));
        List<String> expComments = List.of("// " + topic, "// Topic: " + title, "// Type: reference",
                "// Status: published");
        if (!hd.comments().equals(expComments)) {
            errs.add("comment lines differ: " + hd.comments().subList(0, Math.min(4, hd.comments().size())));
        }
        List<String> names = new ArrayList<>();
        hd.attrs().forEach(a -> names.add(a.name()));
        if (!names.equals(ALLOWED_ATTR_ORDER)) {
            errs.add("attribute order/set differs: " + names);
        }
        Map<String, String> fixed = new LinkedHashMap<>();
        fixed.put("topic-id", topic);
        fixed.put("topic-type", "reference");
        fixed.put("topic-status", "published");
        fixed.put("topic-provenance", "external");
        fixed.put("topic-citation", cfg.citation);
        fixed.put("topic-license", cfg.license);
        fixed.put("topic-canonical", Json.text(facts, "canonical"));
        fixed.put("topic-resource-type", Json.text(facts, "resource_type"));
        fixed.put("topic-ig-version", cfg.igVersion);
        fixed.put("topic-fhir-version", cfg.fhirVersion);
        for (Attr a : hd.attrs()) {
            String v = Text.normalizeSpace(a.value());
            if (fixed.containsKey(a.name()) && !v.equals(Text.normalizeSpace(fixed.get(a.name())))) {
                errs.add(a.name() + " value differs");
            }
            if (WRAPPED.contains(a.name())) {
                String ref = Text.rstripChars(Text.attributeLine(a.name(), v), "\n");
                if (!a.raw().equals(ref)) {
                    errs.add(a.name() + " not wrapped per reference algorithm");
                }
            } else if (a.raw().endsWith(" " + Text.BS) || a.raw().contains("\n")) {
                errs.add(a.name() + " must not be wrapped");
            }
        }
        for (Attr a : hd.attrs()) {
            if (!a.name().equals("topic-keywords")) {
                continue;
            }
            List<String> ks = new ArrayList<>();
            for (String k : a.value().split(",", -1)) {
                ks.add(Text.strip(k));
            }
            List<String> fixedKw = List.of(cfg.igSlug, Renderer.RT_KEBAB.get(Json.text(facts, "resource_type")), "fhir");
            if (!ks.subList(0, Math.min(3, ks.size())).equals(fixedKw)) {
                errs.add("keywords must start with the three fixed keywords");
            }
            if (ks.size() != new HashSet<>(ks).size() || ks.stream().anyMatch(k -> !KEYWORD.matcher(k).find())) {
                errs.add("keywords malformed or duplicated");
            }
            break;
        }
        // anchor / title / blank lines
        List<String> rest = hd.lines().subList(hd.next(), hd.lines().size());
        List<String> want = List.of("", "[[" + topic + "]]", "= " + title, "");
        if (!rest.subList(0, Math.min(4, rest.size())).equals(want)) {
            errs.add("anchor/title/blank-line block differs");
        }
        // headings
        List<String> secs = new ArrayList<>();
        for (String l : hd.lines()) {
            if (l.startsWith("== ")) {
                secs.add(l.substring(3));
            }
        }
        String seg = Json.text(facts, "seg");
        List<String> order = SECTION_ORDER.get(seg);
        if (!new HashSet<>(secs).containsAll(REQUIRED_SECTIONS.get(seg))) {
            List<String> missing = new ArrayList<>(REQUIRED_SECTIONS.get(seg));
            missing.removeAll(secs);
            missing.sort(String::compareTo);
            errs.add("missing required sections: " + missing);
        }
        List<String> inOrder = new ArrayList<>();
        for (String s : secs) {
            if (order.contains(s)) {
                inOrder.add(s);
            }
        }
        List<String> expected = new ArrayList<>();
        for (String s : order) {
            if (secs.contains(s)) {
                expected.add(s);
            }
        }
        if (!inOrder.equals(expected) || secs.stream().anyMatch(s -> !order.contains(s))) {
            errs.add("section order/names differ: " + secs);
        }
        if (!fileName.equals(Json.text(facts, "out_file"))) {
            errs.add("filename differs from facts out_file");
        }
        return errs;
    }

    // ------------------------------------------------------------------ L6 syntax

    /** Split a table row line on unescaped pipes; the row starts with '|'. */
    static List<String> cellsOf(String row) {
        return new ArrayList<>(Arrays.asList(UNESCAPED_PIPE.split(row.substring(1), -1)));
    }

    List<String> checkSyntax(String text) {
        List<String> errs = new ArrayList<>();
        List<String> lines = Text.lines(text);
        boolean inTable = false;
        Integer ncols = null;
        String row = null;
        int rowStart = 0;
        for (int idx = 0; idx < lines.size(); idx++) {
            String l = lines.get(idx);
            int n = idx + 1;
            if (inTable && row != null) {          // continuation of a cell that holds hard line breaks
                row += " " + l;
                if (l.endsWith(" +")) {
                    continue;
                }
                l = row;
                n = rowStart;
                row = null;
            } else if (inTable && l.startsWith("|") && l.endsWith(" +") && !l.equals("|===")) {
                row = l;
                rowStart = n;
                continue;
            }
            if (inTable && URL_COMMA_URL.matcher(l).find()) {
                errs.add("line " + n + ": comma-separated URLs in a table cell (one URL per line expected)");
            }
            if (l.startsWith("[cols=\"")) {
                Matcher m = COLS_STAR.matcher(l);
                if (m.find()) {
                    ncols = Integer.parseInt(m.group(1));
                } else {
                    Matcher m2 = COLS_LIST.matcher(l);
                    ncols = m2.find() ? m2.group(1).split(",", -1).length : null;
                }
                continue;
            }
            if (l.equals("|===")) {
                inTable = !inTable;
                continue;
            }
            if (inTable) {
                if (!l.startsWith("|")) {
                    errs.add("line " + n + ": table row must start with |");
                    continue;
                }
                int cells = cellsOf(l).size();
                if (ncols != null && cells != ncols) {
                    errs.add("line " + n + ": " + cells + " cells, expected " + ncols);
                }
            }
        }
        if (inTable) {
            errs.add("unterminated table");
        }
        List<Integer> heads = new ArrayList<>();
        for (String l : lines) {
            int k = 0;
            while (k < l.length() && l.charAt(k) == '=') {
                k++;
            }
            if (k > 0 && k < l.length() && l.charAt(k) == ' ') {
                heads.add(k);
            }
        }
        for (int i = 0; i + 1 < heads.size(); i++) {
            if (heads.get(i + 1) > heads.get(i) + 1) {
                errs.add("heading level jump " + heads.get(i) + " -> " + heads.get(i + 1));
            }
        }
        return errs;
    }

    // ------------------------------------------------------------------ L3 fabrication, L2 coverage

    private static Set<String> collectUrls(String s) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = URL_RE.matcher(s);
        while (m.find()) {
            out.add(Text.rstripChars(m.group(), "."));
        }
        return out;
    }

    private static String afterAnchor(String text) {
        int i = text.indexOf("\n[[");
        return i >= 0 ? text.substring(i + 3) : text;
    }

    /** Every URL, OID and long dotted number in the body must occur in the raw source JSON. */
    List<String> checkFabrication(String text, String srcText) {
        List<String> errs = new ArrayList<>();
        Set<String> allowed = Set.of(cfg.igUrl, Text.rstripChars(cfg.igUrl, "/"));
        String body = afterAnchor(text);
        for (String u : collectUrls(body)) {
            if (allowed.contains(u)) {
                continue;
            }
            if (!srcText.contains(u) && !srcText.contains(u.replace("\\|", "|"))) {
                errs.add("URL not in source: " + u);
            }
        }
        Set<String> tokens = new LinkedHashSet<>();
        Matcher m = OID_RE.matcher(body);
        while (m.find()) {
            tokens.add(m.group());
        }
        for (String tok : tokens) {
            if (!srcText.contains(tok)) {
                errs.add("OID-like token not in source: " + tok);
            }
        }
        return errs;
    }

    /** Independent oracle: read the raw source with plain traversal and check ids, codes and URLs appear. */
    List<String> checkCoverage(String text, JsonNode facts, JsonNode src) {
        List<String> errs = new ArrayList<>();
        String body = afterAnchor(text);
        String rt = Json.text(src, "resourceType");
        List<String> need = new ArrayList<>();
        switch (rt) {
            case "StructureDefinition" -> {
                boolean isExt = Json.text(facts, "seg").equals("extensions");
                for (JsonNode e : Json.list(src.get("differential"), "element")) {
                    need.add(Json.text(e, "id"));
                    String vs = Json.text(e.get("binding"), "valueSet");
                    if (!vs.isEmpty()) {
                        need.add(vs);
                    }
                    if (!isExt) {   // the extension skeleton has no Invariants section
                        for (JsonNode c : Json.list(e, "constraint")) {
                            need.add(Json.text(c, "key"));
                        }
                    }
                }
                need.add(Json.text(src, "url"));
            }
            case "SearchParameter" -> {
                need.add(Json.text(src, "code"));
                need.add(Json.text(src, "expression"));
                need.add(Json.text(src, "url"));
                need.addAll(Json.strings(src, "base"));
                need.addAll(Json.strings(src, "comparator"));
            }
            case "ValueSet" -> {
                for (String k : List.of("include", "exclude")) {
                    for (JsonNode inc : Json.list(src.get("compose"), k)) {
                        for (JsonNode c : Json.list(inc, "concept")) {
                            need.add(Json.text(c, "code"));
                            if (!Json.text(c, "display").isEmpty()) {
                                need.add(Json.text(c, "display"));
                            }
                        }
                        for (JsonNode fl : Json.list(inc, "filter")) {
                            need.add(Json.text(fl, "property"));
                            need.add(Json.text(fl, "value"));
                        }
                        need.addAll(Json.strings(inc, "valueSet"));
                        if (!Json.text(inc, "system").isEmpty()) {
                            need.add(Json.text(inc, "system"));
                        }
                    }
                }
                need.add(Json.text(src, "url"));
            }
            case "CodeSystem" -> {
                for (JsonNode c : Json.list(src, "concept")) {
                    need.add(Json.text(c, "code"));
                    if (!Json.text(c, "display").isEmpty()) {
                        need.add(Json.text(c, "display"));
                    }
                }
                need.add(Json.text(src, "url"));
            }
            case "CapabilityStatement" -> {
                List<JsonNode> rests = Json.list(src, "rest");
                JsonNode rest = rests.isEmpty() ? null : rests.get(0);
                for (JsonNode r : Json.list(rest, "resource")) {
                    need.add(Json.text(r, "type"));
                    for (JsonNode i : Json.list(r, "interaction")) {
                        need.add(Json.text(i, "code"));
                    }
                    for (JsonNode p : Json.list(r, "searchParam")) {
                        need.add(Json.text(p, "name"));
                        if (!Json.text(p, "definition").isEmpty()) {
                            need.add(Json.text(p, "definition"));
                        }
                    }
                    need.addAll(Json.strings(r, "supportedProfile"));
                }
            }
            case "OperationDefinition" -> {
                need.add(Json.text(src, "code"));
                need.add(Json.text(src, "url"));
                for (JsonNode p : Json.list(src, "parameter")) {
                    need.add(Json.text(p, "name"));
                }
            }
            default -> {
            }
        }
        String esc = body.replace("\\|", "|");
        for (String n : need) {
            String n2 = Text.normalizeSpace(n);
            if (!n2.isEmpty() && !esc.contains(n2)) {
                errs.add("missing from output: " + Text.head(n2, 100));
            }
        }
        return errs;
    }

    // ------------------------------------------------------------------ L1b render equality

    static String sectionsWithoutOverview(String text) {
        String body = afterAnchor(text);
        StringBuilder sb = new StringBuilder();
        for (String p : Text.splitBefore(SECTION_START, body)) {
            if (!p.startsWith("== Overview")) {
                sb.append(p);
            }
        }
        return sb.toString();
    }

    static String firstDiff(String aText, String bText) {
        String[] a = aText.split("\n", -1);
        String[] b = bText.split("\n", -1);
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (!a[i].equals(b[i])) {
                return "line " + (i + 1) + ": '" + Text.head(a[i], 60) + "' vs '" + Text.head(b[i], 60) + "'";
            }
        }
        return "length " + a.length + " vs " + b.length + " lines";
    }

    /**
     * With prose: the whole file (header, Overview, body) must equal a fresh deterministic render. Without
     * prose (fallback when the prose itself is invalid): only the body outside Overview is compared.
     */
    List<String> checkRenderEquality(String text, JsonNode facts, JsonNode prose) {
        if (prose != null) {
            String ref = renderer.render(facts, prose);
            return text.equals(ref) ? List.of()
                    : List.of("file differs from deterministic render at " + firstDiff(text, ref));
        }
        JsonNode dummy = Json.parse("{\"keywords\": [], \"summary\": \"x\"}");
        String ref = renderer.render(facts, dummy);
        String a = sectionsWithoutOverview(text);
        String b = sectionsWithoutOverview(ref);
        return a.equals(b) ? List.of() : List.of("body differs from deterministic render at " + firstDiff(a, b));
    }

    // ------------------------------------------------------------------ prose derivation, L4

    /**
     * Rebuild the prose object from a rendered fragment (keywords after the three fixed ones, summary).
     * Returns null when the Overview is not exactly the reference to the summary attribute.
     */
    static JsonNode deriveProse(String text, JsonNode facts) {
        Header hd = splitHeader(text);
        Map<String, String> val = new LinkedHashMap<>();
        for (Attr a : hd.attrs()) {
            val.put(a.name(), Text.normalizeSpace(a.value()));
        }
        List<String> kws = new ArrayList<>();
        for (String k : val.getOrDefault("topic-keywords", "").split(",", -1)) {
            if (!Text.strip(k).isEmpty()) {
                kws.add(Text.strip(k));
            }
        }
        kws = kws.size() > 3 ? kws.subList(3, kws.size()) : List.of();
        Matcher m = OVERVIEW_RE.matcher(text);
        if (!m.find() || !m.group(1).equals(Renderer.OVERVIEW_REF)) {
            return null;
        }
        var p = Json.object();
        p.put("id", Json.text(facts, "id"));
        p.put("resource_type", Json.text(facts, "resource_type"));
        p.set("keywords", Json.arrayOf(kws));
        p.put("summary", val.getOrDefault("topic-summary", ""));
        return p;
    }

    static List<String> checkOverviewReference(String text) {
        Matcher m = OVERVIEW_RE.matcher(text);
        if (!m.find()) {
            return List.of("Overview section not found");
        }
        if (!m.group(1).equals(Renderer.OVERVIEW_REF)) {
            return List.of("Overview must contain only " + Renderer.OVERVIEW_REF
                    + " (the summary is the single source of text), found: " + Text.head(m.group(1), 80));
        }
        return List.of();
    }

    /** All per-file checks; {@code prose} may be null (then render equality skips the Overview). */
    Map<String, List<String>> runChecks(String fileName, String text, JsonNode facts, String srcText, JsonNode src,
                                        JsonNode prose) {
        Map<String, List<String>> res = new LinkedHashMap<>();
        List<String> l1 = new ArrayList<>(checkStructure(fileName, text, facts));
        l1.addAll(checkOverviewReference(text));
        res.put("L1 structure", l1);
        res.put("L1b render-equal", checkRenderEquality(text, facts, prose));
        res.put("L2 coverage", checkCoverage(text, facts, src));
        res.put("L3 fabrication", checkFabrication(text, srcText));
        res.put("L6 syntax", checkSyntax(text));
        return res;
    }

    List<String> checkProse(Path path) {
        JsonNode p;
        try {
            p = Json.read(path);
        } catch (RuntimeException e) {
            return List.of("prose not valid JSON: " + e.getMessage());
        }
        if (p == null || !p.isObject()) {
            return List.of("prose must be a JSON object");
        }
        return checkProseObj(p);
    }

    List<String> checkProseObj(JsonNode p) {
        List<String> errs = new ArrayList<>();
        if (p.has("error")) {
            return List.of("agent reported error: " + Json.text(p, "error"));
        }
        List<String> allowed = List.of("id", "keywords", "resource_type", "summary");
        for (String k : allowed) {
            if (!p.has(k)) {
                errs.add("missing key " + k);
            }
        }
        if (!errs.isEmpty()) {
            return errs;
        }
        List<String> extra = new ArrayList<>(Json.fieldNames(p));
        extra.removeAll(allowed);
        if (!extra.isEmpty()) {
            extra.sort(String::compareTo);
            errs.add("unexpected keys: " + extra);
        }
        JsonNode ks = p.get("keywords");
        if (!ks.isArray() || ks.size() < 2 || ks.size() > 5) {
            errs.add("keywords must be a list of 2-5 (not counting the three fixed ones the renderer adds)");
        } else {
            Set<String> fixedKw = new HashSet<>(List.of(cfg.igSlug, "fhir",
                    Renderer.RT_KEBAB.getOrDefault(Json.text(p, "resource_type"), "")));
            Set<String> seen = new HashSet<>();
            for (JsonNode k : ks) {
                if (!k.isTextual() || !KEYWORD.matcher(k.asText()).find()) {
                    errs.add("bad keyword " + pyRepr(k));
                }
                if (k.isTextual() && fixedKw.contains(k.asText())) {
                    errs.add("keyword " + pyRepr(k) + " is a fixed keyword");
                }
                seen.add(Json.str(k));
            }
            if (seen.size() != ks.size()) {
                errs.add("duplicate keywords");
            }
        }
        JsonNode sn = p.get("summary");
        String s = sn.isTextual() ? sn.asText() : null;
        if (s == null || Text.len(s) < 60 || Text.len(s) > 200 || !s.endsWith(".")) {
            errs.add("summary must be 60-200 chars ending with a full stop");
        }
        if (s != null) {
            int sentences = 0;
            Matcher m = SENTENCE_END.matcher(s);
            while (m.find()) {
                sentences++;
            }
            if (sentences != 1) {
                errs.add("summary must be exactly one sentence");
            }
        }
        String v = s == null ? "" : s;
        if (SUMMARY_MARKUP.matcher(v).find()) {
            errs.add("summary contains markup, pipe, em dash or line break");
        }
        if (BANNED_OPENING.matcher(v).find()) {
            errs.add("summary starts with a banned phrase");
        }
        return errs;
    }

    private static String pyRepr(JsonNode k) {
        return k.isTextual() ? "'" + k.asText() + "'" : k.toString();
    }

    /** The summary may only contain URL, OID and number tokens that appear in prose_inputs. */
    static List<String> checkProseGrounding(JsonNode p, JsonNode facts) {
        List<String> errs = new ArrayList<>();
        String hay = Json.dumps(facts.get("prose_inputs"), -1, false);
        String v = Json.text(p, "summary");
        Matcher m = GROUND_TOKEN.matcher(v);
        while (m.find()) {
            String tok = m.group();
            if (!hay.contains(Text.rstripChars(tok, ".,"))) {
                errs.add("summary contains ungrounded token '" + tok + "'");
            }
        }
        return errs;
    }

    // ------------------------------------------------------------------ CLI

    /**
     * {@code lint --config CFG OUT_DIR FACTS_DIR PROSE_DIR}, or with {@code --derive-prose}, or with
     * {@code --allow-stub [PROSE_DIR]}.
     */
    int run(Args args, PrintStream out) {
        List<String> pos = args.positional();
        boolean derive = args.flag("--derive-prose");
        boolean allowStub = args.flag("--allow-stub");
        if (pos.size() < 2 || (pos.size() < 3 && !(derive || allowStub)) || (derive && pos.size() > 2)) {
            throw new CliException("usage: lint --config CFG OUT_DIR FACTS_DIR PROSE_DIR | "
                    + "OUT_DIR FACTS_DIR --derive-prose | OUT_DIR FACTS_DIR --allow-stub [PROSE_DIR]");
        }
        Path outDir = Path.of(pos.get(0));
        Path factsDir = Path.of(pos.get(1));
        Path proseDir = pos.size() > 2 ? Path.of(pos.get(2)) : null;
        int fails = 0;
        int total = 0;
        Map<String, int[]> byLayer = new TreeMap<>();
        for (Path fp : Renderer.factsFiles(factsDir)) {
            JsonNode facts = Json.read(fp);
            String outFile = Json.text(facts, "out_file");
            Path path = outDir.resolve(outFile);
            total++;
            if (!Files.exists(path)) {
                fails++;
                out.println("FAIL " + outFile + " [missing output] file not found in " + outDir);
                continue;
            }
            String text = readExact(path);
            String srcText = Json.readText(cfg.srcDir.resolve(Json.text(facts, "source_file")));
            JsonNode src = Json.parse(srcText);
            JsonNode prose = null;
            List<String> e4 = new ArrayList<>();
            if (derive) {
                prose = deriveProse(Text.lstripChar(text, '﻿'), facts);
                if (prose == null) {
                    e4.add("Overview is not exactly the reference " + Renderer.OVERVIEW_REF);
                } else {
                    e4 = checkProseObj(prose);
                    if (e4.isEmpty()) {
                        e4 = checkProseGrounding(prose, facts);
                    }
                }
            } else {
                Path pp = proseDir == null ? null : proseDir.resolve(outFile + ".prose.json");
                if (pp != null && Files.exists(pp)) {
                    e4 = checkProse(pp);
                    if (e4.isEmpty()) {
                        prose = Json.read(pp);
                        e4 = checkProseGrounding(prose, facts);
                    }
                } else if (allowStub) {
                    prose = Renderer.stubProse(facts);
                } else {
                    e4.add("prose file missing: " + pp);
                }
            }
            Map<String, List<String>> res = runChecks(path.getFileName().toString(), text, facts, srcText, src,
                    e4.isEmpty() ? prose : null);
            res.put("L4 prose", e4);
            boolean bad = false;
            for (Map.Entry<String, List<String>> r : res.entrySet()) {
                byLayer.computeIfAbsent(r.getKey(), k -> new int[2])[0]++;
                if (!r.getValue().isEmpty()) {
                    bad = true;
                    byLayer.get(r.getKey())[1]++;
                    out.println("FAIL " + outFile + " [" + r.getKey() + "] "
                            + String.join("; ", r.getValue().subList(0, Math.min(3, r.getValue().size()))));
                }
            }
            if (bad) {
                fails++;
            }
        }
        if (total == 0) {
            out.println("FAILED: no facts files found in " + factsDir + ", nothing was checked");
            return 1;
        }
        out.println("files checked: " + total + ", files with failures: " + fails);
        byLayer.forEach((k, v) -> out.println(String.format("  %-16s checked %d failed %d", k, v[0], v[1])));
        return fails > 0 ? 1 : 0;
    }

    /** File text exactly as stored (no newline translation, BOM kept). */
    static String readExact(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
