package io.jenkins.plugins.jtm.importer;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class JtmTestCaseImportParserTest {

    @Test
    public void parsesMinimalBundle() throws Exception {
        String json = "{ \"version\": 1, \"testCases\": [ { \"title\": \"Hello\", \"steps\": [ { \"action\": \"A1\", \"expectedResult\": \"E1\" } ] } ] }";
        JtmTestCaseImportParser.ImportBundle b = JtmTestCaseImportParser.parse(json);
        assertThat(b.testCases).hasSize(1);
        assertThat(b.testCases.get(0).title).isEqualTo("Hello");
        assertThat(b.testCases.get(0).steps).hasSize(1);
        assertThat(b.testCases.get(0).steps.get(0).action).isEqualTo("A1");
    }
}
