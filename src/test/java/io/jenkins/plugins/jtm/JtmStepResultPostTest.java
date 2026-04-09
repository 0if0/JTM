package io.jenkins.plugins.jtm;

import hudson.security.csrf.CrumbIssuer;
import io.jenkins.plugins.jtm.core.domain.TestCase;
import io.jenkins.plugins.jtm.core.domain.TestCaseResult;
import io.jenkins.plugins.jtm.core.domain.TestRun;
import io.jenkins.plugins.jtm.core.domain.TestStep;
import io.jenkins.plugins.jtm.core.service.TestCaseService;
import io.jenkins.plugins.jtm.core.service.TestRunService;
import io.jenkins.plugins.jtm.persistence.JtmStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.commons.io.IOUtils;
import org.htmlunit.FormEncodingType;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
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

/**
 * POST step results: {@code stepStatus} must be submitted (see {@code TestRunDetailAction#doAddStepResult}).
 */
public class JtmStepResultPostTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private TestCaseService service;
    private TestRunService runService;
    private JtmStore store;

    @Before
    public void setUp() {
        service = TestCaseService.get();
        runService = TestRunService.get();
        store = JtmStore.get();
    }

    /** Same mapper shape as {@link io.jenkins.plugins.jtm.persistence.JtmStore} (field-backed stepStatuses). */
    @Test
    public void testCaseResult_stepStatuses_jsonRoundTrip() throws Exception {
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        TestCaseResult r = new TestCaseResult("TC-1", TestCaseResult.TestResultStatus.PENDING, 0L);
        r.setStepStatuses(new ArrayList<>(List.of(TestStep.StepStatus.PASSED)));
        String json = om.writeValueAsString(r);
        assertThat(json).contains("PASSED");
        TestCaseResult r2 = om.readValue(json, TestCaseResult.class);
        assertThat(r2.getStepStatuses()).containsExactly(TestStep.StepStatus.PASSED);
    }

    @Test
    public void addStepResult_post_persistsStepStatuses() throws Exception {
        List<TestStep> steps = List.of(
            new TestStep(0, "First", "e1"),
            new TestStep(1, "Second", "e2"));
        TestCase tc = service.createTestCase(
            "Case with steps",
            TestCase.TestCaseType.MANUAL,
            TestCase.Priority.MEDIUM,
            "",
            List.of(),
            steps,
            "u");

        TestRun run = runService.createAdHocRun(
            "Run steps", "manual", 1, "", "n", List.of(tc.getId()), "u", "");

        CrumbIssuer issuer = j.jenkins.getCrumbIssuer();
        assertThat(issuer).isNotNull();

        JenkinsRule.WebClient wc = j.createWebClient();
        WebRequest req = new WebRequest(
            new URL(j.getURL() + "jtm/runs/" + run.getId() + "/addStepResult"), HttpMethod.POST);
        req.setEncodingType(FormEncodingType.URL_ENCODED);
        List<NameValuePair> pairs = new ArrayList<>();
        pairs.add(new NameValuePair(issuer.getCrumbRequestField(), issuer.getCrumb()));
        pairs.add(new NameValuePair("testCaseId", tc.getId()));
        pairs.add(new NameValuePair("stepStatus", "PASSED"));
        pairs.add(new NameValuePair("stepStatus", "FAILED"));
        req.setRequestParameters(pairs);

        HtmlPage page = wc.getPage(req);
        assertThat(page.getUrl().toString()).contains("/jtm/runs/" + run.getId() + "/");

        TestRun reloaded = store.findRunById(run.getId()).orElseThrow();
        TestCaseResult res = reloaded.getResultFor(tc.getId()).orElseThrow();
        assertThat(res.getStepStatuses()).containsExactly(
            TestStep.StepStatus.PASSED,
            TestStep.StepStatus.FAILED);
        assertThat(res.getStatus()).isEqualTo(TestCaseResult.TestResultStatus.FAILED);
    }

    /** Same path as the run-detail JS: POST …/setStepStatus with stepIndex + stepStatus (XHR). */
    @Test
    public void setStepStatus_post_persistsAndReturnsJson() throws Exception {
        List<TestStep> steps = List.of(
            new TestStep(0, "First", "e1"),
            new TestStep(1, "Second", "e2"));
        TestCase tc = service.createTestCase(
            "Case for setStepStatus",
            TestCase.TestCaseType.MANUAL,
            TestCase.Priority.MEDIUM,
            "",
            List.of(),
            steps,
            "u");

        TestRun run = runService.createAdHocRun(
            "Run setStep", "manual", 1, "", "n", List.of(tc.getId()), "u", "");

        CrumbIssuer issuer = j.jenkins.getCrumbIssuer();
        assertThat(issuer).isNotNull();

        JenkinsRule.WebClient wc = j.createWebClient();
        WebRequest req = new WebRequest(
            new URL(j.getURL() + "jtm/runs/" + run.getId() + "/setStepStatus"), HttpMethod.POST);
        req.setEncodingType(FormEncodingType.URL_ENCODED);
        List<NameValuePair> pairs = new ArrayList<>();
        pairs.add(new NameValuePair(issuer.getCrumbRequestField(), issuer.getCrumb()));
        pairs.add(new NameValuePair("testCaseId", tc.getId()));
        pairs.add(new NameValuePair("stepIndex", "1"));
        pairs.add(new NameValuePair("stepStatus", "PASSED"));
        req.setRequestParameters(pairs);
        req.setAdditionalHeader("X-Requested-With", "XMLHttpRequest");

        WebResponse wr = wc.loadWebResponse(req);
        assertThat(wr.getStatusCode()).isEqualTo(200);
        assertThat(wr.getContentAsString()).contains("\"ok\":true");

        TestRun reloaded = store.findRunById(run.getId()).orElseThrow();
        TestCaseResult res = reloaded.getResultFor(tc.getId()).orElseThrow();
        assertThat(res.getStepStatuses()).containsExactly(
            TestStep.StepStatus.NOT_RUN,
            TestStep.StepStatus.PASSED);
    }

    @Test
    public void runDetailPage_rendersEmbeddedStepFormControls() throws Exception {
        List<TestStep> steps = List.of(new TestStep(0, "Only", "e"));
        TestCase tc = service.createTestCase(
            "One step",
            TestCase.TestCaseType.MANUAL,
            TestCase.Priority.MEDIUM,
            "",
            List.of(),
            steps,
            "u");
        TestRun run = runService.createAdHocRun(
            "R", "manual", 1, "", "", List.of(tc.getId()), "u", "");

        String html = IOUtils.toString(
            new URL(j.getURL() + "jtm/runs/" + run.getId() + "/"), StandardCharsets.UTF_8);
        assertThat(html).contains("action=\"/jenkins/jtm/runs/" + run.getId() + "/addStepResult\"");
        assertThat(html).contains("name=\"stepStatus\"");
        assertThat(html).contains("data-step-save-url=\"/jenkins/jtm/runs/" + run.getId() + "/setStepStatus\"");
    }

    @Test
    public void runDetailPage_stepSelectsHaveDistinctDataStepIndex() throws Exception {
        List<TestStep> steps = List.of(
            new TestStep(0, "A", "e1"),
            new TestStep(1, "B", "e2"));
        TestCase tc = service.createTestCase(
            "Two steps",
            TestCase.TestCaseType.MANUAL,
            TestCase.Priority.MEDIUM,
            "",
            List.of(),
            steps,
            "u");
        TestRun run = runService.createAdHocRun(
            "R2", "manual", 1, "", "", List.of(tc.getId()), "u", "");

        String html = IOUtils.toString(
            new URL(j.getURL() + "jtm/runs/" + run.getId() + "/"), StandardCharsets.UTF_8);
        assertThat(html).contains("data-step-index=\"0\"");
        assertThat(html).contains("data-step-index=\"1\"");
    }
}
