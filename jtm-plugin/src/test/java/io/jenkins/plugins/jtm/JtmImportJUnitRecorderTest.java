package io.jenkins.plugins.jtm;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import io.jenkins.plugins.jtm.core.domain.TestCase;
import io.jenkins.plugins.jtm.core.domain.TestCaseResult;
import io.jenkins.plugins.jtm.core.domain.TestRun;
import io.jenkins.plugins.jtm.core.service.TestCaseService;
import io.jenkins.plugins.jtm.persistence.JtmStore;
import io.jenkins.plugins.jtm.postbuild.JtmImportJUnitRecorder;
import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end for the freestyle post-build step: workspace file → imported results.
 */
public class JtmImportJUnitRecorderTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private TestCaseService service;
    private JtmStore store;

    @Before
    public void setUp() {
        service = TestCaseService.get();
        store = JtmStore.get();
    }

    private static TestBuilder writeSampleJUnitFile(String xml) {
        return new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException, java.io.IOException {
                FilePath ws = build.getWorkspace();
                assertThat(ws).isNotNull();
                ws.child("target/surefire-reports").mkdirs();
                ws.child("target/surefire-reports/TEST-sample.xml").write(xml, StandardCharsets.UTF_8.name());
                return true;
            }
        };
    }

    @Test
    public void postBuild_importsJUnitIntoNewRun() throws Exception {
        service.createTestCaseWithFixedId(
            "TC-JUNIT-IMPORT-1", "JUnit one", TestCase.TestCaseType.AUTOMATED, TestCase.Priority.MEDIUM, "it");
        service.createTestCaseWithFixedId(
            "TC-JUNIT-IMPORT-2", "JUnit two", TestCase.TestCaseType.AUTOMATED, TestCase.Priority.MEDIUM, "it");

        String xml;
        try (InputStream in = JtmImportJUnitRecorderTest.class.getResourceAsStream("/fixtures/sample-junit-jtm.xml")) {
            assertThat(in).isNotNull();
            xml = IOUtils.toString(in, StandardCharsets.UTF_8);
        }

        FreeStyleProject p = j.createFreeStyleProject("jtm-import-junit");
        p.getBuildersList().add(writeSampleJUnitFile(xml));
        p.getPublishersList().add(new JtmImportJUnitRecorder(
            "target/surefire-reports/TEST-sample.xml",
            "",
            "",
            "",
            false));

        j.assertBuildStatusSuccess(p.scheduleBuild2(0));

        List<TestRun> recent = store.findRecentRuns(20);
        Optional<TestRun> imported = recent.stream()
            .filter(r -> "jtm-import-junit".equals(r.getJobName()))
            .findFirst();
        assertThat(imported).isPresent();
        TestRun run = imported.get();
        TestCaseResult r1 = run.getResultFor("TC-JUNIT-IMPORT-1").orElseThrow();
        TestCaseResult r2 = run.getResultFor("TC-JUNIT-IMPORT-2").orElseThrow();
        assertThat(r1.getStatus()).isEqualTo(TestCaseResult.TestResultStatus.PASSED);
        assertThat(r2.getStatus()).isEqualTo(TestCaseResult.TestResultStatus.FAILED);
    }

    @Test
    public void postBuild_missingFile_marksUnstable() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("jtm-import-missing");
        p.getPublishersList().add(new JtmImportJUnitRecorder(
            "target/surefire-reports/DOES-NOT-EXIST.xml",
            "",
            "",
            "",
            false));

        j.assertBuildStatus(Result.UNSTABLE, p.scheduleBuild2(0).get());
    }
}
