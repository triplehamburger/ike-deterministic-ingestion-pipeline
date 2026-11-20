package network.ike.docs.fhirfragments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Expected values were produced by the original Python implementation. */
class TextTest {

    @Test
    void attributeLineWrapsAt92WithBackslashAndTwoSpaceContinuation() {
        String sentence = "Defines the profile for conformance of patient demographics including birth sex and race.";
        String v = String.join(" ", sentence, sentence, sentence);
        String expected = ":topic-summary: Defines the profile for conformance of patient demographics including birth \\\n"
                + "  sex and race. Defines the profile for conformance of patient demographics including birth \\\n"
                + "  sex and race. Defines the profile for conformance of patient demographics including birth \\\n"
                + "  sex and race.\n";
        assertEquals(expected, Text.attributeLine("topic-summary", v));
    }

    @Test
    void attributeLineKeepsAnUnbreakableFirstWordInline() {
        String url = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-observation-clinical-test-result-category-extension-name";
        assertEquals(":topic-canonical: " + url + "\n", Text.attributeLine("topic-canonical", url));
    }

    @Test
    void attributeLineOfBlankValueIsEmpty() {
        assertEquals("", Text.attributeLine("topic-summary", "  \n "));
    }

    @Test
    void cellEscapesPipesCollapsesSpaceAndHonoursLineBreakMarker() {
        String lb = String.valueOf(Text.LINE_BREAK);
        assertEquals("a \\| b c +\nhttp://x +\ny", Text.cell("a | b\n  c" + lb + "http://x " + lb + "y"));
        assertEquals("", Text.cell(null));
    }

    @Test
    void mdToAdocConvertsLinksEmphasisAndBullets() {
        String md = "See [HL7](http://hl7.org/x) for **bold** and *italic* \\u0041\\u0042\n- one\n  - two\n\n\n[rel](foo.html) end  ";
        assertEquals("See http://hl7.org/x[HL7] for *bold* and _italic_ AB\n* one\n  * two\n\n\nrel end",
                Text.mdToAdoc(md));
        assertEquals("", Text.mdToAdoc(null));
    }

    @Test
    void whitespaceFollowsPythonSplitSemantics() {
        // NBSP and the ASCII unit separator are whitespace; the zero-width space is not
        assertEquals("a b c​d e", Text.normalizeSpace("a b\u001fc​d  e"));
        assertEquals(List.of("x", "y"), Text.splitWords("  x \t y\n"));
    }

    @Test
    void lengthCountsCodePoints() {
        assertEquals(1, Text.len("😀"));
        assertEquals("😀a", Text.head("😀ab", 2));
    }

    @Test
    void pythonStyleJsonDumps() throws Exception {
        var node = Json.parse("{\"b\": 1, \"a\": [1, 2, {\"z\": null}], \"c\": \"x\\ny\\u00e9\", \"d\": []}");
        assertEquals("{\"a\": [1, 2, {\"z\": null}], \"b\": 1, \"c\": \"x\\nyé\", \"d\": []}",
                Json.dumps(node, -1, true));
        String pretty = Json.dumps(node, 1, false);
        assertTrue(pretty.startsWith("{\n \"b\": 1,\n \"a\": [\n  1,\n  2,"), pretty);
    }

    @Test
    void splitBeforeKeepsLeadingEmptyPart() {
        var p = Text.re("^(?=== )", java.util.regex.Pattern.MULTILINE);
        assertEquals(List.of("", "== A\nx\n", "== B\n"), Text.splitBefore(p, "== A\nx\n== B\n"));
        assertEquals(List.of("pre\n", "== A\n"), Text.splitBefore(p, "pre\n== A\n"));
        assertEquals(List.of("none"), Text.splitBefore(p, "none"));
    }
}
