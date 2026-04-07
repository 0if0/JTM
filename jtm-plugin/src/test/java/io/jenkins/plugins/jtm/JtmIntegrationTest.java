package io.jenkins.plugins.jtm;

import hudson.model.Result;
import hudson.security.csrf.CrumbIssuer;
import io.jenkins.plugins.jtm.core.domain.TestCase;
import io.jenkins.plugins.jtm.core.domain.TestCaseResult;
import io.jenkins.plugins.jtm.core.domain.TestRun;
import io.jenkins.plugins.jtm.core.domain.TestStep;
import io.jenkins.plugins.jtm.core.service.QualityGateService;
import io.jenkins.plugins.jtm.core.service.TestCaseService;
import io.jenkins.plugins.jtm.persistence.JtmStore;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.apache.commons.io.IOUtils;
import org.htmlunit.FormEncodingType;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test suite for JTM.
 *
 * NOTE: All enum values are referenced via their full qualified inner-class path
 * (e.g. TestCase.TestCaseStatus.PASSED, TestCaseResult.TestResultStatus.PASSED)
 * to avoid javac ambiguity — both enums share the same constant names.
 */
public class JtmIntegrationTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private TestCaseService service;
    private JtmStore store;

    @Before
    public void setUp() {
        service = TestCaseService.get();
        store   = JtmStore.get();
    }

    // ── TestCase CRUD ─────────────────────────────────────────────────────────

    @Test
    public void createTestCase_success() {
        TestCase tc = service.createTestCase(
            "Login Test",
            TestCase.TestCaseType.AUTOMATED,
            TestCase.Priority.HIGH,
            "tester");

        assertThat(tc.getId()).startsWith("TC-");
        assertThat(tc.getTitle()).isEqualTo("Login Test");
        assertThat(tc.getType()).isEqualTo(TestCase.TestCaseType.AUTOMATED);
        assertThat(tc.getPriority()).isEqualTo(TestCase.Priority.HIGH);
        assertThat(tc.getLastStatus()).isEqualTo(TestCase.TestCaseStatus.PENDING);
        assertThat(tc.getVersion()).isEqualTo(1);
        assertThat(tc.getFlakyScore()).isEqualTo(0);
        assertThat(tc.getCreatedBy()).isEqualTo("tester");
        assertThat(tc.isFlaky()).isFalse();
    }

    @Test
    public void createTestCase_withStepsDescriptionTags_persisted() {
        List<TestStep> raw = new ArrayList<>();
        TestStep s1 = new TestStep();
        s1.setOrderIndex(1);
        s1.setAction("Open login page");
        s1.setExpectedResult("Form visible");
        raw.add(s1);
        TestStep empty = new TestStep();
        empty.setAction("");
        empty.setExpectedResult("");
        raw.add(empty);

        TestCase tc = service.createTestCase(
            "Manual flow",
            TestCase.TestCaseType.MANUAL,
            TestCase.Priority.MEDIUM,
            "Short description",
            List.of("smoke", "login"),
            raw,
            "qa");

        assertThat(tc.getDescription()).isEqualTo("Short description");
        assertThat(tc.getTags()).containsExactly("smoke", "login");
        assertThat(tc.getSteps()).hasSize(1);
        assertThat(tc.getSteps().get(0).getAction()).isEqualTo("Open login page");
        assertThat(tc.getSteps().get(0).getExpectedResult()).isEqualTo("Form visible");
        assertThat(tc.getSteps().get(0).getOrderIndex()).isEqualTo(1);

        assertThat(service.findById(tc.getId()).orElseThrow().getSteps()).hasSize(1);
    }

    @Test
    public void createTestCase_blankTitle_throws() {
        assertThatThrownBy(() ->
            service.createTestCase("  ", TestCase.TestCaseType.MANUAL, TestCase.Priority.MEDIUM, "user"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");
    }

    @Test
    public void createTestCase_titleTooLong_throws() {
        assertThatThrownBy(() ->
            service.createTestCase("x".repeat(501), TestCase.TestCaseType.MANUAL, TestCase.Priority.LOW, "user"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("500");
    }

    @Test
    public void ui_newCasePage_renders() throws Exception {
        String html = IOUtils.toString(
            new URL(j.getURL() + "jtm/testcases/newcase"), StandardCharsets.UTF_8);
        assertThat(html).contains("New Test Case");
        assertThat(html).contains("Test steps");
    }

    @Test
    public void ui_editTestCasePage_renders() throws Exception {
        TestCase tc = service.createTestCase(
            "Edit Page TC", TestCase.TestCaseType.MANUAL, TestCase.Priority.MEDIUM, "user");
        String html = IOUtils.toString(
            new URL(j.getURL() + "jtm/testcases/" + tc.getId() + "/edit"), StandardCharsets.UTF_8);
        assertThat(html).contains("Edit Test Case");
        assertThat(html).contains("Test steps");
    }

    /**
     * IDs must not be limited to {@code TC-\\d+}; otherwise detail/edit URLs 404 for API or imported cases.
     */
    @Test
    public void ui_testCaseIdWithSuffix_detailAndEdit_reachable() throws Exception {
        service.createTestCaseWithFixedId(
            "TC-BETA-1",
            "Non-numeric id",
            TestCase.TestCaseType.MANUAL,
            TestCase.Priority.MEDIUM,
            "user");
        String base = j.getURL() + "jtm/testcases/TC-BETA-1";
        String detail = IOUtils.toString(new URL(base + "/"), StandardCharsets.UTF_8);
        assertThat(detail).contains("Non-numeric id");
        assertThat(detail).contains("/jtm/testcases/TC-BETA-1/delete");
        String edit = IOUtils.toString(new URL(base + "/edit"), StandardCharsets.UTF_8);
        assertThat(edit).contains("Edit Test Case");
    }

    @Test
    public void ui_apiDashboardSummary_returnsJson() throws Exception {
        String json = IOUtils.toString(
            new URL(j.getURL() + "jtm/api/dashboard/summary"), StandardCharsets.UTF_8);
        assertThat(json).contains("\"statusCounts\"");
        assertThat(json).contains("\"passRate\"");
    }

    @Test
    public void ui_newRunPage_renders() throws Exception {
        String html = IOUtils.toString(
            new URL(j.getURL() + "jtm/runs/newrun"), StandardCharsets.UTF_8);
        assertThat(html).contains("New Test Run");
        assertThat(html).contains("jtm/saverun");
    }

    @Test
    public void ui_saverun_createsRunAndRedirects() throws Exception {
        CrumbIssuer issuer = j.jenkins.getCrumbIssuer();
        assertThat(issuer).isNotNull();
        JenkinsRule.WebClient wc = j.createWebClient();
        WebRequest req = new WebRequest(new URL(j.getURL() + "jtm/saverun"), HttpMethod.POST);
        req.setEncodingType(FormEncodingType.URL_ENCODED);
        List<NameValuePair> pairs = new ArrayList<>();
        pairs.add(new NameValuePair("jobName", "ui-job"));
        pairs.add(new NameValuePair("buildNumber", "7"));
        pairs.add(new NameValuePair("name", "Manual smoke"));
        pairs.add(new NameValuePair(issuer.getCrumbRequestField(), issuer.getCrumb()));
        req.setRequestParameters(pairs);

        HtmlPage page = wc.getPage(req);
        assertThat(page.getUrl().toString()).contains("/jtm/runs/RUN-");

        TestRun run = store.findRecentRuns(1).get(0);
        assertThat(run.getJobName()).isEqualTo("ui-job");
        assertThat(run.getBuildNumber()).isEqualTo(7);
        assertThat(run.getName()).isEqualTo("Manual smoke");
        assertThat(run.getStatus()).isEqualTo(TestRun.RunStatus.PARTIAL);
    }

    @Test
    public void findById_existing() {
        TestCase tc = service.createTestCase("Find Me", TestCase.TestCaseType.MANUAL, TestCase.Priority.LOW, "user");
        assertThat(service.findById(tc.getId()))
            .isPresent().get()
            .extracting(TestCase::getTitle)
            .isEqualTo("Find Me");
    }

    @Test
    public void findById_notFound_returnsEmpty() {
        assertThat(service.findById("TC-XXXX")).isEmpty();
    }

    @Test
    public void findPaginated_statusFilter() {
        for (int i = 0; i < 3; i++) {
            TestCase tc = service.createTestCase("Pass " + i, TestCase.TestCaseType.AUTOMATED, TestCase.Priority.HIGH, "u");
            service.updateStatus(tc.getId(), TestCase.TestCaseStatus.PASSED, "ci", i + 1, 0);
        }
        for (int i = 0; i < 2; i++) {
            TestCase tc = service.createTestCase("Fail " + i, TestCase.TestCaseType.MANUAL, TestCase.Priority.MEDIUM, "u");
            service.updateStatus(tc.getId(), TestCase.TestCaseStatus.FAILED, "ci", i + 1, 0);
        }

        TestCaseService.PagedResult<TestCase> result =
            service.findPaginated(0, 50, "FAILED", null, null, null);

        assertThat(result.getTotalCount()).isGreaterThanOrEqualTo(2);
        assertThat(result.getItems())
            .allMatch(tc -> tc.getLastStatus() == TestCase.TestCaseStatus.FAILED);
    }

    @Test
    public void findPaginated_typeFilter() {
        service.createTestCase("Auto Test",   TestCase.TestCaseType.AUTOMATED, TestCase.Priority.HIGH, "u");
        service.createTestCase("Manual Test", TestCase.TestCaseType.MANUAL,    TestCase.Priority.HIGH, "u");

        TestCaseService.PagedResult<TestCase> result =
            service.findPaginated(0, 50, null, "AUTOMATED", null, null);

        assertThat(result.getItems())
            .allMatch(tc -> tc.getType() == TestCase.TestCaseType.AUTOMATED);
    }

    @Test
    public void findPaginated_suiteFilter() {
        TestCase inSuite = service.createTestCase("In Suite", TestCase.TestCaseType.AUTOMATED, TestCase.Priority.HIGH, "u");
        service.updateTestCase(inSuite.getId(),
            inSuite.toBuilder().parentSuiteId("SUITE-0001").build(), "u");
        service.createTestCase("No Suite", TestCase.TestCaseType.AUTOMATED, TestCase.Priority.HIGH, "u");

        TestCaseService.PagedResult<TestCase> result =
            service.findPaginated(0, 50, null, null, "SUITE-0001", null);

        assertThat(result.getTotalCount()).isEqualTo(1);
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getParentSuiteId()).isEqualTo("SUITE-0001");
    }

    @Test
    public void createTestCaseWithFixedId_success() {
        TestCase tc = service.createTestCaseWithFixedId(
            "TC-4242",
            "External id",
            TestCase.TestCaseType.AUTOMATED,
            TestCase.Priority.HIGH,
            "ci");

        assertThat(tc.getId()).isEqualTo("TC-4242");
        assertThat(service.findById("TC-4242")).isPresent();
    }

    @Test
    public void updateTestCaseContent_preservesLastStatus_addsSteps() {
        TestCase tc = service.createTestCase(
            "With steps", TestCase.TestCaseType.MANUAL, TestCase.Priority.MEDIUM, "u");
        service.updateStatus(tc.getId(), TestCase.TestCaseStatus.PASSED, "ci", 1, 0);

        List<TestStep> steps = List.of(
            new TestStep(1, "Open login", "Form visible"),
            new TestStep(2, "Submit", "Redirect"));

        TestCase updated = service.updateTestCaseContent(
            tc.getId(),
            "With steps v2",
            "Desc",
            "Pre",
            "Overall ok",
            steps,
            TestCase.TestCaseType.MANUAL,
            TestCase.Priority.HIGH,
            TestCase.Risk.LOW,
            TestCase.LifecycleStatus.READY,
            List.of("smoke"),
            "REQ-1",
            "JIRA-9",
            "ProjA",
            "editor");

        assertThat(updated.getTitle()).isEqualTo("With steps v2");
        assertThat(updated.getSteps()).hasSize(2);
        assertThat(updated.getLastStatus()).isEqualTo(TestCase.TestCaseStatus.PASSED);
        assertThat(updated.getPriority()).isEqualTo(TestCase.Priority.HIGH);
        assertThat(updated.getTags()).containsExactly("smoke");
    }

    @Test
    public void createTestCaseWithFixedId_duplicate_throws() {
        service.createTestCaseWithFixedId(
            "TC-9191", "First", TestCase.TestCaseType.MANUAL, TestCase.Priority.LOW, "u");
        assertThatThrownBy(() ->
            service.createTestCaseWithFixedId(
                "TC-9191", "Second", TestCase.TestCaseType.MANUAL, TestCase.Priority.LOW, "u"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    public void deleteTestCase_removesFromStore() {
        TestCase tc = service.createTestCase("Delete Me", TestCase.TestCaseType.MANUAL, TestCase.Priority.LOW, "user");
        service.deleteTestCase(tc.getId(), "admin");
        assertThat(service.findById(tc.getId())).isEmpty();
    }

    // ── Status & Flaky Detection ──────────────────────────────────────────────

    @Test
    public void updateStatus_sameStatus_noFlakyIncrease() {
        TestCase tc = service.createTestCase("Stable", TestCase.TestCaseType.AUTOMATED, TestCase.Priority.HIGH, "u");
        service.updateStatus(tc.getId(), TestCase.TestCaseStatus.PASSED, "ci", 1, 8);
        TestCase tc2 = service.updateStatus(tc.getId(), TestCase.TestCaseStatus.PASSED, "ci", 2, 8);

        assertThat(tc2.getFlakyScore()).isEqualTo(0);
        assertThat(tc2.isFlaky()).isFalse();
    }

    @Test
    public void updateStatus_statusFlip_increasesFlakyScore() {
        TestCase tc = service.createTestCase("Flaky TC", TestCase.TestCaseType.AUTOMATED, TestCase.Priority.HIGH, "u");
        // PENDING→PASSED: no flip (first run doesn't count as flip)
        service.updateStatus(tc.getId(), TestCase.TestCaseStatus.PASSED, "ci", 1, 8);
        service.updateStatus(tc.getId(), TestCase.TestCaseStatus.FAILED, "ci", 2, 8); // +8
        service.updateStatus(tc.getId(), TestCase.TestCaseStatus.PASSED, "ci", 3, 8); // +8
        service.updateStatus(tc.getId(), TestCase.TestCaseStatus.FAILED, "ci", 4, 8); // +8
        service.updateStatus(tc.getId(), TestCase.TestCaseStatus.PASSED, "ci", 5, 8); // +8
        TestCase result = service.updateStatus(tc.getId(), TestCase.TestCaseStatus.FAILED, "ci", 6, 8); // +8

        assertThat(result.getFlakyScore()).isEqualTo(40);
        assertThat(result.isFlaky()).isTrue();
    }

    @Test
    public void flakyScore_cappedAt100() {
        TestCase tc = service.createTestCase("Very Flaky", TestCase.TestCaseType.AUTOMATED, TestCase.Priority.HIGH, "u");
        // Alternate 15 times — 14 flips × 8 = 112, capped at 100
        TestCase.TestCaseStatus[] statuses = new TestCase.TestCaseStatus[15];
        for (int i = 0; i < statuses.length; i++) {
            statuses[i] = (i % 2 == 0) ? TestCase.TestCaseStatus.PASSED : TestCase.TestCaseStatus.FAILED;
        }
        for (int i = 0; i < statuses.length; i++) {
            tc = service.updateStatus(tc.getId(), statuses[i], "ci", i + 1, 8);
        }
        assertThat(tc.getFlakyScore()).isLessThanOrEqualTo(100);
    }

    // ── Quality Gate ──────────────────────────────────────────────────────────

    @Test
    public void qualityGate_passRate_above_threshold_passes() {
        TestRun run = makeRun(9, 1, 0, 0); // 90%
        QualityGateService.QualityGateResult result =
            QualityGateService.get().evaluateRun(run, 80.0, -1, false, false);

        assertThat(result.isPassed()).isTrue();
        assertThat(result.getViolations()).isEmpty();
    }

    @Test
    public void qualityGate_passRate_below_threshold_fails() {
        TestRun run = makeRun(7, 3, 0, 0); // 70%
        QualityGateService.QualityGateResult result =
            QualityGateService.get().evaluateRun(run, 80.0, -1, false, false);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getViolations()).isNotEmpty();
        assertThat(result.getViolations().get(0)).contains("70");
    }

    @Test
    public void qualityGate_maxFailures_exceeded_fails() {
        TestRun run = makeRun(8, 3, 0, 0);
        QualityGateService.QualityGateResult result =
            QualityGateService.get().evaluateRun(run, 50.0, 2, false, false);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getViolations()).anyMatch(v -> v.contains("3"));
    }

    @Test
    public void qualityGate_blockOnBlocked_true_fails() {
        TestRun run = makeRun(9, 0, 1, 0);
        QualityGateService.QualityGateResult result =
            QualityGateService.get().evaluateRun(run, 90.0, -1, true, false);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getViolations()).anyMatch(v -> v.toLowerCase().contains("blocked"));
    }

    @Test
    public void qualityGate_blockOnBlocked_false_warns() {
        TestRun run = makeRun(9, 0, 1, 0);
        QualityGateService.QualityGateResult result =
            QualityGateService.get().evaluateRun(run, 80.0, -1, false, false);

        assertThat(result.isPassed()).isTrue();
        assertThat(result.getWarnings()).anyMatch(w -> w.toLowerCase().contains("blocked"));
    }

    // ── Pipeline Step Integration ─────────────────────────────────────────────

    @Test
    public void pipelineStep_updateTestCase_passed() throws Exception {
        TestCase tc = service.createTestCase(
            "Pipeline Test", TestCase.TestCaseType.AUTOMATED, TestCase.Priority.HIGH, "user");

        WorkflowJob job = j.createProject(WorkflowJob.class, "jtm-update-test");
        job.setDefinition(new CpsFlowDefinition(
            "node { updateTestCase testCaseId: '" + tc.getId() + "', status: 'PASSED' }", true));

        WorkflowRun run = j.assertBuildStatusSuccess(job.scheduleBuild2(0));
        j.assertLogContains("[JTM]", run);
        j.assertLogContains("PASSED", run);

        TestCase updated = service.getByIdOrThrow(tc.getId());
        assertThat(updated.getLastStatus()).isEqualTo(TestCase.TestCaseStatus.PASSED);
        assertThat(updated.getVersion()).isEqualTo(2);
    }

    @Test
    public void pipelineStep_invalidStatus_fails() throws Exception {
        TestCase tc = service.createTestCase(
            "Bad Status", TestCase.TestCaseType.AUTOMATED, TestCase.Priority.HIGH, "user");

        WorkflowJob job = j.createProject(WorkflowJob.class, "jtm-bad-status");
        job.setDefinition(new CpsFlowDefinition(
            "node { updateTestCase testCaseId: '" + tc.getId() + "', status: 'INVALID' }", true));

        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
    }

    @Test
    public void pipelineStep_notFound_noFail_continues() throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "jtm-notfound");
        job.setDefinition(new CpsFlowDefinition(
            "node { updateTestCase testCaseId: 'TC-9999', status: 'PASSED', failOnNotFound: false }", true));

        j.assertBuildStatusSuccess(job.scheduleBuild2(0));
    }

    // ── Helpers — deliberately named makeRun / addRun to avoid ambiguity ──────

    /** Creates a finished TestRun with the given result distribution. */
    private TestRun makeRun(int passed, int failed, int blocked, int skipped) {
        TestRun run = new TestRun(store.generateRunId(), "test-job", 1);
        addRunResults(run, passed,  TestCaseResult.TestResultStatus.PASSED);
        addRunResults(run, failed,  TestCaseResult.TestResultStatus.FAILED);
        addRunResults(run, blocked, TestCaseResult.TestResultStatus.BLOCKED);
        addRunResults(run, skipped, TestCaseResult.TestResultStatus.SKIPPED);
        run.finish();
        store.saveRun(run);
        return run;
    }

    private void addRunResults(TestRun run, int count, TestCaseResult.TestResultStatus status) {
        for (int i = 0; i < count; i++) {
            run.addResult(new TestCaseResult("TC-MOCK-" + status.name() + "-" + i, status, 100L));
        }
    }
}
