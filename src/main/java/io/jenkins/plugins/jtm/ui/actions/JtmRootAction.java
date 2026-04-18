package io.jenkins.plugins.jtm.ui.actions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import hudson.util.MultipartFormDataParser;
import io.jenkins.plugins.jtm.core.domain.*;
import io.jenkins.plugins.jtm.core.service.TestCaseService;
import io.jenkins.plugins.jtm.export.JtmExportBrandingStore;
import io.jenkins.plugins.jtm.importer.ImportTextEncoding;
import io.jenkins.plugins.jtm.importer.JtmDelimitedImportParser;
import io.jenkins.plugins.jtm.importer.JtmTestCaseImportParser;
import io.jenkins.plugins.jtm.core.service.QualityGateService;
import io.jenkins.plugins.jtm.persistence.JtmStore;
import io.jenkins.plugins.jtm.persistence.RunLocks;
import io.jenkins.plugins.jtm.postbuild.JUnitXmlImportParser;
import io.jenkins.plugins.jtm.security.JtmPermissions;
import edu.umd.cs.findbugs.annotations.Nullable;
import jenkins.model.Jenkins;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Part;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.apache.commons.fileupload.FileItem;
import org.kohsuke.stapler.verb.GET;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main Jenkins Root Action for JTM.
 *
 * <p>Accessible at: {@code /jtm/}
 * <ul>
 *   <li>Serves Jelly views for the UI</li>
 *   <li>Handles REST API requests at {@code /jtm/api/}</li>
 *   <li>Provides the top-level navigation entry</li>
 * </ul>
 *
 * @author JTM Development Team
 */
@Extension
public final class JtmRootAction implements UnprotectedRootAction {

    private static final Logger LOG = Logger.getLogger(JtmRootAction.class.getName());

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // ── UnprotectedRootAction ─────────────────────────────────────────────────

    @Override
    @Nullable
    public String getIconFileName() {
        return "symbol-jtm plugin-jtm";
    }

    @Override
    public String getDisplayName() {
        return "JTM Testmanagement";
    }

    @Override
    public String getUrlName() {
        return "jtm";
    }

    // ── Jelly View Accessors ──────────────────────────────────────────────────

    /** Used by index.jelly to display statistics. */
    public DashboardData getDashboardData() {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        JtmStore store = JtmStore.get();
        String project = JtmProjectFilter.current();
        Map<String, Long> counts = store.getLatestRunStatusCounts(project);
        double passRate = store.getLatestRunsPassRate(project);
        List<TestCase> flaky = store.getFlakyTests(5, project);
        List<TestRun> recentRuns = store.findRecentRuns(5, project);
        long projectTotalTestCases = TestCaseService.get().findAll(project).size();

        List<TestRun> durationRuns = store.findRecentRuns(30, project);
        List<RunDurationBar> automatedBars = new ArrayList<>();
        for (TestRun r : durationRuns) {
            automatedBars.add(new RunDurationBar(
                r.getId(),
                r.getName(),
                store.sumAutomatedDurationMsForRun(r)
            ));
        }

        List<FailureTrendPoint> failureTrend = new ArrayList<>();
        for (TestRun r : store.findFailureTrendRuns(project, 80)) {
            failureTrend.add(new FailureTrendPoint(
                r.getId(),
                r.getName(),
                r.getFailedCount(),
                r.getStartedAt().toEpochMilli()));
        }

        return new DashboardData(counts, passRate, flaky, recentRuns, projectTotalTestCases, automatedBars,
            failureTrend);
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

    public String getProjectDeleteStatus() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        return StringUtils.defaultString(req != null ? req.getParameter("projectDeleteStatus") : null);
    }

    public String getProjectDeleteKey() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        return StringUtils.defaultString(req != null ? req.getParameter("projectDeleteKey") : null);
    }

    public long getProjectDeleteCasesCount() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null) return 0L;
        String raw = req.getParameter("projectDeleteCases");
        if (raw == null) return 0L;
        try { return Long.parseLong(raw); }
        catch (NumberFormatException e) { return 0L; }
    }

    public long getProjectDeleteRunsCount() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null) return 0L;
        String raw = req.getParameter("projectDeleteRuns");
        if (raw == null) return 0L;
        try { return Long.parseLong(raw); }
        catch (NumberFormatException e) { return 0L; }
    }

    // ── URL Routing ───────────────────────────────────────────────────────────

    /** Route /jtm/testcases → TestCasesAction */
    public TestCasesAction getTestcases() {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return new TestCasesAction();
    }

    /** Route /jtm/runs → TestRunsAction */
    public TestRunsAction getRuns() {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return new TestRunsAction();
    }

    /** Route /jtm/api/… — REST endpoints under a real {@code api} path (see {@link JtmApiAction}). */
    public JtmApiAction getApi() {
        return new JtmApiAction(this);
    }

    /**
     * POST /jtm/savecase — create test case from newcase form.
     * <p>Must live on the root action: on {@link TestCasesAction}, Stapler dispatches
     * the {@code savecase} segment to {@link TestCasesAction#getDynamic} first, which
     * returns null and yields “page not found” before {@code doSavecase} runs.
     */
    @POST
    public void doSavecase(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        new TestCasesAction().doSavecase(req, rsp);
    }

    /**
     * POST /jtm/saverun — create ad-hoc test run from TestRunsAction/newrun.jelly.
     * <p>Declared on the root action for the same reason as {@link #doSavecase}.
     */
    @POST
    public void doSaverun(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        new TestRunsAction().doSaverun(req, rsp);
    }

    /**
     * POST /jtm/importcases — JSON file upload (multipart) or {@code importJson} body field.
     */
    @POST
    public void doImportcases(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EDIT);
        String payload = req.getParameter("importJson");
        String fileName = StringUtils.defaultString(req.getParameter("importFileName"));
        Part upload = null;
        try {
            upload = req.getPart("importFile");
        } catch (ServletException | IllegalStateException ignored) {
            // Fallback to importJson/importFileName fields (legacy/non-multipart paths).
        }
        if (upload != null && upload.getSize() > 0) {
            byte[] raw = upload.getInputStream().readAllBytes();
            payload = ImportTextEncoding.decode(raw);
            if (upload.getSubmittedFileName() != null && !upload.getSubmittedFileName().isBlank()) {
                fileName = upload.getSubmittedFileName();
            }
        }
        if (payload == null || payload.isBlank()) {
            rsp.sendRedirect2(req.getContextPath() + "/jtm/testcases/?importErr=empty");
            return;
        }
        try {
            JtmTestCaseImportParser.ImportBundle bundle;
            String lowerName = fileName != null ? fileName.toLowerCase(Locale.ROOT) : "";
            String trimmed = payload.trim();
            if (lowerName.endsWith(".csv")) {
                bundle = JtmDelimitedImportParser.parseCsv(payload);
            } else if (lowerName.endsWith(".xml")) {
                bundle = parseXmlImport(payload);
            } else if (lowerName.endsWith(".txt")) {
                // .txt may contain JSON, XML or CSV; auto-detect by first non-whitespace char.
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    bundle = JtmTestCaseImportParser.parse(payload);
                } else if (trimmed.startsWith("<")) {
                    bundle = parseXmlImport(payload);
                } else {
                    bundle = JtmDelimitedImportParser.parseCsv(payload);
                }
            } else {
                // Default keeps JSON compatibility; XML auto-detected by leading tag.
                bundle = trimmed.startsWith("<")
                    ? parseXmlImport(payload)
                    : JtmTestCaseImportParser.parse(payload);
            }
            TestCaseService.ImportStats stats =
                TestCaseService.get().importTestCasesFromBundle(bundle, currentUser());
            rsp.sendRedirect2(req.getContextPath() + "/jtm/testcases/?importOk=1&importCreated="
                + stats.created + "&importSkipped=" + stats.skipped);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[JTM] import failed", e);
            rsp.sendRedirect2(req.getContextPath() + "/jtm/testcases/?importErr=parse");
        }
    }

    private static JtmTestCaseImportParser.ImportBundle parseXmlImport(String payload) throws Exception {
        JUnitXmlImportParser.ParseResult parsed = JUnitXmlImportParser.parse(
            new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));
        JtmTestCaseImportParser.ImportBundle bundle = new JtmTestCaseImportParser.ImportBundle();
        bundle.version = 1;
        List<JtmTestCaseImportParser.ImportCaseDto> cases = new ArrayList<>();
        for (String caseId : parsed.getLinkedCaseIds()) {
            if (caseId == null || caseId.isBlank()) {
                continue;
            }
            JtmTestCaseImportParser.ImportCaseDto dto = new JtmTestCaseImportParser.ImportCaseDto();
            dto.id = caseId.trim();
            dto.title = caseId.trim();
            dto.description = "Imported from XML";
            dto.type = "AUTOMATED";
            cases.add(dto);
        }
        bundle.testCases = cases;
        return bundle;
    }

    /**
     * POST /jtm/clearprojectscope — clear the signed-in user’s saved JTM project filter (see {@link JtmProjectFilter}).
     * Redirects to {@code /jtm/} with no query string so E2E can load the dashboard without {@code ?project=}.
     */
    @POST
    public void doClearprojectscope(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        JtmProjectFilter.clearPreferredProjectForCurrentUser();
        rsp.sendRedirect2(req.getContextPath() + "/jtm/");
    }

    /**
     * POST /jtm/registerproject — persist a new project key for dropdowns and open the dashboard scoped to it.
     */
    @POST
    public void doRegisterproject(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EDIT);
        String key = StringUtils.trimToEmpty(req.getParameter("newProjectScope"));
        if (!key.isEmpty()) {
            JtmStore.get().registerProjectScope(key);
        }
        String cp = req.getContextPath();
        if (key.isEmpty()) {
            rsp.sendRedirect2(cp + "/jtm/");
        } else {
            rsp.sendRedirect2(cp + "/jtm/?project=" + URLEncoder.encode(key, StandardCharsets.UTF_8));
        }
    }

    /**
     * POST /jtm/deleteproject — removes a project from registry if no cases/runs still reference it.
     */
    @POST
    public void doDeleteproject(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EDIT);
        String key = StringUtils.trimToEmpty(req.getParameter("projectScope"));
        String cp = req.getContextPath();
        if (key.isEmpty()) {
            rsp.sendRedirect2(cp + "/jtm/?projectDeleteStatus=missing");
            return;
        }
        JtmStore store = JtmStore.get();
        long caseCount = store.countTestCasesForProject(key);
        long runCount = store.countRunsForProject(key);
        String encoded = URLEncoder.encode(key, StandardCharsets.UTF_8);
        if (caseCount > 0 || runCount > 0) {
            rsp.sendRedirect2(cp + "/jtm/?project=" + encoded
                + "&projectDeleteStatus=blocked"
                + "&projectDeleteKey=" + encoded
                + "&projectDeleteCases=" + caseCount
                + "&projectDeleteRuns=" + runCount);
            return;
        }
        store.unregisterProjectScope(key);
        rsp.sendRedirect2(cp + "/jtm/?projectDeleteStatus=deleted&projectDeleteKey=" + encoded);
    }

    /**
     * GET /jtm/exportBranding — view export branding configuration.
     */
    @GET
    public HttpResponse doExportBranding(StaplerRequest2 req, StaplerResponse2 rsp) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_ADMIN);
        return HttpResponses.forwardToView(this, "exportBrandingPage.jelly");
    }

    /**
     * POST /jtm/exportBrandingSave — upload company logo for run exports (stored under {@code jtm/branding/}).
     */
    @POST
    @SuppressWarnings("deprecation")
    public void doExportBrandingSave(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_ADMIN);
        String cp = req.getContextPath();

        byte[] raw = null;
        String origName = null;
        String mime = null;

        String contentType = req.getContentType();
        if (contentType != null && MultipartFormDataParser.isMultiPartForm(contentType)) {
            try (MultipartFormDataParser parser = new MultipartFormDataParser(req)) {
                FileItem fi = parser.getFileItem("logoFile");
                if (fi != null && !fi.isFormField() && fi.getSize() > 0) {
                    raw = fi.get();
                    origName = fi.getName();
                    mime = fi.getContentType();
                }
            } catch (ServletException e) {
                LOG.log(Level.FINE, "export branding MultipartFormDataParser", e);
            }
        }
        if (raw == null) {
            try {
                Part part = req.getPart("logoFile");
                if (part != null && part.getSize() > 0) {
                    raw = part.getInputStream().readAllBytes();
                    origName = part.getSubmittedFileName();
                    mime = part.getContentType();
                }
            } catch (ServletException | IllegalStateException e) {
                LOG.log(Level.FINE, "export branding getPart", e);
            }
        }

        if (raw != null && raw.length > 0) {
            try {
                JtmExportBrandingStore.get().saveLogo(raw, mime, origName);
                rsp.sendRedirect2(cp + "/jtm/exportBranding?brandingMsg=saved");
            } catch (IOException ex) {
                LOG.log(Level.WARNING, "[JTM] branding save failed", ex);
                rsp.sendRedirect2(cp + "/jtm/exportBranding?brandingMsg=error");
            }
            return;
        }
        rsp.sendRedirect2(cp + "/jtm/exportBranding?brandingMsg=empty");
    }

    /**
     * POST /jtm/exportBrandingClear — clear company logo used in exports.
     */
    @POST
    public void doExportBrandingClear(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        JtmPermissions.checkPermission(JtmPermissions.TEST_ADMIN);
        String cp = req.getContextPath();
        try {
            JtmExportBrandingStore.get().clearLogo();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[JTM] clear branding failed", e);
        }
        rsp.sendRedirect2(cp + "/jtm/exportBranding?brandingMsg=cleared");
    }

    public boolean isExportBrandingLogoPresent() {
        return JtmExportBrandingStore.get().hasLogo();
    }

    public String getExportBrandingMessage() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null) {
            return "";
        }
        return StringUtils.defaultString(req.getParameter("brandingMsg"));
    }

    /**
     * POST /jtm/edittestcase — save metadata and steps from TestCaseDetailAction/edit.jelly.
     */
    @POST
    public void doEdittestcase(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        String id = trim(req.getParameter("jtmCaseId"));
        if (id == null) {
            rsp.sendRedirect2(req.getContextPath() + "/jtm/testcases/");
            return;
        }
        String user = currentUser();
        java.util.List<TestStep> steps = JtmStepFormParser.parseSteps(req);
        java.util.List<String> tags = parseTagsParam(req.getParameter("tags"));

        TestCase.TestCaseType type = parseEnum(req.getParameter("type"),
            TestCase.TestCaseType.class, TestCase.TestCaseType.MANUAL);
        TestCase.Priority prio = parseEnum(req.getParameter("priority"),
            TestCase.Priority.class, TestCase.Priority.MEDIUM);
        TestCase.Risk risk = parseEnum(req.getParameter("risk"),
            TestCase.Risk.class, TestCase.Risk.MEDIUM);
        TestCase.LifecycleStatus life = parseEnum(req.getParameter("lifecycle"),
            TestCase.LifecycleStatus.class, TestCase.LifecycleStatus.DRAFT);

        String createdByOverride = null;
        if (JtmPermissions.hasPermission(JtmPermissions.TEST_ADMIN)) {
            createdByOverride = req.getParameter("createdBy");
        }
        TestCaseService.get().updateTestCaseContent(
            id,
            req.getParameter("title"),
            req.getParameter("description"),
            req.getParameter("preconditions"),
            req.getParameter("expectedResult"),
            steps,
            type, prio, risk, life,
            tags,
            req.getParameter("requirementId"),
            req.getParameter("jiraTicket"),
            StringUtils.trimToEmpty(req.getParameter("projectScope")),
            user,
            createdByOverride
        );
        rsp.sendRedirect2(req.getContextPath() + "/jtm/testcases/" + id + "/");
    }

    /**
     * POST /jtm/deletetestcase — permanently delete (JTM Admin or creator of the case).
     */
    @POST
    public void doDeletetestcase(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        String id = trim(req.getParameter("jtmCaseId"));
        if (id == null) {
            rsp.sendRedirect2(req.getContextPath() + "/jtm/testcases/");
            return;
        }
        TestCaseService.get().deleteTestCase(id, currentUser());
        rsp.sendRedirect2(req.getContextPath() + "/jtm/testcases/");
    }

    /**
     * POST /jtm/resetJtmData — clear all JTM persisted data (admin only). For E2E or support.
     */
    @POST
    public void doResetJtmData(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        JtmStore.get().resetAllData();
        rsp.sendRedirect2(req.getContextPath() + "/jtm/");
    }

    /**
     * POST /jtm/resetjtmdata — alias for {@link #doResetJtmData} (older Stapler-style name).
     */
    @POST
    public void doResetjtmdata(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        doResetJtmData(req, rsp);
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String currentUser() {
        hudson.model.User u = hudson.model.User.current();
        return u != null ? u.getId() : "anonymous";
    }

    private static java.util.List<String> parseTagsParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Collections.emptyList();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String t : raw.split("[,;\\s]+")) {
            if (!t.isBlank()) {
                out.add(t.trim());
            }
        }
        return out;
    }

    private static <T extends Enum<T>> T parseEnum(String val, Class<T> type, T def) {
        if (val == null || val.isBlank()) {
            return def;
        }
        try {
            return Enum.valueOf(type, val.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    // ── REST API: /jtm/api/* (routed via {@link JtmApiAction#getDynamic}) ─────

    void serveApiMethodNotAllowed(StaplerRequest2 req, StaplerResponse2 rsp, String method)
        throws IOException {
        sendError(rsp, 405, "Method not allowed: " + method);
    }

    /** GET /jtm/api/testcases */
    void serveApiTestcases(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        try {
            int page = intParam(req, "page", 0);
            int pageSize = intParam(req, "pageSize", 50);
            String status = req.getParameter("status");
            String type = req.getParameter("type");
            String suite = req.getParameter("suite");
            String q = req.getParameter("q");

            TestCaseService.PagedResult<TestCase> result =
                TestCaseService.get().findPaginated(page, pageSize, status, type, suite, q);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("items", result.getItems());
            response.put("totalCount", result.getTotalCount());
            response.put("page", result.getPage());
            response.put("pageSize", result.getPageSize());
            response.put("totalPages", result.getTotalPages());
            response.put("hasNext", result.hasNext());
            response.put("hasPrevious", result.hasPrevious());

            sendJson(rsp, 200, response);
        } catch (Exception e) {
            sendError(rsp, 500, e.getMessage());
        }
    }

    /** GET /jtm/api/testcase/{id} */
    void serveApiTestcaseGet(String id, StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        TestCaseService.get().findById(id).ifPresentOrElse(
            tc -> sendJsonSafe(rsp, 200, tc, "Failed to write testcase response"),
            () -> sendErrorSafe(rsp, 404, "TestCase not found: " + id, "Failed to write testcase-not-found response")
        );
    }

    /** POST /jtm/api/testcases */
    void serveApiCreateTestcase(StaplerRequest2 req, StaplerResponse2 rsp)
        throws IOException, ServletException {
        try {
            TestCase template = JSON.readValue(req.getInputStream(), TestCase.class);
            String user = getApiUser(req);
            TestCase created = TestCaseService.get().createTestCase(template, user);
            sendJson(rsp, 201, created);
        } catch (IllegalArgumentException e) {
            sendError(rsp, 400, e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[JTM API] Create failed", e);
            sendError(rsp, 500, "Internal error: " + e.getMessage());
        }
    }

    /** POST /jtm/api/testcase/{id}/update */
    void serveApiTestcaseUpdate(String id, StaplerRequest2 req, StaplerResponse2 rsp)
        throws IOException, ServletException {
        try {
            byte[] body = req.getInputStream().readAllBytes();
            TestCase update = JSON.readValue(body, TestCase.class);
            String user = getApiUser(req);
            TestCase updated = TestCaseService.get().updateTestCase(id, update, user);
            sendJson(rsp, 200, updated);
        } catch (NoSuchElementException e) {
            sendError(rsp, 404, e.getMessage());
        } catch (IllegalArgumentException e) {
            sendError(rsp, 400, e.getMessage());
        } catch (Exception e) {
            sendError(rsp, 500, e.getMessage());
        }
    }

    /** POST /jtm/api/testcase/{id}/status */
    void serveApiUpdateStatus(StaplerRequest2 req, StaplerResponse2 rsp)
        throws IOException, ServletException {
        try {
            @SuppressWarnings("unchecked") Map<String, Object> body =
                (Map<String, Object>) JSON.readValue(req.getInputStream(), Map.class);
            if (body == null) {
                throw new IllegalArgumentException("Request body is required");
            }
            String id = requireString(body, "id");
            String statusStr = requireString(body, "status");
            String user = body.containsKey("updatedBy")
                ? optionalString(body.get("updatedBy"), "updatedBy")
                : getApiUser(req);
            int buildNumber = parseBuildNumber(body.get("buildNumber"));

            TestCase.TestCaseStatus newStatus = TestCase.TestCaseStatus.valueOf(
                statusStr.trim().toUpperCase(Locale.ROOT));
            TestCase updated = TestCaseService.get().updateStatus(
                id, newStatus, user, buildNumber, 5);

            sendJson(rsp, 200, updated);
        } catch (IllegalArgumentException e) {
            sendError(rsp, 400, e.getMessage());
        } catch (NoSuchElementException e) {
            sendError(rsp, 404, e.getMessage());
        } catch (Exception e) {
            sendError(rsp, 500, e.getMessage());
        }
    }

    /** POST /jtm/api/testcase/{id}/delete */
    void serveApiTestcaseDelete(String id, StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        try {
            String user = getApiUser(req);
            TestCaseService.get().deleteTestCase(id, user);
            sendJson(rsp, 200, Map.of("deleted", true, "id", id));
        } catch (NoSuchElementException e) {
            sendError(rsp, 404, e.getMessage());
        } catch (Exception e) {
            sendError(rsp, 500, e.getMessage());
        }
    }

    /** POST /jtm/api/testruns */
    void serveApiTestruns(StaplerRequest2 req, StaplerResponse2 rsp)
        throws IOException, ServletException {
        try {
            TestRun run = JSON.readValue(req.getInputStream(), TestRun.class);
            if (run.getId() == null) {
                run.setId(JtmStore.get().generateRunId());
            }
            String id = run.getId();
            synchronized (RunLocks.lockFor(id)) {
                JtmStore store = JtmStore.get();
                store.findRunById(id).ifPresent(existing -> mergeApiStepStatusesFromExisting(existing, run));
                store.saveRun(run);
            }
            sendJson(rsp, 201, run);
        } catch (Exception e) {
            sendError(rsp, 500, e.getMessage());
        }
    }

    /** API payloads often omit {@code stepStatuses}; never wipe manual step progress on upsert. */
    private static void mergeApiStepStatusesFromExisting(TestRun existing, TestRun incoming) {
        for (TestCaseResult in : incoming.getResults()) {
            existing.getResultFor(in.getTestCaseId()).ifPresent(prev -> {
                if (in.getStepStatuses().isEmpty() && !prev.getStepStatuses().isEmpty()) {
                    in.setStepStatuses(new ArrayList<>(prev.getStepStatuses()));
                }
                if (in.getStepComments().isEmpty() && !prev.getStepComments().isEmpty()) {
                    in.setStepComments(new ArrayList<>(prev.getStepComments()));
                }
            });
        }
    }

    /** GET /jtm/api/dashboard/summary */
    void serveApiDashboardSummary(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        try {
            JtmStore store = JtmStore.get();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("statusCounts", store.getLatestRunStatusCounts(null));
            summary.put("passRate", Math.round(store.getLatestRunsPassRate(null) * 10.0) / 10.0);
            summary.put("totalTestCases", store.findAllTestCases().size());
            summary.put("flakyTests", store.getFlakyTests(10));
            summary.put("recentRuns", store.findRecentRuns(10));
            summary.put("timestamp", System.currentTimeMillis());
            sendJson(rsp, 200, summary);
        } catch (Exception e) {
            sendError(rsp, 500, e.getMessage());
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    void sendJson(StaplerResponse2 rsp, int status, Object data) throws IOException {
        rsp.setStatus(status);
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.setHeader("X-JTM-Version", "1.0.0");
        rsp.setHeader("Cache-Control", "no-cache");
        try (PrintWriter writer = rsp.getWriter()) {
            JSON.writeValue(writer, data);
        }
    }

    void sendError(StaplerResponse2 rsp, int status, String message) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", true);
        error.put("status", status);
        error.put("message", message);
        error.put("timestamp", System.currentTimeMillis());
        sendJson(rsp, status, error);
    }

    private int parseBuildNumber(Object raw) {
        if (raw == null) {
            return 0;
        }
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        if (raw instanceof String) {
            try {
                return Integer.parseInt(((String) raw).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("buildNumber must be a number");
            }
        }
        throw new IllegalArgumentException("buildNumber must be a number");
    }

    private String requireString(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (!(raw instanceof String)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        String out = ((String) raw).trim();
        if (out.isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return out;
    }

    private String optionalString(Object raw, String key) {
        if (raw == null) {
            return getApiUser(null);
        }
        if (!(raw instanceof String)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        String out = ((String) raw).trim();
        return out.isEmpty() ? getApiUser(null) : out;
    }

    private void sendJsonSafe(StaplerResponse2 rsp, int status, Object data, String logMessage) {
        try {
            sendJson(rsp, status, data);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[JTM API] " + logMessage, e);
        }
    }

    private void sendErrorSafe(StaplerResponse2 rsp, int status, String message, String logMessage) {
        try {
            sendError(rsp, status, message);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[JTM API] " + logMessage, e);
        }
    }

    private String getApiUser(StaplerRequest2 req) {
        hudson.model.User user = hudson.model.User.current();
        return user != null ? user.getId() : "anonymous";
    }

    private int intParam(StaplerRequest2 req, String name, int defaultValue) {
        String val = req.getParameter(name);
        if (val == null) return defaultValue;
        try { return Integer.parseInt(val); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    // ── Dashboard Data DTO ────────────────────────────────────────────────────

    public static final class DashboardData {
        private final Map<String, Long> statusCounts;
        private final double passRate;
        private final List<TestCase> flakyTests;
        private final List<TestRun> recentRuns;
        private final long projectTotalTestCases;
        private final List<RunDurationBar> automatedRunDurations;
        private final List<FailureTrendPoint> failureTrend;
        private final long failureTrendMaxFailed;

        public DashboardData(Map<String, Long> statusCounts, double passRate,
                              List<TestCase> flakyTests, List<TestRun> recentRuns,
                              long projectTotalTestCases, List<RunDurationBar> automatedRunDurations,
                              List<FailureTrendPoint> failureTrend) {
            this.statusCounts = statusCounts;
            this.passRate = passRate;
            this.flakyTests = flakyTests;
            this.recentRuns = recentRuns;
            this.projectTotalTestCases = projectTotalTestCases;
            this.automatedRunDurations = automatedRunDurations != null
                ? automatedRunDurations : java.util.Collections.emptyList();
            this.failureTrend = failureTrend != null ? failureTrend : java.util.Collections.emptyList();
            this.failureTrendMaxFailed = this.failureTrend.stream()
                .mapToLong(FailureTrendPoint::getFailedCount)
                .max()
                .orElse(0L);
        }

        public Map<String, Long> getStatusCounts() { return statusCounts; }
        public double getPassRate() { return passRate; }

        /** Pre-formatted for dashboard KPI (avoids fmt taglib / EL edge cases). */
        public String getOverallPassRateLabel() {
            return String.format(Locale.ROOT, "%.1f", passRate);
        }
        public List<TestCase> getFlakyTests() { return flakyTests; }
        public List<TestRun> getRecentRuns() { return recentRuns; }
        /** Jelly-safe; prefer over {@code recentRuns.size()} in views. */
        public int getRecentRunsCount() { return recentRuns.size(); }
        public int getFlakyTestsCount() { return flakyTests.size(); }
        public boolean isRecentRunsEmpty() { return recentRuns.isEmpty(); }
        public boolean isFlakyTestsEmpty() { return flakyTests.isEmpty(); }
        public long getProjectTotalTestCases() { return projectTotalTestCases; }
        public List<RunDurationBar> getAutomatedRunDurations() { return automatedRunDurations; }
        public int getAutomatedRunDurationsCount() { return automatedRunDurations.size(); }
        public long getTotal() { return statusCounts.getOrDefault("TOTAL", 0L); }
        public long getPassed() { return statusCounts.getOrDefault("PASSED", 0L); }
        public long getFailed() { return statusCounts.getOrDefault("FAILED", 0L); }
        public long getBlocked() { return statusCounts.getOrDefault("BLOCKED", 0L); }
        public long getPending() { return statusCounts.getOrDefault("PENDING", 0L); }
        public long getFalsePositive() { return statusCounts.getOrDefault("FALSE_POSITIVE", 0L); }
        public long getSkipped() { return statusCounts.getOrDefault("SKIPPED", 0L); }
        public List<FailureTrendPoint> getFailureTrend() { return failureTrend; }
        public int getFailureTrendCount() { return failureTrend.size(); }
        public long getFailureTrendMaxFailed() { return Math.max(1L, failureTrendMaxFailed); }

        /** SVG viewBox 0 0 100 40: x = time, y = failed count (higher = more failures). */
        public String getFailureTrendSvgPoints() {
            if (failureTrend.isEmpty()) {
                return "";
            }
            long max = getFailureTrendMaxFailed();
            int n = failureTrend.size();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                FailureTrendPoint p = failureTrend.get(i);
                double x = n <= 1 ? 50.0 : (100.0 * i / (n - 1));
                double y = 40.0 - (40.0 * p.getFailedCount() / max);
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(String.format(Locale.ROOT, "%.2f,%.2f", x, y));
            }
            return sb.toString();
        }

        public String getFailureTrendOldestLabel() {
            if (failureTrend.isEmpty()) {
                return "";
            }
            return failureTrend.get(0).getRunName();
        }

        public String getFailureTrendNewestLabel() {
            if (failureTrend.isEmpty()) {
                return "";
            }
            return failureTrend.get(failureTrend.size() - 1).getRunName();
        }
    }

    /** One point for the dashboard failed-tests trend chart. */
    public static final class FailureTrendPoint {
        private final String runId;
        private final String runName;
        private final long failedCount;
        private final long startedAtEpochMs;

        public FailureTrendPoint(String runId, String runName, long failedCount, long startedAtEpochMs) {
            this.runId = runId;
            this.runName = runName;
            this.failedCount = failedCount;
            this.startedAtEpochMs = startedAtEpochMs;
        }

        public String getRunId() { return runId; }
        public String getRunName() { return runName; }
        public long getFailedCount() { return failedCount; }
        public long getStartedAtEpochMs() { return startedAtEpochMs; }
    }

    public static final class RunDurationBar {
        private final String runId;
        private final String runName;
        private final long automatedDurationMs;

        public RunDurationBar(String runId, String runName, long automatedDurationMs) {
            this.runId = runId;
            this.runName = runName;
            this.automatedDurationMs = Math.max(0L, automatedDurationMs);
        }

        public String getRunId() { return runId; }
        public String getRunName() { return runName; }
        public long getAutomatedDurationMs() { return automatedDurationMs; }

        public String getAutomatedDurationFormatted() {
            long ms = automatedDurationMs;
            if (ms < 1000) return ms + "ms";
            if (ms < 60_000) return String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0);
            long sec = ms / 1000;
            long min = sec / 60;
            long rem = sec % 60;
            return min + "m " + rem + "s";
        }
    }
}
