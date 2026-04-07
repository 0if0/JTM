package io.jenkins.plugins.jtm;

import hudson.security.csrf.CrumbIssuer;
import io.jenkins.plugins.jtm.core.domain.TestCase;
import io.jenkins.plugins.jtm.core.service.TestCaseService;
import org.apache.commons.io.IOUtils;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.FormEncodingType;
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
 * Delete POST uses {@code jtmCaseId} — Jenkins strips a plain {@code id} form field from POST bodies.
 */
public class JtmDeleteUiTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private TestCaseService service;

    @Before
    public void setUp() {
        service = TestCaseService.get();
    }

    @Test
    public void delete_viaUrlEncodedPost_removesCase() throws Exception {
        TestCase tc = service.createTestCase(
            "Raw delete", TestCase.TestCaseType.MANUAL, TestCase.Priority.LOW, "u");
        CrumbIssuer issuer = j.jenkins.getCrumbIssuer();
        assertThat(issuer).isNotNull();

        JenkinsRule.WebClient wc = j.createWebClient();
        WebRequest req = new WebRequest(new URL(j.getURL() + "jtm/deletetestcase"), HttpMethod.POST);
        req.setEncodingType(FormEncodingType.URL_ENCODED);
        List<NameValuePair> pairs = new ArrayList<>();
        pairs.add(new NameValuePair("jtmCaseId", tc.getId()));
        pairs.add(new NameValuePair(issuer.getCrumbRequestField(), issuer.getCrumb()));
        req.setRequestParameters(pairs);

        HtmlPage result = wc.getPage(req);
        assertThat(result.getUrl().toString()).contains("/jtm/testcases/");
        assertThat(service.findById(tc.getId())).isEmpty();
    }

    @Test
    public void delete_viaTestCaseDetailPost_removesCase() throws Exception {
        TestCase tc = service.createTestCase(
            "Detail delete", TestCase.TestCaseType.MANUAL, TestCase.Priority.LOW, "u");
        CrumbIssuer issuer = j.jenkins.getCrumbIssuer();
        assertThat(issuer).isNotNull();

        JenkinsRule.WebClient wc = j.createWebClient();
        String path = "jtm/testcases/" + tc.getId() + "/delete";
        WebRequest req = new WebRequest(new URL(j.getURL() + path), HttpMethod.POST);
        req.setEncodingType(FormEncodingType.URL_ENCODED);
        List<NameValuePair> pairs = new ArrayList<>();
        pairs.add(new NameValuePair(issuer.getCrumbRequestField(), issuer.getCrumb()));
        req.setRequestParameters(pairs);

        HtmlPage result = wc.getPage(req);
        assertThat(result.getUrl().toString()).contains("/jtm/testcases/");
        assertThat(service.findById(tc.getId())).isEmpty();
    }

    @Test
    public void detailPage_html_includesDeleteFormOnDetailUrl() throws Exception {
        TestCase tc = service.createTestCase(
            "Detail HTML", TestCase.TestCaseType.MANUAL, TestCase.Priority.LOW, "u");
        String html = IOUtils.toString(
            new URL(j.getURL() + "jtm/testcases/" + tc.getId() + "/"), StandardCharsets.UTF_8);
        assertThat(html).contains("/jtm/testcases/" + tc.getId() + "/delete");
        CrumbIssuer issuer = j.jenkins.getCrumbIssuer();
        assertThat(issuer).isNotNull();
        assertThat(html).contains(issuer.getCrumbRequestField());
    }
}
