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
import java.util.Set;

/**
 * Handles /jtm/testcases/
 *
 * URL layout (Stapler conventions):
 *   GET  /jtm/testcases/           → index.jelly        (list)
 *   GET  /jtm/testcases/newcase    → doNewcase()        (forwards to newcase.jelly)
 *   POST /jtm/savecase             → JtmRootAction.doSavecase → doSavecase() (form post)
 *   GET  /jtm/testcases/{id}/      → getDynamic()       → TestCaseDetailAction + index.jelly
 *   GET  /jtm/runs/newrun          → TestRunsAction.doNewrun
 *   POST /jtm/saverun              → JtmRootAction.doSaverun → TestRunsAction.doSaverun
 */
public final class TestCasesAction implements Action {

    @Override public String getIconFileName()  { return null; }
    @Override public String getDisplayName()   { return "Test Cases"; }
    @Override public String getUrlName()       { return "testcases"; }

    // ── Data for index.jelly ──────────────────────────────────────────────────
    private transient Set<String> linkedTestCaseIds;
    private transient String linkedScopeProjectKey;

    public List<TestCase> getTestCases() {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        String projectKey = JtmProjectFilter.current();
        List<TestCase> cases = TestCaseService.get().findAll(projectKey);
        if (linkedTestCaseIds == null || !java.util.Objects.equals(linkedScopeProjectKey, projectKey)) {
            linkedTestCaseIds = JtmStore.get().findLinkedTestCaseIds(projectKey);
            linkedScopeProjectKey = projectKey;
        }
        return cases;
    }

    public int getTestCasesCount() {
        return getTestCases().size();
    }

    /** True if the test case is linked to at least one test run (within the current project filter). */
    public boolean isTestCaseLinked(String testCaseId) {
        if (testCaseId == null) return false;
        if (linkedTestCaseIds == null) {
            // In case Jelly triggers this before getTestCases() (shouldn't happen, but safe).
            String projectKey = JtmProjectFilter.current();
            linkedTestCaseIds = JtmStore.get().findLinkedTestCaseIds(projectKey);
            linkedScopeProjectKey = projectKey;
        }
        return linkedTestCaseIds.contains(testCaseId);
    }

    public boolean isTestCasesEmpty() {
        return getTestCases().isEmpty();
    }

    public List<String> getProjectOptions() {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return JtmStore.get().findDistinctProjectKeys();
    }

    /** Selected filter from {@code ?project=} (may be empty). */
    public String getSelectedProject() {
        return StringUtils.defaultString(JtmProjectFilter.current());
    }

    public String getProjectUrlQuery() {
        return JtmProjectFilter.urlQueryParam();
    }

    /** Prefill for newcase.jelly when {@code ?project=} is set. */
    public String getProjectKeyPrefill() {
        return StringUtils.defaultString(JtmProjectFilter.current());
    }

    /** Selected value for project dropdown on newcase.jelly. */
    public String getProjectKeySelection() {
        return getProjectKeyPrefill();
    }

    /** Options for project dropdown (registry + cases/runs + current selection). */
    public List<String> getProjectKeySelectOptions() {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return JtmStore.get().findDistinctProjectKeysIncluding(getProjectKeySelection());
    }

    public boolean canEdit()  { return JtmPermissions.hasPermission(JtmPermissions.TEST_EDIT); }
    public boolean canAdmin() { return JtmPermissions.hasPermission(JtmPermissions.TEST_ADMIN); }

    /** Per-row: global Edit or creator of that test case. */
    public boolean canEditCase(TestCase tc) {
        return JtmPermissions.canEditTestCase(tc);
    }

    // ── GET /jtm/testcases/newcase → newcase.jelly ────────────────────────────
    // Use doNewcase (explicit view forward). A getNewcase()+return-this pattern does not
    // reliably select newcase.jelly under nested Stapler dispatch (404).

    @GET
    public void doNewcase(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EDIT);
        req.getView(this, "newcase").forward(req, rsp);
    }

    // ── POST /jtm/savecase (via JtmRootAction) → save and redirect ─────────────

    @POST
    public void doSavecase(StaplerRequest req, StaplerResponse rsp) throws IOException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EDIT);

        String title       = trimToNull(req.getParameter("title"));
        String typeStr     = req.getParameter("type");
        String priorityStr = req.getParameter("priority");
        String description = trimToNull(req.getParameter("description"));
        String tagsRaw     = req.getParameter("tags");
        String projectKey  = StringUtils.trimToEmpty(req.getParameter("projectKey"));
        String user        = currentUser();
        List<TestStep> rawSteps = JtmStepFormParser.parseSteps(req);

        if (title == null) {
            rsp.sendRedirect2(req.getContextPath() + "/jtm/testcases/newcase?error=title_required");
            return;
        }

        TestCase.TestCaseType type = parseEnum(typeStr,
            TestCase.TestCaseType.class, TestCase.TestCaseType.MANUAL);
        TestCase.Priority priority = parseEnum(priorityStr,
            TestCase.Priority.class, TestCase.Priority.MEDIUM);

        String descForStore = description != null ? description : "";
        TestCase created = TestCaseService.get().createTestCase(
            title, type, priority, descForStore, parseTags(tagsRaw), rawSteps, user, projectKey);

        rsp.sendRedirect2(req.getContextPath() + "/jtm/testcases/" + created.getId() + "/");
    }

    // ── Dynamic routing /jtm/testcases/{id}/ — any persisted id (reserve Stapler paths) ─

    private static final Set<String> TESTCASES_RESERVED_SEGMENTS = Set.of("newcase");

    public Object getDynamic(String id, StaplerRequest req, StaplerResponse rsp) {
        if (id == null || id.isBlank() || TESTCASES_RESERVED_SEGMENTS.contains(id)) {
            return null;
        }
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return TestCaseService.get().findById(id)
            .map(TestCaseDetailAction::new)
            .orElse(null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String trimToNull(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        return s.trim();
    }

    private List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String t : raw.split("[,;\\s]+")) {
            if (!t.trim().isEmpty()) result.add(t.trim());
        }
        return result;
    }

    private <T extends Enum<T>> T parseEnum(String val, Class<T> type, T def) {
        if (val == null || val.isBlank()) return def;
        try { return Enum.valueOf(type, val.toUpperCase()); }
        catch (IllegalArgumentException e) { return def; }
    }

    private String currentUser() {
        hudson.model.User u = hudson.model.User.current();
        return u != null ? u.getId() : "anonymous";
    }
}
