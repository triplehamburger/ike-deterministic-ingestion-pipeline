package network.ike.docs.fhirfragments;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The per-IG configuration (the contents of {@code ig-config.json}). Paths in the file are relative to the
 * file's own folder.
 */
final class IgConfig {
    static final String DEFAULT_LICENSE = "Fair use summary of copyrighted work — not for redistribution.";

    /** Resource types the extractor and renderer handle. */
    static final Set<String> SUPPORTED_TYPES = new LinkedHashSet<>(List.of(
            "StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem", "CapabilityStatement",
            "OperationDefinition"));

    /** Output segments in the order of {@code _includes.adoc}, with their headings. */
    static final Map<String, String> SEGMENTS = new LinkedHashMap<>();

    static {
        SEGMENTS.put("profiles", "Profiles");
        SEGMENTS.put("extensions", "Extensions");
        SEGMENTS.put("searchparams", "Search Parameters");
        SEGMENTS.put("valuesets", "Value Sets");
        SEGMENTS.put("codesystems", "Code Systems");
        SEGMENTS.put("capabilitystatements", "Capability Statements");
        SEGMENTS.put("operations", "Operations");
    }

    final Path configDir;
    final Path srcDir;
    final Path workDir;
    final String igSlug;
    final String igDirName;
    final String igVersion;
    final String fhirVersion;
    final String citation;
    final String license;
    final String igUrl;
    final String canonicalNamespace;
    final String topicPrefix;
    private final Set<String> skipNames;
    private final List<String> skipPrefixes;
    private final List<String> skipSuffixes;
    private final Set<String> skipResourceTypes;

    private IgConfig(Path file, JsonNode c) {
        configDir = file.toAbsolutePath().getParent();
        srcDir = configDir.resolve(required(c, "source_dir")).normalize();
        workDir = configDir.resolve(optional(c, "work_dir", "work")).normalize();
        igSlug = required(c, "ig_slug");
        igDirName = required(c, "ig_dir_name");
        igVersion = required(c, "ig_version");
        fhirVersion = required(c, "fhir_version");
        citation = required(c, "citation");
        license = optional(c, "license", DEFAULT_LICENSE);
        igUrl = required(c, "ig_url");
        canonicalNamespace = required(c, "canonical_namespace");
        topicPrefix = optional(c, "topic_prefix", "ext-standards-" + igSlug);
        skipNames = new LinkedHashSet<>(c.has("skip_names") ? Json.strings(c, "skip_names")
                : Arrays.asList(".index.json", "package.json", "validation-oo.json", "validation-summary.json",
                "Parameters-manifest.json"));
        skipPrefixes = c.has("skip_prefixes") ? Json.strings(c, "skip_prefixes")
                : Arrays.asList("Basic-", "ImplementationGuide-");
        skipSuffixes = c.has("skip_suffixes") ? Json.strings(c, "skip_suffixes") : List.of(".openapi.json");
        skipResourceTypes = new LinkedHashSet<>(Json.strings(c, "skip_resource_types"));
    }

    private static String required(JsonNode c, String key) {
        JsonNode v = c.get(key);
        if (v == null || v.isNull()) {
            throw new CliException("config key missing: " + key);
        }
        return v.asText();
    }

    private static String optional(JsonNode c, String key, String dflt) {
        JsonNode v = c.get(key);
        return (v == null || v.isNull()) ? dflt : v.asText();
    }

    /** Load a config file. */
    static IgConfig load(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new CliException("config file not found: " + file);
        }
        return new IgConfig(file, Json.read(file));
    }

    /** Resource type of a JSON file, or null when it cannot be read or is not a resource. */
    static String resourceTypeOf(Path p) {
        try {
            JsonNode j = Json.read(p);
            return (j != null && j.isObject() && j.has("resourceType")) ? j.get("resourceType").asText() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Source manifest: top-level *.json files minus the config's skip names, prefixes, suffixes and types. */
    List<Path> sourceFiles() {
        List<String> names = new ArrayList<>();
        try (Stream<Path> s = Files.list(srcDir)) {
            s.forEach(p -> names.add(p.getFileName().toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        names.sort(String::compareTo);
        List<Path> out = new ArrayList<>();
        for (String n : names) {
            if (!n.endsWith(".json") || skipNames.contains(n)
                    || skipPrefixes.stream().anyMatch(n::startsWith)
                    || skipSuffixes.stream().anyMatch(n::endsWith)) {
                continue;
            }
            Path p = srcDir.resolve(n);
            if (!Files.isRegularFile(p)) {
                continue;
            }
            String rt = resourceTypeOf(p);
            if (rt != null && skipResourceTypes.contains(rt)) {
                continue;
            }
            out.add(p);
        }
        return out;
    }
}
