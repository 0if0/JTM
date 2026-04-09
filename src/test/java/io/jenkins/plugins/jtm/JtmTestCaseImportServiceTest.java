package io.jenkins.plugins.jtm;

import io.jenkins.plugins.jtm.core.domain.TestCase;
import io.jenkins.plugins.jtm.core.service.TestCaseService;
import io.jenkins.plugins.jtm.importer.JtmTestCaseImportParser;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Optional;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class JtmTestCaseImportServiceTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void import_createsCasesAndSkipsDuplicateIds() throws Exception {
        String json = "{ \"version\": 1, \"testCases\": ["
            + "{ \"id\": \"TC-IMPORT-X1\", \"title\": \"First\", \"projectKey\": \"Demo\", \"type\": \"MANUAL\","
            + " \"steps\": [ { \"action\": \"Click\", \"expectedResult\": \"OK\" } ] },"
            + "{ \"id\": \"TC-IMPORT-X1\", \"title\": \"Dup\", \"steps\": [] },"
            + "{ \"id\": \"TC-IMPORT-X2\", \"title\": \"Second\", \"type\": \"AUTOMATED\", \"steps\": [] }"
            + "] }";
        JtmTestCaseImportParser.ImportBundle bundle = JtmTestCaseImportParser.parse(json);
        TestCaseService.ImportStats stats =
            TestCaseService.get().importTestCasesFromBundle(bundle, "tester");

        assertThat(stats.created).isEqualTo(2);
        assertThat(stats.skipped).isEqualTo(1);

        Optional<TestCase> a = TestCaseService.get().findById("TC-IMPORT-X1");
        assertThat(a).isPresent();
        assertThat(a.get().getTitle()).isEqualTo("First");
        assertThat(a.get().getSteps()).hasSize(1);

        assertThat(TestCaseService.get().findById("TC-IMPORT-X2")).isPresent();
    }
}
