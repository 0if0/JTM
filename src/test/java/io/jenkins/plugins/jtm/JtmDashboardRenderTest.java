package io.jenkins.plugins.jtm;

import org.htmlunit.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jvnet.hudson.test.JenkinsRule;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression: GET /jtm/ must render the dashboard (E2E waits for {@code #jtm-dash-project}). */
@ExtendWith(JtmJenkinsExtension.class)
public class JtmDashboardRenderTest {

    @Test
    public void jtmRoot_dashboardContainsProjectSelect(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        // HtmlUnit does not parse ES2020+ in adjunct dashboard.js (e.g. Math.max(0, ...ms));
        // we only assert server-rendered Jelly output.
        wc.getOptions().setJavaScriptEnabled(false);
        Page page = wc.getPage(j.getURL() + "jtm/");
        assertThat(page.getWebResponse().getStatusCode()).isEqualTo(200);
        assertThat(page.getWebResponse().getContentAsString()).contains("id=\"jtm-dash-project\"");
    }
}
