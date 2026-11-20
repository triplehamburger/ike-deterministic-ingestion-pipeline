package network.ike.docs.fhirfragments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** JSON and file helpers shared by the tools. JSON files are read as UTF-8 with an optional BOM. */
final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final JsonNodeFactory F = JsonNodeFactory.instance;

    private Json() {
    }

    /** File text, UTF-8, a leading BOM removed. Line endings are kept as they are. */
    static String readText(Path p) {
        try {
            String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            return s.startsWith("﻿") ? s.substring(1) : s;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode read(Path p) {
        return parse(readText(p));
    }

    /** UTF-8 write with LF endings, creating parent folders. */
    static void writeText(Path p, String text) {
        try {
            Path parent = p.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(p, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Text of a field; empty when missing or null. Non-text values use their JSON text. */
    static String text(JsonNode n, String key) {
        return str(n == null ? null : n.get(key));
    }

    /** Text of a node; empty when missing or null. */
    static String str(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return "";
        }
        return v.isValueNode() ? v.asText() : v.toString();
    }

    /** Text of a node, or null when it is missing or JSON null. */
    static String strOrNull(JsonNode v) {
        return (v == null || v.isNull() || v.isMissingNode()) ? null : str(v);
    }

    /** A node that is never Java null: missing becomes JSON null. */
    static JsonNode orNull(JsonNode v) {
        return (v == null || v.isMissingNode()) ? NullNode.getInstance() : v;
    }

    /** Python truthiness of a JSON value. */
    static boolean truthy(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return false;
        }
        if (v.isTextual()) {
            return !v.asText().isEmpty();
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isNumber()) {
            return v.asDouble() != 0;
        }
        return v.size() > 0;
    }

    /** Elements of an array field (empty when the field is missing, null or not an array). */
    static List<JsonNode> list(JsonNode n, String key) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode a = n == null ? null : n.get(key);
        if (a != null && a.isArray()) {
            a.forEach(out::add);
        }
        return out;
    }

    /** String elements of an array field. */
    static List<String> strings(JsonNode n, String key) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : list(n, key)) {
            out.add(str(e));
        }
        return out;
    }

    static ArrayNode arrayOf(List<String> items) {
        ArrayNode a = F.arrayNode();
        items.forEach(a::add);
        return a;
    }

    /** First non-empty of the given texts. */
    static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ Python-style json.dumps

    /**
     * Serialise like Python's {@code json.dumps(..., ensure_ascii=False)}: {@code indent} less than zero gives
     * the compact form with ", " and ": " separators; zero or more gives one item per line.
     */
    static String dumps(JsonNode node, int indent, boolean sortKeys) {
        StringBuilder sb = new StringBuilder();
        dump(node, indent, sortKeys, 0, sb);
        return sb.toString();
    }

    private static void dump(JsonNode n, int indent, boolean sort, int level, StringBuilder sb) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            sb.append("null");
        } else if (n.isTextual()) {
            quote(n.asText(), sb);
        } else if (n.isBoolean()) {
            sb.append(n.asBoolean() ? "true" : "false");
        } else if (n.isNumber()) {
            sb.append(n.asText());
        } else if (n.isArray()) {
            if (n.size() == 0) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            boolean first = true;
            for (JsonNode e : n) {
                sb.append(first ? "" : (indent >= 0 ? "," : ", "));
                first = false;
                newline(indent, level + 1, sb);
                dump(e, indent, sort, level + 1, sb);
            }
            newline(indent, level, sb);
            sb.append(']');
        } else if (n.isObject()) {
            if (n.size() == 0) {
                sb.append("{}");
                return;
            }
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
                names.add(it.next());
            }
            if (sort) {
                Collections.sort(names);
            }
            sb.append('{');
            boolean first = true;
            for (String k : names) {
                sb.append(first ? "" : (indent >= 0 ? "," : ", "));
                first = false;
                newline(indent, level + 1, sb);
                quote(k, sb);
                sb.append(": ");
                dump(n.get(k), indent, sort, level + 1, sb);
            }
            newline(indent, level, sb);
            sb.append('}');
        } else {
            quote(n.asText(), sb);
        }
    }

    private static void newline(int indent, int level, StringBuilder sb) {
        if (indent >= 0) {
            sb.append('\n');
            for (int i = 0; i < indent * level; i++) {
                sb.append(' ');
            }
        }
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Object field names in order. */
    static List<String> fieldNames(JsonNode n) {
        List<String> out = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
            out.add(it.next().getKey());
        }
        return out;
    }

    static ObjectNode object() {
        return F.objectNode();
    }
}
