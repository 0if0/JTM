package io.jenkins.plugins.jtm;

import io.jenkins.plugins.jtm.core.domain.TestCaseResult;
import io.jenkins.plugins.jtm.postbuild.JUnitXmlImportParser;
import org.junit.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class JUnitXmlImportParserTest {

    @Test
    public void parse_sampleFixture_mapsTcIdsAndStatuses() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/sample-junit-jtm.xml")) {
            assertThat(in).isNotNull();
            JUnitXmlImportParser.ParseResult pr = JUnitXmlImportParser.parse(in);
            assertThat(pr.getProjectKey()).isEqualTo("jtm-sample");
            List<TestCaseResult> results = pr.getResults();
            assertThat(results).hasSize(2);
            assertThat(results.get(0).getTestCaseId()).isEqualTo("TC-JUNIT-IMPORT-1");
            assertThat(results.get(0).getStatus()).isEqualTo(TestCaseResult.TestResultStatus.PASSED);
            assertThat(results.get(1).getTestCaseId()).isEqualTo("TC-JUNIT-IMPORT-2");
            assertThat(results.get(1).getStatus()).isEqualTo(TestCaseResult.TestResultStatus.FAILED);
            assertThat(pr.getLinkedCaseIds()).containsExactly("TC-JUNIT-IMPORT-1", "TC-JUNIT-IMPORT-2");
        }
    }

    @Test
    public void toCaseId_prefersJtmStyleIdInName() {
        assertThat(JUnitXmlImportParser.toCaseId("any.Class", "TC-ABC-1")).isEqualTo("TC-ABC-1");
    }

    @Test
    public void toCaseId_generatesAutoWhenNotTcPrefixed() {
        assertThat(JUnitXmlImportParser.toCaseId("pkg.Foo", "testMethod"))
            .startsWith("AUTO-");
    }
}
