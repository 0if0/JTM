package io.jenkins.plugins.jtm.core.service;

import io.jenkins.plugins.jtm.core.domain.*;
import io.jenkins.plugins.jtm.importer.JtmTestCaseImportParser;
import io.jenkins.plugins.jtm.persistence.JtmStore;
import io.jenkins.plugins.jtm.security.AuditEntry;
import io.jenkins.plugins.jtm.security.JtmPermissions;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Core business logic service for test case management.
 *
 * <p>All operations:
 * <ul>
 *   <li>Validate inputs</li>
 *   <li>Check permissions</li>
 *   <li>Delegate to JtmStore</li>
 *   <li>Write audit entries</li>
 *   <li>Fire domain events</li>
 * </ul>
 *
 * @author JTM Development Team
 */
public final class TestCaseService {

    private static final Logger LOG = Logger.getLogger(TestCaseService.class.getName());

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static final TestCaseService INSTANCE = new TestCaseService();

    public static TestCaseService get() { return INSTANCE; }

    private final JtmStore store;

    private TestCaseService() {
        this.store = JtmStore.get();
    }

    // ── Create ─────────────────────────────────────────────────────────────────

    /**
     * Creates a new test case.
     *
     * @param title         display title (required)
     * @param type          test case type (required)
     * @param priority      priority level
     * @param createdByUser Jenkins username of creator
     * @return the persisted TestCase with assigned ID
     * @throws IllegalArgumentException if validation fails
     */
    public TestCase createTestCase(String title, TestCase.TestCaseType type,
                                   TestCase.Priority priority, String createdByUser) {
        return createTestCase(title, type, priority, "", Collections.emptyList(),
            Collections.emptyList(), createdByUser);
    }

    /**
     * Creates a new test case including description, tags, and steps (normalized like updates).
     */
    public TestCase createTestCase(String title, TestCase.TestCaseType type,
                                   TestCase.Priority priority,
                                   String description, List<String> tags, List<TestStep> rawSteps,
                                   String createdByUser) {
        return createTestCase(title, type, priority, description, tags, rawSteps, createdByUser, null);
    }

    public TestCase createTestCase(String title, TestCase.TestCaseType type,
                                   TestCase.Priority priority,
                                   String description, List<String> tags, List<TestStep> rawSteps,
                                   String createdByUser, String projectScope) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EDIT);
        validateTitle(title);

        List<TestStep> steps = normalizeSteps(rawSteps);
        List<String> tagList = tags != null ? new ArrayList<>(tags) : Collections.emptyList();

        String id = store.generateTestCaseId();
        TestCase tc = TestCase.builder()
            .id(id)
            .title(title.trim())
            .description(StringUtils.defaultString(description))
            .type(type != null ? type : TestCase.TestCaseType.MANUAL)
            .priority(priority != null ? priority : TestCase.Priority.MEDIUM)
            .risk(TestCase.Risk.MEDIUM)
            .lifecycleStatus(TestCase.LifecycleStatus.DRAFT)
            .tags(tagList)
            .steps(steps)
            .projectScope(StringUtils.trimToEmpty(projectScope))
            .lastStatus(TestCase.TestCaseStatus.PENDING)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .createdBy(createdByUser)
            .updatedBy(createdByUser)
            .build();

        store.saveTestCase(tc);
        audit(AuditEntry.Action.TEST_CASE_CREATED, "TestCase", id, createdByUser,
            null, null, "Created: " + title + (steps.isEmpty() ? "" : " (" + steps.size() + " steps)"));

        LOG.info("[JTM] Created test case " + id + ": " + title);
        return tc;
    }

    /**
     * Creates a test case with an explicit id (e.g. pipeline auto-create for a known external id).
     */
    public TestCase createTestCaseWithFixedId(String id, String title, TestCase.TestCaseType type,
                                              TestCase.Priority priority, String createdByUser) {
        return createTestCaseWithFixedId(id, title, type, priority, createdByUser, "");
    }

    /**
     * Creates a test case with an explicit id and project key.
     */
    public TestCase createTestCaseWithFixedId(String id,
                                              String title,
                                              TestCase.TestCaseType type,
                                              TestCase.Priority priority,
                                              String createdByUser,
                                              String projectScope) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EDIT);
        validateTitle(title);
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("TestCase id must not be blank");
        }
        id = id.trim();
        if (store.findTestCaseById(id).isPresent()) {
            throw new IllegalArgumentException("TestCase already exists: " + id);
        }

        TestCase tc = TestCase.builder()
            .id(id)
            .title(title.trim())
            .type(type != null ? type : TestCase.TestCaseType.MANUAL)
            .priority(priority != null ? priority : TestCase.Priority.MEDIUM)
            .risk(TestCase.Risk.MEDIUM)
            .lifecycleStatus(TestCase.LifecycleStatus.DRAFT)
            .lastStatus(TestCase.TestCaseStatus.PENDING)
            .projectScope(StringUtils.trimToEmpty(projectScope))
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .createdBy(createdByUser)
            .updatedBy(createdByUser)
            .build();

        store.saveTestCase(tc);
        audit(AuditEntry.Action.TEST_CASE_CREATED, "TestCase", id, createdByUser,
            null, null, "Created with fixed id: " + title);

        LOG.info("[JTM] Created test case " + id + ": " + title);
        return tc;
    }

    /**
     * Creates a test case from a fully populated builder result.
     */
    public TestCase createTestCase(TestCase template, String createdByUser) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EDIT);
        validateTitle(template.getTitle());

        String id = store.generateTestCaseId();
        TestCase tc = template.toBuilder()
            .id(id)
            .version(1)
            .lastStatus(TestCase.TestCaseStatus.PENDING)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .createdBy(createdByUser)
            .updatedBy(createdByUser)
            .flakyScore(0)
            .build();

        store.saveTestCase(tc);
        audit(AuditEntry.Action.TEST_CASE_CREATED, "TestCase", id, createdByUser,
            null, null, "Created via API: " + template.getTitle());
        return tc;
    }

    // ── Read ───────────────────────────────────────────────────────────────────

    public Optional<TestCase> findById(String id) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return store.findTestCaseById(id);
    }

    public TestCase getByIdOrThrow(String id) {
        return findById(id).orElseThrow(() ->
            new NoSuchElementException("TestCase not found: " + id));
    }

    public List<TestCase> findAll() {
        return findAll(null);
    }

    /** @param projectScopeFilter optional; blank = all projects */
    public List<TestCase> findAll(String projectScopeFilter) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return store.findAllTestCases(projectScopeFilter);
    }

    public PagedResult<TestCase> findPaginated(int page, int pageSize,
                                                String statusFilter, String typeFilter,
                                                String suiteId, String query) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        if (page < 0) page = 0;
        if (pageSize < 1 || pageSize > 200) pageSize = 50;

        List<TestCase> items = store.findTestCasesPaginated(
            page, pageSize, statusFilter, typeFilter, suiteId, query);
        long total = store.countTestCases(statusFilter, typeFilter, suiteId, query);

        return new PagedResult<>(items, total, page, pageSize);
    }

    // ── Update ─────────────────────────────────────────────────────────────────

    public TestCase updateTestCase(String id, TestCase updated, String updatedByUser) {
        validateTitle(updated.getTitle());
        TestCase existing = getByIdOrThrow(id);
        JtmPermissions.checkEditTestCase(existing);

        TestCase newVersion = existing.toBuilder()
            .title(updated.getTitle().trim())
            .description(updated.getDescription())
            .preconditions(updated.getPreconditions())
            .expectedResult(updated.getExpectedResult())
            .steps(updated.getSteps())
            .type(updated.getType())
            .priority(updated.getPriority())
            .risk(updated.getRisk())
            .tags(updated.getTags())
            .lifecycleStatus(updated.getLifecycleStatus())
            .linkedJob(updated.getLinkedJob())
            .requirementId(updated.getRequirementId())
            .jiraTicket(updated.getJiraTicket())
            .projectScope(updated.getProjectScope())
            .parentSuiteId(updated.getParentSuiteId())
            .version(existing.getVersion() + 1)
            .updatedAt(Instant.now())
            .updatedBy(updatedByUser)
            .build();

        store.saveTestCase(newVersion);
        audit(AuditEntry.Action.TEST_CASE_UPDATED, "TestCase", id, updatedByUser,
            existing.getTitle(), newVersion.getTitle(), "Updated by " + updatedByUser);
        return newVersion;
    }

    /**
     * Updates design-time fields (metadata + steps) from the UI while preserving
     * execution history (last status, flaky score, last run, etc.).
     */
    public TestCase updateTestCaseContent(String id, String title, String description,
                                          String preconditions, String expectedResult,
                                          List<TestStep> steps,
                                          TestCase.TestCaseType type, TestCase.Priority priority,
                                          TestCase.Risk risk, TestCase.LifecycleStatus lifecycle,
                                          List<String> tags,
                                          String requirementId, String jiraTicket,
                                          String projectScope,
                                          String updatedByUser) {
        return updateTestCaseContent(id, title, description, preconditions, expectedResult,
            steps, type, priority, risk, lifecycle, tags, requirementId, jiraTicket, projectScope,
            updatedByUser, null);
    }

    /**
     * @param createdByOverride when non-null and the caller is a JTM admin, updates recorded creator id.
     */
    public TestCase updateTestCaseContent(String id, String title, String description,
                                          String preconditions, String expectedResult,
                                          List<TestStep> steps,
                                          TestCase.TestCaseType type, TestCase.Priority priority,
                                          TestCase.Risk risk, TestCase.LifecycleStatus lifecycle,
                                          List<String> tags,
                                          String requirementId, String jiraTicket,
                                          String projectScope,
                                          String updatedByUser,
                                          String createdByOverride) {
        validateTitle(title);
        TestCase existing = getByIdOrThrow(id);
        JtmPermissions.checkEditTestCase(existing);

        List<TestStep> normalized = normalizeSteps(steps);

        String req = StringUtils.trimToNull(requirementId);
        String jira = StringUtils.trimToNull(jiraTicket);

        TestCase.Builder b = existing.toBuilder()
            .title(title.trim())
            .description(StringUtils.defaultString(description))
            .preconditions(StringUtils.defaultString(preconditions))
            .expectedResult(StringUtils.defaultString(expectedResult))
            .steps(normalized)
            .type(type != null ? type : existing.getType())
            .priority(priority != null ? priority : existing.getPriority())
            .risk(risk != null ? risk : existing.getRisk())
            .lifecycleStatus(lifecycle != null ? lifecycle : existing.getLifecycleStatus())
            .tags(tags != null ? tags : Collections.emptyList())
            .requirementId(req)
            .jiraTicket(jira)
            .projectScope(StringUtils.trimToEmpty(projectScope))
            .version(existing.getVersion() + 1)
            .updatedAt(Instant.now())
            .updatedBy(updatedByUser);
        if (createdByOverride != null && JtmPermissions.hasPermission(JtmPermissions.TEST_ADMIN)) {
            String cb = StringUtils.trimToNull(createdByOverride);
            if (cb != null) {
                b.createdBy(cb);
            }
        }
        TestCase newVersion = b.build();

        store.saveTestCase(newVersion);
        audit(AuditEntry.Action.TEST_CASE_UPDATED, "TestCase", id, updatedByUser,
            existing.getTitle(), newVersion.getTitle(), "Content updated (incl. steps)");
        return newVersion;
    }

    /**
     * Bulk import from {@link JtmTestCaseImportParser} bundle; skips rows whose id already exists.
     */
    public ImportStats importTestCasesFromBundle(JtmTestCaseImportParser.ImportBundle bundle,
                                                 String user) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EDIT);
        if (bundle == null || bundle.testCases == null || bundle.testCases.isEmpty()) {
            return new ImportStats(0, 0);
        }
        int created = 0;
        int skipped = 0;
        for (JtmTestCaseImportParser.ImportCaseDto dto : bundle.testCases) {
            if (dto == null || StringUtils.isBlank(dto.title)) {
                skipped++;
                continue;
            }
            String wantId = StringUtils.trimToNull(dto.id);
            if (wantId != null && store.findTestCaseById(wantId).isPresent()) {
                skipped++;
                continue;
            }
            String id = wantId != null ? wantId : store.generateTestCaseId();
            List<TestStep> steps = importSteps(dto);
            TestCase tc = TestCase.builder()
                .id(id)
                .title(dto.title.trim())
                .description(StringUtils.defaultString(dto.description))
                .type(parseEnum(dto.type, TestCase.TestCaseType.class, TestCase.TestCaseType.MANUAL))
                .priority(parseEnum(dto.priority, TestCase.Priority.class, TestCase.Priority.MEDIUM))
                .risk(parseEnum(dto.risk, TestCase.Risk.class, TestCase.Risk.MEDIUM))
                .lifecycleStatus(parseEnum(dto.lifecycleStatus, TestCase.LifecycleStatus.class,
                    TestCase.LifecycleStatus.DRAFT))
                .tags(dto.tags != null ? new ArrayList<>(dto.tags) : Collections.emptyList())
                .steps(steps)
                .projectScope(StringUtils.trimToEmpty(dto.projectScope))
                .lastStatus(TestCase.TestCaseStatus.PENDING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .createdBy(user)
                .updatedBy(user)
                .build();
            store.saveTestCase(tc);
            audit(AuditEntry.Action.TEST_CASE_CREATED, "TestCase", id, user,
                null, null, "Imported: " + tc.getTitle());
            created++;
        }
        LOG.info("[JTM] Import finished: created=" + created + ", skipped=" + skipped);
        return new ImportStats(created, skipped);
    }

    private static List<TestStep> importSteps(JtmTestCaseImportParser.ImportCaseDto dto) {
        if (dto.steps == null || dto.steps.isEmpty()) {
            return Collections.emptyList();
        }
        List<TestStep> out = new ArrayList<>();
        int ord = 1;
        for (JtmTestCaseImportParser.ImportStepDto s : dto.steps) {
            if (s == null) {
                continue;
            }
            String a = StringUtils.trimToEmpty(s.action);
            String e = s.expectedResult != null ? s.expectedResult.trim() : "";
            if (a.isEmpty() && e.isEmpty()) {
                continue;
            }
            if (a.isEmpty()) {
                a = "(no action)";
            }
            TestStep ts = new TestStep(ord++, a, e);
            ts.setStatus(TestStep.StepStatus.NOT_RUN);
            out.add(ts);
        }
        return out;
    }

    private static <T extends Enum<T>> T parseEnum(String raw, Class<T> type, T def) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    public static final class ImportStats {
        public final int created;
        public final int skipped;

        public ImportStats(int created, int skipped) {
            this.created = created;
            this.skipped = skipped;
        }
    }

    private static List<TestStep> normalizeSteps(List<TestStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return Collections.emptyList();
        }
        List<TestStep> out = new ArrayList<>();
        int ord = 1;
        for (TestStep s : steps) {
            if (s == null) {
                continue;
            }
            String a = StringUtils.trimToEmpty(s.getAction());
            String e = s.getExpectedResult() != null ? s.getExpectedResult().trim() : "";
            if (a.isEmpty() && e.isEmpty()) {
                continue;
            }
            if (a.isEmpty()) {
                a = "(no action)";
            }
            TestStep copy = new TestStep(ord, a, e);
            copy.setStatus(TestStep.StepStatus.NOT_RUN);
            out.add(copy);
            ord++;
        }
        return out;
    }

    /**
     * Updates just the status of a test case — lightweight operation
     * used by pipeline steps and CI/CD synchronization.
     *
     * @param flakyIncrease how much to increase the flaky score (0-10 per call)
     */
    public TestCase updateStatus(String id, TestCase.TestCaseStatus newStatus,
                                  String updatedByUser, int buildNumber,
                                  int flakyIncrease) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EXECUTE);
        TestCase existing = getByIdOrThrow(id);

        boolean statusChanged = existing.getLastStatus() != newStatus;
        boolean countsAsFlakyFlip = statusChanged
            && existing.getLastStatus() != TestCase.TestCaseStatus.PENDING;
        int newFlakyScore = Math.min(100,
            existing.getFlakyScore() + (countsAsFlakyFlip ? flakyIncrease : 0));

        TestCase updated = existing.withStatus(newStatus, updatedByUser, buildNumber, newFlakyScore);
        store.saveTestCase(updated);

        audit(AuditEntry.Action.TEST_CASE_STATUS_CHANGED, "TestCase", id, updatedByUser,
            existing.getLastStatus().name(), newStatus.name(),
            "Build #" + buildNumber + " → " + newStatus);

        LOG.info("[JTM] Status update: " + id + " → " + newStatus + " (build #" + buildNumber + ")");
        return updated;
    }

    // ── Delete ─────────────────────────────────────────────────────────────────

    public void deleteTestCase(String id, String deletedByUser) {
        TestCase existing = getByIdOrThrow(id);
        JtmPermissions.checkDeleteTestCase(existing);

        store.deleteTestCase(id);
        audit(AuditEntry.Action.TEST_CASE_DELETED, "TestCase", id, deletedByUser,
            existing.getTitle(), null, "Deleted by " + deletedByUser);
        LOG.info("[JTM] Deleted test case " + id);
    }

    // ── Statistics ─────────────────────────────────────────────────────────────

    public Map<String, Long> getStatusCounts() {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return store.getStatusCounts();
    }

    public double getOverallPassRate() {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return store.getOverallPassRate();
    }

    public List<TestCase> getFlakyTests(int limit) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return store.getFlakyTests(limit);
    }

    // ── Private Helpers ────────────────────────────────────────────────────────

    private void validateTitle(String title) {
        if (StringUtils.isBlank(title)) {
            throw new IllegalArgumentException("TestCase title must not be blank");
        }
        if (title.trim().length() > 500) {
            throw new IllegalArgumentException("TestCase title too long (max 500 chars)");
        }
    }

    private void audit(AuditEntry.Action action, String entityType, String entityId,
                       String user, String oldVal, String newVal, String details) {
        AuditEntry entry = AuditEntry.builder(UUID.randomUUID().toString(), action)
            .entityType(entityType)
            .entityId(entityId)
            .performedBy(user)
            .oldValue(oldVal)
            .newValue(newVal)
            .details(details)
            .build();
        store.appendAuditEntry(entry);
    }

    // ── PagedResult ────────────────────────────────────────────────────────────

    public static final class PagedResult<T> {
        private final List<T> items;
        private final long totalCount;
        private final int page;
        private final int pageSize;
        private final int totalPages;

        public PagedResult(List<T> items, long totalCount, int page, int pageSize) {
            this.items = Collections.unmodifiableList(items);
            this.totalCount = totalCount;
            this.page = page;
            this.pageSize = pageSize;
            this.totalPages = pageSize > 0 ? (int) Math.ceil((double) totalCount / pageSize) : 0;
        }

        public List<T> getItems() { return items; }
        public long getTotalCount() { return totalCount; }
        public int getPage() { return page; }
        public int getPageSize() { return pageSize; }
        public int getTotalPages() { return totalPages; }
        public boolean hasNext() { return page < totalPages - 1; }
        public boolean hasPrevious() { return page > 0; }
    }
}
