package io.jenkins.plugins.jtm.ui.actions;

import hudson.model.Action;
import io.jenkins.plugins.jtm.core.domain.TestCase;
import io.jenkins.plugins.jtm.core.domain.TestCaseResult;
import io.jenkins.plugins.jtm.core.domain.TestStep;
import io.jenkins.plugins.jtm.core.domain.TestRun;
import io.jenkins.plugins.jtm.core.service.TestCaseService;
import io.jenkins.plugins.jtm.core.service.TestRunService;
import io.jenkins.plugins.jtm.export.ExportRow;
import io.jenkins.plugins.jtm.export.JtmExportBrandingStore;
import io.jenkins.plugins.jtm.export.TestRunExportService;
import io.jenkins.plugins.jtm.security.JtmPermissions;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Detail view/action for a single test run.
 * Must be public for reliable Stapler/Jelly property resolution.
 */
public final class TestRunDetailAction implements Action {
    /** Only the id is kept; never hold a stale {@link TestRun} instance (Jelly may read fields directly). */
    private final String runId;

    public TestRunDetailAction(TestRun r) {
        this.runId = Objects.requireNonNull(r.getId());
    }

    @Override public String getIconFileName()  { return null; }
    @Override public String getDisplayName()   { return currentRun().getName(); }
    @Override public String getUrlName()       { return runId; }

    public TestRun getTestRun() { return currentRun(); }

    /** Test case picker for “link result” form (same data as list page). */
    public List<TestCase> getTestCasesForPicker() {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return TestCaseService.get().findAll();
    }

    public List<RunLinkedCaseRow> getLinkedCaseRows() {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        TestRun run = currentRun();
        List<RunLinkedCaseRow> rows = new ArrayList<>();
        for (String id : run.getLinkedTestCaseIds()) {
            Optional<TestCase> tc = TestCaseService.get().findById(id);
            String title = tc.map(TestCase::getTitle).orElse("(deleted test case)");
            List<TestStep> steps = tc.map(TestCase::getSteps).orElse(List.of());
            TestCaseResult res = run.getResultFor(id).orElse(null);
            rows.add(new RunLinkedCaseRow(id, title, res, steps));
        }
        return rows;
    }

    /** Jelly-safe count for linked matrix visibility. */
    public int getLinkedCaseRowsCount() {
        return getLinkedCaseRows().size();
    }

    public String getProjectUrlQuery() {
        return JtmProjectFilter.urlQueryParam();
    }

    public List<String> getStepStatusOptions() {
        List<String> out = new ArrayList<>();
        for (TestStep.StepStatus s : TestStep.StepStatus.values()) {
            out.add(s.name());
        }
        return out;
    }

    /** Options for the per-case overall result dropdown (same order as before). */
    public List<String> getOverallResultOptions() {
        List<String> out = new ArrayList<>();
        for (TestCaseResult.TestResultStatus s : TestCaseResult.TestResultStatus.values()) {
            out.add(s.name());
        }
        return out;
    }

    /** Existing Jenkins users for assignee dropdowns. */
    public List<UserChoice> getJenkinsUserChoices() {
        List<UserChoice> out = new ArrayList<>();
        for (hudson.model.User u : hudson.model.User.getAll()) {
            if (u == null || u.getId() == null || u.getId().isBlank()) {
                continue;
            }
            // Internal Jenkins pseudo-user — not useful as a human assignee.
            if ("SYSTEM".equals(u.getId())) {
                continue;
            }
            out.add(new UserChoice(u.getId(), u.getDisplayName()));
        }
        out.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    /** Test cases not yet linked — for “add to run” checkboxes. */
    public List<TestCase> getTestCasesNotYetLinked() {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        Set<String> linked = new HashSet<>(currentRun().getLinkedTestCaseIds());
        List<TestCase> out = new ArrayList<>();
        for (TestCase tc : TestCaseService.get().findAll()) {
            if (!linked.contains(tc.getId())) {
                out.add(tc);
            }
        }
        return out;
    }

    public int getTestCasesNotYetLinkedCount() {
        return getTestCasesNotYetLinked().size();
    }

    /** Used by run-detail JS to keep step assignment in sync with the current user. */
    public String getCurrentUserId() {
        return runDetailCurrentUser();
    }

    /**
     * GET …/runs/{id}/export?format=html|pdf — download a self-contained HTML or PDF report
     * (optional company logo from {@link JtmExportBrandingStore}, configured once under {@code /jtm/exportBranding}).
     */
    public void doExport(StaplerRequest req, StaplerResponse rsp) throws IOException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        String format = StringUtils.defaultString(req.getParameter("format")).toLowerCase(Locale.ROOT);
        if (!"html".equals(format) && !"pdf".equals(format)) {
            format = "html";
        }
        TestRun run = currentRun();
        List<ExportRow> rows = TestRunExportService.buildRows(run);
        JtmExportBrandingStore brand = JtmExportBrandingStore.get();
        java.util.Optional<byte[]> logo = brand.readLogoBytes();
        java.util.Optional<String> mime = brand.getLogoMimeType();
        String safe = run.getId().replaceAll("[^a-zA-Z0-9._-]", "_");
        if ("pdf".equals(format)) {
            try {
                byte[] pdf = TestRunExportService.buildPdf(run, rows, logo, mime);
                rsp.setContentType("application/pdf");
                rsp.addHeader("Content-Disposition", "attachment; filename=\"" + safe + "-report.pdf\"");
                rsp.getOutputStream().write(pdf);
            } catch (Exception e) {
                rsp.sendError(500, "PDF export failed");
            }
        } else {
            byte[] html = TestRunExportService.buildHtml(run, rows, logo, mime);
            rsp.setContentType("text/html;charset=UTF-8");
            rsp.addHeader("Content-Disposition", "attachment; filename=\"" + safe + "-report.html\"");
            rsp.getOutputStream().write(html);
        }
    }

    /** POST …/runs/{id}/removeLinked — remove a test case from this run’s scope. */
    @POST
    public void doRemoveLinked(StaplerRequest req, StaplerResponse rsp) throws IOException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EXECUTE);
        String caseId = trimToNull(req.getParameter("testCaseId"));
        if (caseId != null) {
            TestRunService.get().removeLinkedTestCase(runId, caseId, runDetailCurrentUser());
        }
        rsp.sendRedirect2(req.getContextPath() + "/jtm/runs/" + runId + "/");
    }

    /** POST …/runs/{id}/deleteRun — delete this test run. */
    @POST
    public void doDeleteRun(StaplerRequest req, StaplerResponse rsp) throws IOException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EXECUTE);
        String id = runId;
        TestRunService.get().deleteRun(id, runDetailCurrentUser());
        rsp.sendRedirect2(req.getContextPath() + "/jtm/runs/" + JtmProjectFilter.urlQueryParam());
    }

    /** POST …/runs/{id}/addLinked — add more test cases to this run’s scope. */
    @POST
    public void doAddLinked(StaplerRequest req, StaplerResponse rsp) throws IOException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EXECUTE);
        List<String> ids = new ArrayList<>();
        String[] raw = req.getParameterValues("linkedTestCaseId");
        if (raw != null) {
            for (String s : raw) {
                if (s != null && !s.isBlank()) {
                    ids.add(s.trim());
                }
            }
        }
        TestRunService.get().appendLinkedTestCases(runId, ids, runDetailCurrentUser());
        rsp.sendRedirect2(req.getContextPath() + "/jtm/runs/" + runId + "/");
    }

    /** POST …/runs/{id}/addResult — attach a test case result to this run. */
    @POST
    public void doAddResult(StaplerRequest req, StaplerResponse rsp) throws IOException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EXECUTE);
        TestRun latestRun = TestRunService.get().getByIdOrThrow(runId);
        String caseId = trimToNull(req.getParameter("testCaseId"));
        String statusStr = trimToNull(req.getParameter("resultStatus"));
        if (caseId == null || statusStr == null) {
            rsp.sendRedirect2(req.getContextPath() + "/jtm/runs/" + runId + "/");
            return;
        }
        if (!latestRun.getLinkedTestCaseIds().isEmpty()
            && !latestRun.getLinkedTestCaseIds().contains(caseId)) {
            rsp.sendRedirect2(req.getContextPath() + "/jtm/runs/" + runId + "/");
            return;
        }
        try {
            TestCaseResult.TestResultStatus st =
                TestCaseResult.TestResultStatus.valueOf(statusStr.trim().toUpperCase());
            TestCaseResult result = new TestCaseResult(caseId, st, 0L);
            result.setExecutedBy(runDetailCurrentUser());
            Optional<TestCaseResult> prev = latestRun.getResultFor(caseId);
            if (prev.isPresent()) {
                result.setStepStatuses(new ArrayList<>(prev.get().getStepStatuses()));
                result.setStepComments(new ArrayList<>(prev.get().getStepComments()));
            }
            String rawAssign = req.getParameter("assignedTo");
            if (rawAssign != null) {
                result.setAssignedTo(trimToNull(rawAssign));
            } else if (prev.isPresent()) {
                result.setAssignedTo(prev.get().getAssignedTo());
            }
            if (req.getParameterMap().containsKey("caseComment")) {
                result.setComment(StringUtils.defaultString(req.getParameter("caseComment")));
            } else if (prev.isPresent()) {
                result.setComment(prev.get().getComment());
            }
            TestRunService.get().addResult(runId, result, true);
        } catch (IllegalArgumentException ignored) {
            // invalid enum
        }
        rsp.sendRedirect2(req.getContextPath() + "/jtm/runs/" + runId + "/");
    }

    /** POST …/runs/{id}/addStepResult — set all step statuses for one linked test case. */
    @POST
    public void doAddStepResult(StaplerRequest req, StaplerResponse rsp) throws IOException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EXECUTE);
        TestRun latestRun = TestRunService.get().getByIdOrThrow(runId);
        String caseId = trimToNull(req.getParameter("testCaseId"));
        if (caseId == null) {
            rsp.sendRedirect2(req.getContextPath() + "/jtm/runs/" + runId + "/");
            return;
        }
        if (!latestRun.getLinkedTestCaseIds().isEmpty() && !latestRun.getLinkedTestCaseIds().contains(caseId)) {
            rsp.sendRedirect2(req.getContextPath() + "/jtm/runs/" + runId + "/");
            return;
        }

        String[] rawSteps = req.getParameterValues("stepStatus");
        Map<Integer, String> indexedRaw = new HashMap<>();
        for (Map.Entry<String, String[]> e : req.getParameterMap().entrySet()) {
            String k = e.getKey();
            if (k == null || !k.startsWith("stepStatus_")) {
                continue;
            }
            String suffix = k.substring("stepStatus_".length());
            int idx;
            try {
                idx = Integer.parseInt(suffix);
            } catch (NumberFormatException ignore) {
                continue;
            }
            String[] vals = e.getValue();
            String v = (vals != null && vals.length > 0) ? vals[vals.length - 1] : null;
            indexedRaw.put(idx, v);
        }
        List<TestStep.StepStatus> stepStatuses = new ArrayList<>();
        if (!indexedRaw.isEmpty()) {
            indexedRaw.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .forEach(entry -> {
                    String raw = entry.getValue();
                    if (raw == null || raw.isBlank()) {
                        stepStatuses.add(TestStep.StepStatus.NOT_RUN);
                        return;
                    }
                    try {
                        stepStatuses.add(TestStep.StepStatus.valueOf(raw.trim().toUpperCase()));
                    } catch (IllegalArgumentException ex) {
                        stepStatuses.add(TestStep.StepStatus.NOT_RUN);
                    }
                });
        } else if (rawSteps != null) {
            for (String raw : rawSteps) {
                if (raw == null || raw.isBlank()) {
                    stepStatuses.add(TestStep.StepStatus.NOT_RUN);
                    continue;
                }
                try {
                    stepStatuses.add(TestStep.StepStatus.valueOf(raw.trim().toUpperCase()));
                } catch (IllegalArgumentException e) {
                    stepStatuses.add(TestStep.StepStatus.NOT_RUN);
                }
            }
        }
        if (stepStatuses.isEmpty()) {
            int tcSteps = TestCaseService.get().findById(caseId).map(tc -> tc.getSteps().size()).orElse(0);
            for (int i = 0; i < tcSteps; i++) {
                stepStatuses.add(TestStep.StepStatus.NOT_RUN);
            }
        }

        TestCaseResult.TestResultStatus overall = aggregateStepStatus(stepStatuses);
        TestCaseResult result = new TestCaseResult(caseId, overall, 0L);
        result.setExecutedBy(runDetailCurrentUser());
        result.setStepStatuses(stepStatuses);
        Optional<TestCaseResult> prev = latestRun.getResultFor(caseId);
        result.setStepComments(mergeStepCommentsFromRequest(req, stepStatuses.size(), prev));
        prev.ifPresent(p -> result.setComment(p.getComment()));
        // When steps are set in a run, the step assignment should jump to the current user.
        result.setAssignedTo(runDetailCurrentUser());
        TestRunService.get().addResult(runId, result, true);
        boolean ajax = "1".equals(req.getParameter("ajax"))
            || "XMLHttpRequest".equalsIgnoreCase(req.getHeader("X-Requested-With"));
        if (ajax) {
            rsp.setStatus(200);
            return;
        }
        rsp.sendRedirect2(req.getContextPath() + "/jtm/runs/" + runId + "/");
    }

    /** POST …/runs/{id}/setStepStatus — autosave one step status in-place (no redirect). */
    @POST
    public void doSetStepStatus(StaplerRequest req, StaplerResponse rsp) throws IOException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EXECUTE);
        TestRun latestRun = TestRunService.get().getByIdOrThrow(runId);
        String caseId = trimToNull(req.getParameter("testCaseId"));
        String stepIndexRaw = trimToNull(req.getParameter("stepIndex"));
        String stepStatusRaw = trimToNull(req.getParameter("stepStatus"));
        if (caseId == null || stepIndexRaw == null || stepStatusRaw == null) {
            rsp.setStatus(400);
            return;
        }
        if (!latestRun.getLinkedTestCaseIds().isEmpty() && !latestRun.getLinkedTestCaseIds().contains(caseId)) {
            rsp.setStatus(404);
            return;
        }
        int idx;
        try {
            idx = Integer.parseInt(stepIndexRaw);
        } catch (NumberFormatException e) {
            rsp.setStatus(400);
            return;
        }
        if (idx < 0) {
            rsp.setStatus(400);
            return;
        }
        TestStep.StepStatus st;
        try {
            st = TestStep.StepStatus.valueOf(stepStatusRaw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            st = TestStep.StepStatus.NOT_RUN;
        }

        Optional<TestCaseResult> prevOpt = latestRun.getResultFor(caseId);
        List<TestStep.StepStatus> statuses = prevOpt
            .map(r -> new ArrayList<>(r.getStepStatuses()))
            .orElseGet(ArrayList::new);
        int minLen = TestCaseService.get().findById(caseId).map(tc -> tc.getSteps().size()).orElse(0);
        int targetSize = Math.max(Math.max(statuses.size(), minLen), idx + 1);
        while (statuses.size() < targetSize) {
            statuses.add(TestStep.StepStatus.NOT_RUN);
        }
        statuses.set(idx, st);

        List<String> comments = prevOpt
            .map(r -> new ArrayList<>(r.getStepComments()))
            .orElseGet(ArrayList::new);
        while (comments.size() < targetSize) {
            comments.add("");
        }
        if (req.getParameterMap().containsKey("stepComment")) {
            comments.set(idx, StringUtils.defaultString(req.getParameter("stepComment")));
        }

        TestCaseResult.TestResultStatus overall = aggregateStepStatus(statuses);
        TestCaseResult result = new TestCaseResult(caseId, overall, 0L);
        result.setExecutedBy(runDetailCurrentUser());
        result.setStepStatuses(statuses);
        result.setStepComments(comments);
        prevOpt.ifPresent(p -> result.setComment(p.getComment()));
        // When steps are set in a run, the step assignment should jump to the current user.
        result.setAssignedTo(runDetailCurrentUser());
        // Same path as other result updates (persist + optional test-case sync).
        TestRunService.get().addResult(runId, result, true);
        List<TestStep.StepStatus> saved = TestRunService.get().getByIdOrThrow(runId)
            .getResultFor(caseId)
            .map(TestCaseResult::getStepStatuses)
            .orElseGet(ArrayList::new);
        boolean persisted = idx < saved.size() && saved.get(idx) == st;
        String at = (idx >= 0 && idx < saved.size() && saved.get(idx) != null)
            ? saved.get(idx).name() : "NONE";
        String json = String.format(Locale.ROOT,
            "{\"ok\":%s,\"savedCount\":%d,\"idx\":%d,\"at\":\"%s\"}",
            persisted ? "true" : "false", saved.size(), idx, at);
        rsp.setStatus(200);
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
    }

    private static TestCaseResult.TestResultStatus aggregateStepStatus(List<TestStep.StepStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return TestCaseResult.TestResultStatus.PENDING;
        }
        boolean hasFailed = false;
        boolean hasBlocked = false;
        boolean hasNotRun = false;
        boolean hasFalsePositive = false;
        for (TestStep.StepStatus s : statuses) {
            TestStep.StepStatus st = s != null ? s : TestStep.StepStatus.NOT_RUN;
            if (st == TestStep.StepStatus.FAILED) {
                hasFailed = true;
            }
            if (st == TestStep.StepStatus.BLOCKED) {
                hasBlocked = true;
            }
            if (st == TestStep.StepStatus.NOT_RUN) {
                hasNotRun = true;
            }
            if (st == TestStep.StepStatus.FALSE_POSITIVE) {
                hasFalsePositive = true;
            }
        }
        if (hasFailed) {
            return TestCaseResult.TestResultStatus.FAILED;
        }
        if (hasBlocked) {
            return TestCaseResult.TestResultStatus.BLOCKED;
        }
        if (hasNotRun) {
            return TestCaseResult.TestResultStatus.PENDING;
        }
        if (hasFalsePositive) {
            return TestCaseResult.TestResultStatus.FALSE_POSITIVE;
        }
        return TestCaseResult.TestResultStatus.PASSED;
    }

    private static List<String> mergeStepCommentsFromRequest(
        StaplerRequest req, int size, Optional<TestCaseResult> prev) {
        List<String> old = prev.map(TestCaseResult::getStepComments).orElse(List.of());
        List<String> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String key = "stepComment_" + i;
            String[] vals = req.getParameterValues(key);
            String v = null;
            if (vals != null && vals.length > 0) {
                v = vals[vals.length - 1];
            }
            if (v == null) {
                v = req.getParameter(key);
            }
            if (v != null) {
                out.add(v);
            } else if (i < old.size() && old.get(i) != null) {
                out.add(old.get(i));
            } else {
                out.add("");
            }
        }
        return out;
    }

    private static String runDetailCurrentUser() {
        hudson.model.User u = hudson.model.User.current();
        return u != null ? u.getId() : "anonymous";
    }

    private static String trimToNull(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        return s.trim();
    }

    private TestRun currentRun() {
        return TestRunService.get().getByIdOrThrow(runId);
    }

    public static final class UserChoice {
        private final String id;
        private final String label;

        public UserChoice(String id, String displayName) {
            this.id = id;
            String d = (displayName == null || displayName.isBlank()) ? id : displayName;
            this.label = d.equals(id) ? id : (d + " (" + id + ")");
        }

        public String getId() { return id; }
        public String getLabel() { return label; }
    }
}
