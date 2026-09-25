package net.rabbitware.config.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Reading the analyzer's output, one JSON object per line. */
class FindingTest {

    /** A line exactly as the analyzer's encoder writes one. */
    private static final String LINE = "{\"severity\":\"ERROR\",\"rule\":\"unknown-property\","
        + "\"file\":\"/p/src/main/java/App.java\",\"line\":12,\"column\":30,\"length\":6,"
        + "\"message\":\"property `prot` is not declared in rwconfig - did you mean `port`?\"}";

    @Test
    void readsEveryField() {
        Finding finding = Finding.parse(LINE);
        assertEquals("ERROR", finding.severity());
        assertEquals("unknown-property", finding.rule());
        assertEquals("/p/src/main/java/App.java", finding.file());
        assertEquals(12, finding.line());
        assertEquals(30, finding.column());
        assertEquals("property `prot` is not declared in rwconfig - did you mean `port`?", finding.message());
        assertTrue(finding.isError());
    }

    @Test
    @DisplayName("describes itself the way javac does, so IDEs and CI logs link to it")
    void describe() {
        assertEquals(
            "/p/src/main/java/App.java:12:30: error: property `prot` is not declared in rwconfig"
                + " - did you mean `port`? [unknown-property]",
            Finding.parse(LINE).describe());
    }

    @Test
    @DisplayName("a finding about the whole file has no line or column to show")
    void wholeFile() {
        Finding finding = Finding.parse("{\"severity\":\"WARNING\",\"rule\":\"unknown-setting\","
            + "\"file\":\"rwconfig\",\"line\":0,\"column\":0,\"length\":0,\"message\":\"m\"}");
        assertEquals("rwconfig: warning: m [unknown-setting]", finding.describe());
        assertFalse(finding.isError());
    }

    @Test
    @DisplayName("every escape the analyzer's encoder writes is read back")
    void escapes() {
        // the encoder escapes a quote, a backslash, \n \r \t, and other control
        // characters as \\u00XX
        Finding finding = Finding.parse("{\"severity\":\"INFO\",\"rule\":\"r\",\"file\":\"C:\\\\dir\\\\A.java\","
            + "\"line\":1,\"column\":1,\"length\":0,"
            + "\"message\":\"a \\\"quoted\\\" \\\\ back\\nnew\\rret\\ttab\\u0001ctl\\u0041\"}");
        assertEquals("C:\\dir\\A.java", finding.file());
        assertEquals("a \"quoted\" \\ back\nnew\rret\ttab\u0001ctlA", finding.message());
    }

    @Test
    @DisplayName("the order of the fields does not matter, and whitespace between them is fine")
    void fieldOrderAndWhitespace() {
        Finding finding = Finding.parse(" { \"message\" : \"m\" , \"line\" : 3 , \"column\":4, \"file\":\"f\","
            + " \"rule\":\"r\", \"severity\":\"WARNING\", \"length\": 0 } ");
        assertEquals("f", finding.file());
        assertEquals(3, finding.line());
        assertEquals("m", finding.message());
    }

    @Test
    @DisplayName("text that is not a JSON number or string - a true, a null - is refused")
    void onlyStringsAndNumbers() {
        assertThrows(IllegalArgumentException.class, () -> Finding.parse(
            "{\"severity\":\"ERROR\",\"rule\":\"r\",\"file\":\"f\",\"line\":null,\"column\":1,\"message\":\"m\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not json at all",
        "SLF4J(W): No SLF4J providers were found.",
        "{\"severity\":\"ERROR\"",
        "{\"severity\":\"ERROR\",\"rule\":\"r\",\"file\":\"f\",\"line\":1,\"column\":1,\"message\":\"m\"} trailing",
        "{\"severity\":\"ERROR\",\"rule\":\"r\",\"file\":\"f\",\"line\":1,\"column\":1,\"message\":\"unterminated}",
        "{\"severity\":\"ERROR\",\"rule\":\"r\",\"file\":\"f\",\"line\":1,\"column\":1,\"message\":\"bad \\q\"}",
        "{\"severity\":\"ERROR\",\"rule\":\"r\",\"file\":\"f\",\"line\":1,\"column\":1,\"message\":\"\\u12\"}",
        "{\"severity\":\"ERROR\",\"rule\":\"r\",\"line\":1,\"column\":1,\"message\":\"no file\"}",
        "{\"severity\":\"ERROR\",\"rule\":\"r\",\"file\":\"f\",\"line\":\"1\",\"column\":1,\"message\":\"m\"}",
    })
    @DisplayName("anything that is not a complete finding is an error, never skipped - it would be a lost finding")
    void malformedLinesAreRejected(String line) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Finding.parse(line));
        assertTrue(e.getMessage().contains(line), "the message should quote the line: " + e.getMessage());
    }
}
