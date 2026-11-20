package network.ike.docs.fhirfragments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source JSON to {@code <ResourceType>-<id>.adoc.facts.json}: deterministic, with snapshot cardinality resolved.
 * Exit codes: 0 ok; 1 one or more extraction warnings (a source shape the renderer does not handle); 2 a
 * source file has a resource type the skill does not support (list it in skip_resource_types to skip it).
 */
final class FactsExtractor {
    private static final String EXPECT = "capabilitystatement-expectation";
    private static final Pattern CHOICE_SLICE = Text.re("(\\w+)\\[x\\]:(\\w+)");
    private static final Pattern CHOICE = Text.re("\\[x\\](?!:)");

    private static final Map<String, String> SUBTYPE = Map.of(
            "profiles", "profile", "extensions", "extension", "searchparams", "searchparameter",
            "valuesets", "valueset", "codesystems", "codesystem", "capabilitystatements", "capabilitystatement",
            "operations", "operation");

    private final IgConfig cfg;

    FactsExtractor(IgConfig cfg) {
        this.cfg = cfg;
    }

    static String segFor(JsonNode res) {
        String rt = Json.text(res, "resourceType");
        switch (rt) {
            case "StructureDefinition":
                if ("Extension".equals(Json.text(res, "type"))
                        || List.of("complex-type", "primitive-type").contains(Json.text(res, "kind"))) {
                    return "extensions";
                }
                return "profiles";
            case "SearchParameter":
                return "searchparams";
            case "ValueSet":
                return "valuesets";
            case "CodeSystem":
                return "codesystems";
            case "CapabilityStatement":
                return "capabilitystatements";
            case "OperationDefinition":
                return "operations";
            default:
                throw new IllegalStateException("unsupported resource type " + rt);
        }
    }

    private static String contactText(JsonNode res) {
        List<String> parts = new ArrayList<>();
        for (JsonNode c : Json.list(res, "contact")) {
            List<String> bits = new ArrayList<>();
            if (!Json.text(c, "name").isEmpty()) {
                bits.add(Json.text(c, "name"));
            }
            for (JsonNode t : Json.list(c, "telecom")) {
                if (!Json.text(t, "value").isEmpty()) {
                    bits.add(Json.text(t, "value"));
                }
            }
            if (!bits.isEmpty()) {
                parts.add(String.join("; ", bits));
            }
        }
        return String.join(" / ", parts);
    }

    /** First extension whose url ends with the suffix: its first value[x] (or the given key). Null if none. */
    static JsonNode extValue(JsonNode res, String urlSuffix, String key) {
        if (res == null) {
            return null;
        }
        for (JsonNode e : Json.list(res, "extension")) {
            if (Json.text(e, "url").endsWith(urlSuffix)) {
                if (key != null) {
                    return e.get(key);
                }
                for (String k : Json.fieldNames(e)) {
                    if (k.startsWith("value")) {
                        return e.get(k);
                    }
                }
                return null;
            }
        }
        return null;
    }

    private static JsonNode extValue(JsonNode res, String urlSuffix) {
        return extValue(res, urlSuffix, null);
    }

    /** Expectation code of an element, or of a primitive's underscore sibling. */
    private static JsonNode expectation(JsonNode node) {
        if (!Json.truthy(node)) {
            return Json.orNull(null);
        }
        return Json.orNull(extValue(node, EXPECT));
    }

    private static String typesText(JsonNode el) {
        List<String> out = new ArrayList<>();
        for (JsonNode t : Json.list(el, "type")) {
            String code = Json.text(t, "code");
            List<String> urls = new ArrayList<>(Json.strings(t, "profile"));
            urls.addAll(Json.strings(t, "targetProfile"));
            if (urls.size() > 1) {      // one URL per line in the table cell
                String lb = String.valueOf(Text.LINE_BREAK);
                out.add(code + " (" + lb + String.join(lb, urls) + " )");
            } else {
                out.add(code + (urls.isEmpty() ? "" : " (" + urls.get(0) + ")"));
            }
        }
        return String.join(" or ", out);
    }

    private static String fixedText(JsonNode v) {
        if (v.isObject()) {
            if (v.has("coding")) {
                List<String> codings = new ArrayList<>();
                for (JsonNode c : v.get("coding")) {
                    codings.add(Json.text(c, "system") + "|" + Json.text(c, "code"));
                }
                String s = String.join("; ", codings);
                if (Json.truthy(v.get("text"))) {
                    s += (s.isEmpty() ? "" : " ") + "text: " + Json.text(v, "text");
                }
                return s;
            }
            if (v.has("code") || v.has("system")) {
                return Json.text(v, "system") + "|" + Json.text(v, "code");
            }
            return Json.dumps(v, -1, true);
        }
        if (v.isBoolean()) {
            return v.asBoolean() ? "true" : "false";
        }
        return Json.str(v);
    }

    private record Snapshot(Map<String, JsonNode> byId, Map<String, String> alias) {
    }

    private static Snapshot snapshotIndex(JsonNode sd) {
        Map<String, JsonNode> snap = new LinkedHashMap<>();
        Map<String, String> alias = new HashMap<>();
        JsonNode snapshot = sd.get("snapshot");
        for (JsonNode el : Json.list(snapshot, "element")) {
            String id = Json.text(el, "id");
            snap.put(id, el);
            String a1 = CHOICE_SLICE.matcher(id).replaceAll("$2");
            if (!a1.equals(id)) {
                alias.putIfAbsent(a1, id);
            }
        }
        // children of a type-restricted choice element: Foo.value[x].child <- Foo.valueQuantity.child
        for (String id : new ArrayList<>(snap.keySet())) {
            Matcher m = CHOICE.matcher(id);
            while (m.find()) {
                JsonNode anc = snap.get(id.substring(0, m.end()));
                if (anc == null) {
                    continue;
                }
                for (JsonNode t : Json.list(anc, "type")) {
                    String code = Json.text(t, "code");
                    if (!code.isEmpty()) {
                        String a = id.substring(0, m.start()) + code.substring(0, 1).toUpperCase(Locale.ROOT)
                                + code.substring(1) + id.substring(m.end());
                        alias.putIfAbsent(a, id);
                    }
                }
            }
        }
        return new Snapshot(snap, alias);
    }

    /** Fills the profile parts of {@code d}; returns nothing, adds warnings to {@code warnings}. */
    private static void profileRows(JsonNode sd, ObjectNode d, ArrayNode warnings) {
        Snapshot sn = snapshotIndex(sd);
        ArrayNode rows = Json.F.arrayNode();
        ArrayNode bindings = Json.F.arrayNode();
        ArrayNode fixed = Json.F.arrayNode();
        ArrayNode constraints = Json.F.arrayNode();
        ArrayNode uscdi = Json.F.arrayNode();
        for (JsonNode e : Json.list(sd.get("differential"), "element")) {
            String eid = Json.text(e, "id");
            JsonNode s = sn.byId().get(eid);
            if (s == null) {
                String via = sn.alias().get(eid);
                s = via == null ? null : sn.byId().get(via);
            }
            if (s == null) {
                warnings.add("no snapshot row for differential element " + eid);
                s = Json.object();
            }
            List<String> fromSnap = new ArrayList<>();
            JsonNode mn;
            if (e.has("min")) {
                mn = e.get("min");
            } else {
                mn = s.get("min");
                fromSnap.add("min");
            }
            JsonNode mx;
            if (e.has("max")) {
                mx = e.get("max");
            } else {
                mx = s.get("max");
                fromSnap.add("max");
            }
            JsonNode ms = e.get("mustSupport");
            if (ms == null || ms.isNull()) {
                ms = s.get("mustSupport");
            }
            ObjectNode row = Json.object();
            row.put("id", eid);
            row.put("path", Json.text(e, "path"));
            row.put("slice", Json.text(e, "sliceName"));
            row.set("min", Json.orNull(mn));
            row.set("max", Json.orNull(mx));
            row.put("ms", Json.truthy(ms));
            row.put("types", typesText(e));
            row.put("short", Json.text(e, "short"));
            row.set("from_snapshot", Json.arrayOf(fromSnap));
            rows.add(row);

            JsonNode b = e.get("binding");
            if (Json.truthy(b)) {
                String vs = Json.text(b, "valueSet");
                boolean vsFrom = false;
                if (vs.isEmpty()) {
                    vs = Json.text(s.get("binding"), "valueSet");
                    vsFrom = !vs.isEmpty();
                }
                ObjectNode bo = Json.object();
                bo.put("element", eid);
                bo.put("strength", Json.text(b, "strength"));
                bo.put("value_set", vs);
                bo.put("from_snapshot", vsFrom);
                bindings.add(bo);
            }
            for (String k : Json.fieldNames(e)) {
                if (k.startsWith("pattern") || k.startsWith("fixed")) {
                    ObjectNode fo = Json.object();
                    fo.put("element", eid);
                    fo.put("kind", k);
                    fo.put("value", fixedText(e.get(k)));
                    fixed.add(fo);
                }
            }
            for (JsonNode c : Json.list(e, "constraint")) {
                ObjectNode co = Json.object();
                co.put("key", Json.text(c, "key"));
                co.put("severity", Json.text(c, "severity"));
                co.put("human", Json.text(c, "human"));
                co.put("expression", Json.text(c, "expression"));
                constraints.add(co);
            }
            for (JsonNode x : Json.list(e, "extension")) {
                JsonNode vb = x.get("valueBoolean");
                if (Json.text(x, "url").endsWith("uscdi-requirement") && vb != null && vb.isBoolean()
                        && vb.asBoolean()) {
                    String prof = "";
                    for (JsonNode t : Json.list(e, "type")) {
                        if ("Extension".equals(Json.text(t, "code")) && Json.truthy(t.get("profile"))) {
                            prof = String.join(String.valueOf(Text.LINE_BREAK), Json.strings(t, "profile"));
                        }
                    }
                    ObjectNode uo = Json.object();
                    uo.put("element", eid);
                    uo.put("slice", Json.text(e, "sliceName"));
                    uo.set("min", Json.orNull(mn));
                    uo.set("max", Json.orNull(mx));
                    uo.put("profile_url", prof);
                    uscdi.add(uo);
                }
            }
        }
        d.set("rows", rows);
        d.set("bindings", bindings);
        d.set("fixed", fixed);
        d.set("constraints", constraints);
        d.set("uscdi", uscdi);
    }

    private static ArrayNode contextText(JsonNode sd) {
        ArrayNode out = Json.F.arrayNode();
        for (JsonNode c : Json.list(sd, "context")) {
            out.add(Json.text(c, "type") + ": " + Json.text(c, "expression"));
        }
        return out;
    }

    private static ArrayNode valueSetRules(JsonNode list) {
        ArrayNode out = Json.F.arrayNode();
        if (list == null || !list.isArray()) {
            return out;
        }
        for (JsonNode inc : list) {
            ObjectNode r = Json.object();
            r.put("system", Json.text(inc, "system"));
            r.put("version", Json.text(inc, "version"));
            ArrayNode concepts = Json.F.arrayNode();
            for (JsonNode c : Json.list(inc, "concept")) {
                ObjectNode co = Json.object();
                co.put("code", Json.text(c, "code"));
                co.put("display", Json.text(c, "display"));
                concepts.add(co);
            }
            r.set("concepts", concepts);
            ArrayNode filters = Json.F.arrayNode();
            for (JsonNode f : Json.list(inc, "filter")) {
                ObjectNode fo = Json.object();
                fo.put("property", Json.text(f, "property"));
                fo.put("op", Json.text(f, "op"));
                fo.put("value", Json.text(f, "value"));
                filters.add(fo);
            }
            r.set("filters", filters);
            r.set("value_sets", Json.arrayOf(Json.strings(inc, "valueSet")));
            out.add(r);
        }
        return out;
    }

    /** Facts for one source file. */
    ObjectNode extract(Path path) {
        JsonNode res = Json.read(path);
        String rt = Json.text(res, "resourceType");
        String rid = Json.text(res, "id");
        if (rid.isEmpty()) {
            throw new IllegalStateException(path.getFileName() + ": resource has no id");
        }
        String seg = segFor(res);
        String outFile = rt + "-" + rid + ".adoc";      // always <ResourceType>-<id>.adoc
        String title = Json.firstNonEmpty(Json.text(res, "title"), Json.text(res, "name"), rid);

        ObjectNode facts = Json.object();
        facts.put("source_file", path.getFileName().toString());
        facts.put("source_sha256", sha256(path));
        facts.put("resource_type", rt);
        facts.put("id", rid);
        facts.put("seg", seg);
        facts.put("topic_id", cfg.topicPrefix + "-" + seg + "-" + rid);
        facts.put("out_file", outFile);
        facts.put("title", title);
        facts.put("canonical", Json.text(res, "url"));
        facts.put("status", Json.text(res, "status"));
        facts.put("description_md", Json.text(res, "description"));
        facts.put("purpose_md", Json.text(res, "purpose"));
        ObjectNode publishing = facts.putObject("publishing");
        publishing.put("publisher", Json.text(res, "publisher"));
        publishing.put("contact", contactText(res));
        publishing.put("date", Json.text(res, "date"));
        publishing.put("url", Json.text(res, "url"));
        ObjectNode meta = facts.putObject("meta");
        meta.put("id", rid);
        meta.put("url", Json.text(res, "url"));
        meta.put("version", Json.text(res, "version"));
        meta.put("name", Json.text(res, "name"));
        meta.put("title", Json.text(res, "title"));
        meta.put("status", Json.text(res, "status"));
        meta.put("date", Json.text(res, "date"));
        meta.put("publisher", Json.text(res, "publisher"));
        meta.put("fhir_version", Json.text(res, "fhirVersion"));
        meta.put("base_definition", Json.text(res, "baseDefinition"));
        meta.put("kind", Json.text(res, "kind"));
        meta.set("type", res.has("type") && !res.get("type").isNull() ? res.get("type") : Json.F.textNode(""));   // kept as is: an OperationDefinition has a boolean
        meta.put("derivation", Json.text(res, "derivation"));
        meta.set("fmm", Json.orNull(extValue(res, "structuredefinition-fmm")));
        meta.set("wg", Json.orNull(extValue(res, "structuredefinition-wg")));
        meta.set("standards_status", Json.orNull(extValue(res, "structuredefinition-standards-status")));
        ObjectNode d = facts.putObject("data");
        ArrayNode warnings = facts.putArray("warnings");

        ObjectNode counts = Json.object();
        String fhirType = "";
        switch (rt) {
            case "StructureDefinition" -> {
                profileRows(res, d, warnings);
                d.set("contexts", contextText(res));
                counts.put("elements", d.get("rows").size());
                counts.put("bindings", d.get("bindings").size());
                counts.put("constraints", d.get("constraints").size());
                fhirType = Json.text(res, "type");
            }
            case "SearchParameter" -> {
                List<JsonNode> comps = Json.list(res, "comparator");
                List<JsonNode> cexts = Json.list(res, "_comparator");
                d.put("code", Json.text(res, "code"));
                List<String> base = Json.strings(res, "base");
                d.set("base", Json.arrayOf(base));
                d.put("type", Json.text(res, "type"));
                d.put("expression", Json.text(res, "expression"));
                d.put("derived_from", Json.text(res, "derivedFrom"));
                d.set("multiple_or", expectation(res.get("_multipleOr")));
                d.set("multiple_and", expectation(res.get("_multipleAnd")));
                ArrayNode cs = Json.F.arrayNode();
                for (int i = 0; i < comps.size(); i++) {
                    ObjectNode c = Json.object();
                    c.put("code", Json.str(comps.get(i)));
                    c.set("expectation", i < cexts.size() && Json.truthy(cexts.get(i))
                            ? expectation(cexts.get(i)) : Json.orNull(null));
                    cs.add(c);
                }
                d.set("comparators", cs);
                if (!comps.isEmpty() && cexts.size() != comps.size()) {
                    warnings.add("comparator/_comparator length mismatch");
                }
                counts.put("comparators", comps.size());
                fhirType = base.isEmpty() ? "" : base.get(0);
            }
            case "ValueSet" -> {
                JsonNode comp = res.get("compose");
                d.set("include", valueSetRules(comp == null ? null : comp.get("include")));
                d.set("exclude", valueSetRules(comp == null ? null : comp.get("exclude")));
                d.put("version_note", "");
                int concepts = 0;
                for (JsonNode r : d.get("include")) {
                    concepts += r.get("concepts").size();
                }
                counts.put("includes", d.get("include").size());
                counts.put("excludes", d.get("exclude").size());
                counts.put("concepts", concepts);
            }
            case "CodeSystem" -> {
                ArrayNode concepts = Json.F.arrayNode();
                for (JsonNode c : Json.list(res, "concept")) {
                    if (Json.truthy(c.get("concept"))) {
                        warnings.add("nested concepts in code system (not supported)");
                    }
                    ObjectNode co = Json.object();
                    co.put("code", Json.text(c, "code"));
                    co.put("display", Json.text(c, "display"));
                    co.put("definition", Json.text(c, "definition"));
                    concepts.add(co);
                }
                d.put("content", Json.text(res, "content"));
                d.set("concepts", concepts);
                counts.put("concepts", concepts.size());
            }
            case "CapabilityStatement" -> {
                List<JsonNode> rests = Json.list(res, "rest");
                if (rests.size() != 1) {
                    warnings.add("expected exactly one rest element, found " + rests.size());
                }
                JsonNode rest = rests.isEmpty() ? Json.object() : rests.get(0);
                d.put("kind", Json.text(res, "kind"));
                d.set("formats", Json.arrayOf(Json.strings(res, "format")));
                d.put("mode", Json.text(rest, "mode"));
                ArrayNode resources = Json.F.arrayNode();
                int interactions = 0;
                int searchParams = 0;
                for (JsonNode r : Json.list(rest, "resource")) {
                    ArrayNode sp = Json.F.arrayNode();
                    for (JsonNode p : Json.list(r, "searchParam")) {
                        ObjectNode po = Json.object();
                        po.put("name", Json.text(p, "name"));
                        po.put("type", Json.text(p, "type"));
                        po.put("definition", Json.text(p, "definition"));
                        po.set("expectation", expectation(p));
                        sp.add(po);
                    }
                    ArrayNode inter = Json.F.arrayNode();
                    for (JsonNode i : Json.list(r, "interaction")) {
                        ObjectNode io = Json.object();
                        io.put("code", Json.text(i, "code"));
                        io.set("expectation", expectation(i));
                        inter.add(io);
                    }
                    ArrayNode profs = Json.F.arrayNode();
                    List<JsonNode> sps = Json.list(r, "supportedProfile");
                    List<JsonNode> spx = Json.list(r, "_supportedProfile");
                    for (int i = 0; i < sps.size(); i++) {
                        ObjectNode pr = Json.object();
                        pr.put("url", Json.str(sps.get(i)));
                        pr.set("expectation", i < spx.size() && Json.truthy(spx.get(i))
                                ? expectation(spx.get(i)) : Json.orNull(null));
                        profs.add(pr);
                    }
                    ObjectNode beh = Json.object();
                    for (String k : List.of("versioning", "readHistory", "updateCreate", "conditionalCreate",
                            "conditionalRead", "conditionalUpdate", "conditionalDelete")) {
                        if (r.has(k)) {
                            beh.put(k, Json.str(r.get(k)));
                        }
                    }
                    if (Json.truthy(r.get("referencePolicy"))) {
                        beh.put("referencePolicy", String.join(", ", Json.strings(r, "referencePolicy")));
                    }
                    ObjectNode ro = Json.object();
                    ro.put("type", Json.text(r, "type"));
                    ro.set("expectation", expectation(r));
                    ro.set("interactions", inter);
                    ro.set("search_params", sp);
                    ro.set("profiles", profs);
                    ro.set("behavior", beh);
                    resources.add(ro);
                    interactions += inter.size();
                    searchParams += sp.size();
                }
                d.set("resources", resources);
                counts.put("resources", resources.size());
                counts.put("interactions", interactions);
                counts.put("search_params", searchParams);
            }
            case "OperationDefinition" -> {
                ArrayNode params = Json.F.arrayNode();
                for (JsonNode p : Json.list(res, "parameter")) {
                    if (Json.truthy(p.get("part"))) {
                        warnings.add("nested operation parameters (not supported)");
                    }
                    ObjectNode po = Json.object();
                    po.put("name", Json.text(p, "name"));
                    po.put("use", Json.text(p, "use"));
                    po.set("min", Json.orNull(p.get("min")));
                    po.put("max", Json.text(p, "max"));
                    po.put("type", Json.text(p, "type"));
                    po.put("documentation", Json.text(p, "documentation"));
                    params.add(po);
                }
                d.put("kind", Json.text(res, "kind"));
                d.put("code", Json.text(res, "code"));
                d.set("resource", Json.arrayOf(Json.strings(res, "resource")));
                d.set("system", Json.orNull(res.get("system")));
                d.set("type", Json.orNull(res.get("type")));
                d.set("instance", Json.orNull(res.get("instance")));
                d.set("parameters", params);
                counts.put("parameters", params.size());
            }
            default -> throw new IllegalStateException("unsupported resource type " + rt);
        }
        ObjectNode pi = facts.putObject("prose_inputs");
        pi.put("resource_type", rt);
        pi.put("subtype", SUBTYPE.get(seg));
        pi.put("id", rid);
        pi.put("name", Json.text(res, "name"));
        pi.put("title", title);
        pi.put("status", Json.text(res, "status"));
        pi.put("description", Json.text(res, "description"));
        pi.put("purpose", Json.text(res, "purpose"));
        pi.put("fhir_type", fhirType);
        pi.set("counts", counts);
        return facts;
    }

    static String sha256(Path p) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(Files.readAllBytes(p));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** CLI entry: {@code extract-facts --config CFG [FACTS_DIR]}. */
    int run(Args args, PrintStream out) {
        Path outDir = args.positional().isEmpty() ? cfg.workDir.resolve("facts") : Path.of(args.positional().get(0));
        List<Path> files = cfg.sourceFiles();
        List<String> unsupported = new ArrayList<>();
        for (Path p : files) {
            String rt = IgConfig.resourceTypeOf(p);
            if (rt == null || !IgConfig.SUPPORTED_TYPES.contains(rt)) {
                unsupported.add(rt);
                out.println("UNSUPPORTED resource type " + (rt == null ? "None" : "'" + rt + "'") + " in "
                        + p.getFileName());
            }
        }
        if (!unsupported.isEmpty()) {
            out.println("FAILED: " + unsupported.size() + " file(s) have an unsupported resource type. Add the type "
                    + "to \"skip_resource_types\" in the config to skip it on purpose.");
            return 2;
        }
        if (Files.isDirectory(outDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(outDir, "*.facts.json")) {
                for (Path stale : ds) {
                    Files.delete(stale);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        int n = 0;
        int warnTotal = 0;
        Set<String> seen = new HashSet<>();
        for (Path p : files) {
            ObjectNode facts = extract(p);
            String outFile = facts.get("out_file").asText();
            if (!seen.add(outFile)) {
                out.println("WARN " + outFile + " duplicate output file name (two source files share a resource type and id)");
                warnTotal++;
            }
            Json.writeText(outDir.resolve(outFile + ".facts.json"), Json.dumps(facts, 1, false));
            n++;
            ArrayNode w = (ArrayNode) facts.get("warnings");
            if (w.size() > 0) {
                warnTotal += w.size();
                List<String> first = new ArrayList<>();
                for (int i = 0; i < Math.min(3, w.size()); i++) {
                    first.add(w.get(i).asText());
                }
                out.println("WARN " + outFile + " " + first);
            }
        }
        out.println("facts written: " + n + ", warnings: " + warnTotal);
        if (warnTotal > 0) {
            out.println("FAILED: fix or explain every warning before rendering (exit 1)");
            return 1;
        }
        return 0;
    }
}
