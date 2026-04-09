package io.jenkins.plugins.jtm.ui.actions;

import hudson.model.Action;
import io.jenkins.plugins.jtm.core.domain.TestCase;
import io.jenkins.plugins.jtm.core.domain.TestStep;
import io.jenkins.plugins.jtm.core.service.TestCaseService;
import io.jenkins.plugins.jtm.persistence.JtmStore;
import io.jenkins.plugins.jtm.security.JtmPermissions;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.verb.GET;
import org.kohsuke.stapler.verb.POST;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Detail view for a single test case — must be {@code public} so Stapler/Jelly resolve views
 * ({@code TestCaseDetailAction/index.jelly}, {@code edit.jelly}) reliably on all Jenkins versions.
 */
public final class TestCaseDetailAction implements Action {

    private final TestCase testCase;

    public TestCaseDetailAction(TestCase tc) {
        this.testCase = tc;
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return testCase.getTitle();
    }

    @Override
    public String getUrlName() {
        return testCase.getId();
    }

    public TestCase getTestCase() {
        return testCase;
    }

    public boolean canEdit() {
        return JtmPermissions.canEditTestCase(testCase);
    }

    public boolean canExecute() {
        return JtmPermissions.hasPermission(JtmPermissions.TEST_EXECUTE);
    }

    public boolean canAdmin() {
        return JtmPermissions.hasPermission(JtmPermissions.TEST_ADMIN);
    }

    public boolean canDelete() {
        return JtmPermissions.canDeleteTestCase(testCase);
    }

    /**
     * GET …/testcases/{id}/edit — forward explicitly (nested Stapler dispatch is fragile).
     */
    @GET
    public void doEdit(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EDIT);
        JtmPermissions.checkEditTestCase(testCase);
        req.getView(this, "edit").forward(req, rsp);
    }

    /**
     * POST …/testcases/{id}/delete — same object as GET detail (CSRF / routing work reliably).
     */
    @POST
    public void doDelete(StaplerRequest req, StaplerResponse rsp) throws IOException {
        JtmPermissions.checkDeleteTestCase(testCase);
        String user = currentUser();
        TestCaseService.get().deleteTestCase(testCase.getId(), user);
        rsp.sendRedirect2(req.getContextPath() + "/jtm/testcases/");
    }

    public boolean hasSteps() {
        return testCase.getSteps() != null && !testCase.getSteps().isEmpty();
    }

    // Status intentionally not exposed on test case pages; it is run-scoped and only shown in test run detail UIs.

    /**
     * Row indices for edit.jelly: one row ({@code 0}) when there are no steps, else {@code 0..n-1}.
     */
    public List<Integer> getStepRowIndices() {
        List<TestStep> steps = testCase.getSteps();
        if (steps == null || steps.isEmpty()) {
            return Collections.singletonList(0);
        }
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            out.add(i);
        }
        return out;
    }

    public List<String> getProjectOptions() {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return JtmStore.get().findDistinctProjectKeys();
    }

    public String getSelectedProject() {
        return StringUtils.defaultString(JtmProjectFilter.current());
    }

    public String getProjectUrlQuery() {
        return JtmProjectFilter.urlQueryParam();
    }

    public String getProjectKeySelection() {
        return StringUtils.defaultString(testCase.getProjectKey());
    }

    public List<String> getProjectKeySelectOptions() {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return JtmStore.get().findDistinctProjectKeysIncluding(getProjectKeySelection());
    }

    public TestStep getStepAt(int index) {
        if (index < 0 || index >= testCase.getSteps().size()) {
            return null;
        }
        return testCase.getSteps().get(index);
    }

    public TestStep getStepAt(Integer index) {
        return index == null ? null : getStepAt(index.intValue());
    }

    public String getTagsCommaSeparated() {
        if (testCase.getTags() == null || testCase.getTags().isEmpty()) {
            return "";
        }
        return String.join(", ", testCase.getTags());
    }

    public boolean isSelectedType(String name) {
        return name != null && testCase.getType().name().equals(name);
    }

    public boolean isSelectedPriority(String name) {
        return name != null && testCase.getPriority().name().equals(name);
    }

    public boolean isSelectedRisk(String name) {
        return name != null && testCase.getRisk().name().equals(name);
    }

    public boolean isSelectedLifecycle(String name) {
        return name != null && testCase.getLifecycleStatus().name().equals(name);
    }

    public List<String> getTestCaseTypeOptions() {
        List<String> out = new ArrayList<>();
        for (TestCase.TestCaseType t : TestCase.TestCaseType.values()) {
            out.add(t.name());
        }
        return out;
    }

    public List<String> getPriorityOptions() {
        List<String> out = new ArrayList<>();
        for (TestCase.Priority p : TestCase.Priority.values()) {
            out.add(p.name());
        }
        return out;
    }

    public List<String> getRiskOptions() {
        List<String> out = new ArrayList<>();
        for (TestCase.Risk r : TestCase.Risk.values()) {
            out.add(r.name());
        }
        return out;
    }

    public List<String> getLifecycleOptions() {
        List<String> out = new ArrayList<>();
        for (TestCase.LifecycleStatus s : TestCase.LifecycleStatus.values()) {
            out.add(s.name());
        }
        return out;
    }

    @POST
    public void doUpdateStatus(StaplerRequest req, StaplerResponse rsp) throws IOException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EXECUTE);
        String statusStr = req.getParameter("status");
        String user = hudson.model.User.current() != null
            ? hudson.model.User.current().getId() : "anonymous";
        try {
            TestCase.TestCaseStatus s = TestCase.TestCaseStatus.valueOf(statusStr.toUpperCase());
            TestCaseService.get().updateStatus(testCase.getId(), s, user, 0, 3);
        } catch (IllegalArgumentException ignored) {
            // ignore invalid status
        }
        rsp.sendRedirect2(req.getContextPath() + "/jtm/testcases/" + testCase.getId() + "/");
    }

    private static String currentUser() {
        hudson.model.User u = hudson.model.User.current();
        return u != null ? u.getId() : "anonymous";
    }
}
