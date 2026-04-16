package io.jenkins.plugins.jtm.ui.actions;

import hudson.model.Action;
import io.jenkins.plugins.jtm.core.domain.TestCase;
import io.jenkins.plugins.jtm.core.domain.TestRun;
import io.jenkins.plugins.jtm.core.service.TestCaseService;
import io.jenkins.plugins.jtm.core.service.TestRunService;
import io.jenkins.plugins.jtm.export.FlatExportRow;
import io.jenkins.plugins.jtm.export.JtmExportBrandingStore;
import io.jenkins.plugins.jtm.export.TestRunExportService;
import io.jenkins.plugins.jtm.persistence.JtmStore;
import io.jenkins.plugins.jtm.security.JtmPermissions;
import jakarta.servlet.ServletException;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.GET;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Handles /jtm/runs/
 */
public class TestRunsAction implements Action {
    @Override public String getIconFileName()  { return null; }
    @Override public String getDisplayName()   { return "Test Runs"; }
    @Override public String getUrlName()       { return "runs"; }

    private static final Set<String> RUNS_RESERVED_SEGMENTS = Set.of("newrun", "exportBatch", "deleteBatch");

    /** Matches the recent-runs list cap; batch export cannot exceed this many run IDs. */
    private static final int MAX_BATCH_RUNS = 20;

    public List<TestRun> getRecentRuns() {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return JtmStore.get().findRecentRuns(20, JtmProjectFilter.current());
    }

    /** Jelly/JEXL-safe count (avoid list.size() in Jelly). */
    public int getRecentRunsCount() {
        return getRecentRuns().size();
    }

    public boolean isRecentRunsEmpty() {
        return getRecentRuns().isEmpty();
    }

    /**
     * For newrun.jelly — test cases to link to a new run.
     * Always lists all cases (findAll(null)); do not apply the UI project filter here.
     */
    public List<TestCase> getTestCasesForPicker() {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EDIT);
        return TestCaseService.get().findAll(null);
    }

    public int getTestCasesForPickerCount() {
        return getTestCasesForPicker().size();
    }

    public List<String> getProjectOptions() {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return JtmStore.get().findDistinctProjectScopes();
    }

    public String getSelectedProject() {
        return StringUtils.defaultString(JtmProjectFilter.current());
    }

    public String getProjectUrlQuery() {
        return JtmProjectFilter.urlQueryParam();
    }

    /** Prefill for newrun.jelly when ?project= is set. */
    public String getProjectScopePrefill() {
        return StringUtils.defaultString(JtmProjectFilter.current());
    }

    public String getProjectScopeSelection() {
        return getProjectScopePrefill();
    }

    public List<String> getProjectScopeSelectOptions() {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EDIT);
        return JtmStore.get().findDistinctProjectScopesIncluding(getProjectScopeSelection());
    }

    /**
     * POST /jtm/runs/exportBatch — flat multi-run report (HTML or PDF). Body: {@code runId} (repeatable), {@code format}.
     */
    @POST
    public void doExportBatch(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        String format = StringUtils.defaultString(req.getParameter("format")).toLowerCase(Locale.ROOT);
        if (!"html".equals(format) && !"pdf".equals(format)) {
            format = "html";
        }
        LinkedHashSet<String> idOrder = new LinkedHashSet<>();
        String[] raw = req.getParameterValues("runId");
        if (raw != null) {
            for (String s : raw) {
                if (s != null && !s.isBlank()) {
                    idOrder.add(s.trim());
                }
            }
        }
        List<String> ids = new ArrayList<>(idOrder);
        if (ids.size() > MAX_BATCH_RUNS) {
            ids = new ArrayList<>(ids.subList(0, MAX_BATCH_RUNS));
        }
        JtmStore store = JtmStore.get();
        List<TestRun> runs = new ArrayList<>();
        for (String id : ids) {
            store.findRunById(id).ifPresent(runs::add);
        }
        if (runs.isEmpty()) {
            rsp.sendError(400, "No valid test runs selected");
            return;
        }
        List<FlatExportRow> flat = TestRunExportService.buildFlatRows(runs);
        if (flat.size() > TestRunExportService.MAX_FLAT_EXPORT_ROWS) {
            rsp.sendError(
                400,
                "Too many result rows (max " + TestRunExportService.MAX_FLAT_EXPORT_ROWS + ")"
            );
            return;
        }
        JtmExportBrandingStore brand = JtmExportBrandingStore.get();
        Optional<byte[]> logo = brand.readLogoBytes();
        Optional<String> mime = brand.getLogoMimeType();
        String fname = "jtm-multi-run-"
            + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now());
        if ("pdf".equals(format)) {
            try {
                byte[] pdf = TestRunExportService.buildPdfFlat(runs, flat, logo, mime);
                rsp.setContentType("application/pdf");
                rsp.addHeader("Content-Disposition", "attachment; filename=\"" + fname + ".pdf\"");
                rsp.getOutputStream().write(pdf);
            } catch (RuntimeException e) {
                rsp.sendError(500, "PDF export failed");
            }
        } else {
            byte[] html = TestRunExportService.buildHtmlFlat(runs, flat, logo, mime);
            rsp.setContentType("text/html;charset=UTF-8");
            rsp.addHeader("Content-Disposition", "attachment; filename=\"" + fname + ".html\"");
            rsp.getOutputStream().write(html);
        }
    }

    /** POST /jtm/runs/deleteBatch — delete selected runs from list page. */
    @POST
    public void doDeleteBatch(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EXECUTE);
        LinkedHashSet<String> idOrder = new LinkedHashSet<>();
        String[] raw = req.getParameterValues("runId");
        if (raw != null) {
            for (String s : raw) {
                if (s != null && !s.isBlank()) {
                    idOrder.add(s.trim());
                }
            }
        }
        int deleted = 0;
        int failed = 0;
        String user = currentRunUser();
        for (String id : idOrder) {
            try {
                TestRunService.get().deleteRun(id, user);
                deleted++;
            } catch (Exception e) {
                failed++;
            }
        }
        String projectQuery = JtmProjectFilter.urlQueryParam();
        String sep = projectQuery.isEmpty() ? "?" : "&";
        rsp.sendRedirect2(req.getContextPath() + "/jtm/runs/" + projectQuery
            + sep + "bulkDeleted=" + deleted + "&bulkFailed=" + failed);
    }

    @GET
    public HttpResponse doNewrun(StaplerRequest2 req, StaplerResponse2 rsp) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EDIT);
        return HttpResponses.forwardToView(this, "newrunPage.jelly");
    }

    /** POST /jtm/saverun (via JtmRootAction#doSaverun) — ad-hoc run from UI. */
    @POST
    public void doSaverun(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EXECUTE);
        String name = trimRunParam(req.getParameter("name"));
        String job = trimRunParam(req.getParameter("jobName"));
        if (job == null) {
            job = "manual";
        }
        int buildNum = parseBuildNumber(req.getParameter("buildNumber"), 1);
        String branchRaw = trimRunParam(req.getParameter("branch"));
        String notes = trimRunParam(req.getParameter("notes"));
        String projectScope = StringUtils.trimToEmpty(req.getParameter("projectScope"));
        if (projectScope.isEmpty()) {
            String fromFilter = StringUtils.trimToNull(JtmProjectFilter.current());
            if (fromFilter != null) {
                projectScope = fromFilter;
            }
        }
        String user = currentRunUser();

        List<String> linked = new ArrayList<>();
        String[] rawLinked = req.getParameterValues("linkedTestCaseId");
        if (rawLinked != null) {
            for (String s : rawLinked) {
                if (s != null && !s.isBlank()) {
                    linked.add(s.trim());
                }
            }
        }

        TestRun run = TestRunService.get().createAdHocRun(
            name, job, buildNum, branchRaw != null ? branchRaw : "", notes, linked, user, projectScope);
        rsp.sendRedirect2(req.getContextPath() + "/jtm/runs/" + run.getId() + "/");
    }

    private static String trimRunParam(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        return s.trim();
    }

    private static int parseBuildNumber(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String currentRunUser() {
        hudson.model.User u = hudson.model.User.current();
        return u != null ? u.getId() : "anonymous";
    }

    public Object getDynamic(String id, StaplerRequest2 req, StaplerResponse2 rsp) {
        if (id == null || id.isBlank() || RUNS_RESERVED_SEGMENTS.contains(id)) {
            return null;
        }
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return JtmStore.get().findRunById(id).map(TestRunDetailAction::new).orElse(null);
    }
}
