package network.ike.docs.fhirfragments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class ProseRulesTest {

    @TempDir
    Path tmp;

    private Linter linter;

    @BeforeEach
    void setUp() {
        IgConfig cfg = IgConfig.load(TestSupport.usCoreConfig(tmp, TestSupport.miniPackage()));
        linter = new Linter(cfg, new Renderer(cfg));
    }

    private static JsonNode prose(String keywords, String summary) {
        return Json.parse("{\"id\": \"x\", \"resource_type\": \"ValueSet\", \"keywords\": " + keywords
                + ", \"summary\": " + Json.dumps(Json.F.textNode(summary), -1, false) + "}");
    }

    private static final String GOOD = "Defines the value set of observation categories allowed for screening results.";

    @Test
    void goodProsePasses() {
        assertEquals(List.of(), linter.checkProseObj(prose("[\"terminology\", \"category\"]", GOOD)));
    }

    @Test
    void rulesRejectBadKeywordsAndSummaries() {
        assertTrue(linter.checkProseObj(prose("[\"us-core\", \"category\"]", GOOD)).get(0).contains("fixed keyword"));
        assertTrue(linter.checkProseObj(prose("[\"Category\", \"x\"]", GOOD)).get(0).contains("bad keyword"));
        assertTrue(linter.checkProseObj(prose("[\"one\"]", GOOD)).get(0).contains("2-5"));
        assertTrue(linter.checkProseObj(prose("[\"a\", \"a\"]", GOOD)).contains("duplicate keywords"));
        assertTrue(linter.checkProseObj(prose("[\"a\", \"b\"]", "Too short.")).get(0).contains("60-200"));
        assertTrue(linter.checkProseObj(prose("[\"a\", \"b\"]", GOOD.replace("allowed", "allowed. It")))
                .contains("summary must be exactly one sentence"));
        assertTrue(linter.checkProseObj(prose("[\"a\", \"b\"]", GOOD.replace("value set", "*value* set")))
                .stream().anyMatch(e -> e.contains("markup")));
        assertTrue(linter.checkProseObj(prose("[\"a\", \"b\"]", GOOD.replace("observation", "observation —")))
                .stream().anyMatch(e -> e.contains("em dash")));
        assertTrue(linter.checkProseObj(prose("[\"a\", \"b\"]", "This resource " + GOOD))
                .contains("summary starts with a banned phrase"));
    }

    @Test
    void rulesRejectMissingAndUnexpectedKeys() {
        assertEquals(List.of("missing key summary"),
                linter.checkProseObj(Json.parse("{\"id\":\"x\",\"resource_type\":\"ValueSet\",\"keywords\":[\"a\",\"b\"]}")));
        assertTrue(linter.checkProseObj(Json.parse("{\"error\": \"boom\"}")).get(0).startsWith("agent reported error"));
        JsonNode extra = Json.parse("{\"id\":\"x\",\"resource_type\":\"ValueSet\",\"keywords\":[\"a\",\"b\"],\"summary\":\""
                + GOOD + "\",\"overview_lead\":\"\"}");
        assertEquals(List.of("unexpected keys: [overview_lead]"), linter.checkProseObj(extra));
    }

    @Test
    void groundingRejectsNumbersAndUrlsThatAreNotInTheInputs() {
        JsonNode facts = Json.parse("{\"prose_inputs\": {\"description\": \"See http://x.org/a and 42 codes\"}}");
        assertEquals(List.of(), Linter.checkProseGrounding(
                prose("[\"a\", \"b\"]", "Lists 42 codes described at http://x.org/a for the guide of screening data."), facts));
        assertEquals(1, Linter.checkProseGrounding(
                prose("[\"a\", \"b\"]", "Lists 43 codes described for the guide of screening data in many places."), facts).size());
    }
}
