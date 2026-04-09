package io.jenkins.plugins.jtm.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import hudson.Extension;
import hudson.XmlFile;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import io.jenkins.plugins.jtm.core.domain.*;
import io.jenkins.plugins.jtm.security.AuditEntry;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Thread-safe persistence store for JTM domain objects.
 *
 * <p>Uses Jenkins' home directory for storage, compatible with
 * Jenkins' built-in file-based persistence. Each entity type
 * is stored in its own subdirectory as individual JSON files,
 * enabling easy migration to an external database later.
 *
 * <p>Persistence layout:
 * <pre>
 *   $JENKINS_HOME/
 *     jtm/
 *       testcases/
 *         TC-001.json
 *         TC-002.json
 *       suites/
 *         SUITE-001.json
 *       runs/
 *         RUN-001.json
 *       project-registry.json
 *       audit/
 *         2024-01/
 *           audit-2024-01-15.json
 * </pre>
 *
 * <p>Designed for scalability up to 10,000+ test cases via:
 * <ul>
 *   <li>ReadWriteLock for concurrent read access</li>
 *   <li>In-memory index (ConcurrentHashMap)</li>
 *   <li>Lazy loading on startup</li>
 *   <li>Async writes via ExecutorService</li>
 * </ul>
 *
 * @author JTM Development Team
 */
public final class JtmStore {

    private static final Logger LOG = Logger.getLogger(JtmStore.class.getName());

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static volatile JtmStore instance;

    public static JtmStore get() {
        if (instance == null) {
            synchronized (JtmStore.class) {
                if (instance == null) {
                    instance = new JtmStore();
                }
            }
        }
        return instance;
    }

    // ── Storage ───────────────────────────────────────────────────────────────
    private final Map<String, TestCase> testCaseIndex = new ConcurrentHashMap<>();
    private final Map<String, TestSuite> suiteIndex = new ConcurrentHashMap<>();
    private final Map<String, TestRun> runIndex = new ConcurrentHashMap<>();

    private final ReadWriteLock testCaseLock = new ReentrantReadWriteLock();
    private final ReadWriteLock suiteLock = new ReentrantReadWriteLock();
    private final ReadWriteLock runLock = new ReentrantReadWriteLock();

    /** User-registered project keys (also merged into {@link #findDistinctProjectKeys()}). */
    private final NavigableSet<String> registeredProjectKeys = new ConcurrentSkipListSet<>();

    private static final int MAX_PROJECT_KEY_LEN = 120;

    private final ObjectMapper objectMapper;
    private volatile ExecutorService writeExecutor;

    // ── ID Counters ───────────────────────────────────────────────────────────
    private final ConcurrentMap<String, Long> counters = new ConcurrentHashMap<>();

    private JtmStore() {
        this.objectMapper = buildObjectMapper();
        this.writeExecutor = newWriteExecutor();
        loadAll();
    }

    private static ExecutorService newWriteExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jtm-store-writer");
            t.setDaemon(true);
            return t;
        });
    }

    private ExecutorService ensureWriteExecutor() {
        ExecutorService exec = writeExecutor;
        if (exec != null && !exec.isShutdown() && !exec.isTerminated()) {
            return exec;
        }
        synchronized (this) {
            exec = writeExecutor;
            if (exec == null || exec.isShutdown() || exec.isTerminated()) {
                writeExecutor = newWriteExecutor();
            }
            return writeExecutor;
        }
    }

    private ObjectMapper buildObjectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);
    }

    // ── Directory Layout ──────────────────────────────────────────────────────

    private File getJtmDir() {
        return new File(Jenkins.get().getRootDir(), "jtm");
    }

    private File getTestCasesDir() { return new File(getJtmDir(), "testcases"); }
    private File getSuitesDir() { return new File(getJtmDir(), "suites"); }
    private File getRunsDir() { return new File(getJtmDir(), "runs"); }
    private File getAuditDir() { return new File(getJtmDir(), "audit"); }

    // ── Initialization ────────────────────────────────────────────────────────

    private void loadAll() {
        loadRegisteredProjectKeys();
        loadDirectory(getTestCasesDir(), TestCase.class, testCaseIndex);
        loadDirectory(getSuitesDir(), TestSuite.class, suiteIndex);
        loadDirectory(getRunsDir(), TestRun.class, runIndex);
        LOG.info(String.format("[JTM] Loaded %d test cases, %d suites, %d runs",
            testCaseIndex.size(), suiteIndex.size(), runIndex.size()));
    }

    private File getProjectRegistryFile() {
        return new File(getJtmDir(), "project-registry.json");
    }

    private void loadRegisteredProjectKeys() {
        File f = getProjectRegistryFile();
        if (!f.exists()) {
            return;
        }
        try {
            List<String> list = objectMapper.readValue(f, new TypeReference<List<String>>() {});
            if (list == null) {
                return;
            }
            registeredProjectKeys.clear();
            for (String s : list) {
                if (s == null) {
                    continue;
                }
                String t = StringUtils.trimToEmpty(s);
                if (t.isEmpty() || t.length() > MAX_PROJECT_KEY_LEN) {
                    continue;
                }
                registeredProjectKeys.add(t);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[JTM] Failed to load project registry", e);
        }
    }

    private void saveRegisteredProjectKeys() {
        File f = getProjectRegistryFile();
        f.getParentFile().mkdirs();
        try {
            objectMapper.writeValue(f, new ArrayList<>(registeredProjectKeys));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[JTM] Failed to persist project registry", e);
        }
    }

    /**
     * Registers a project key for use in dropdowns before any case/run uses it.
     * Idempotent; trims and enforces a max length.
     */
    public void registerProjectKey(String raw) {
        if (raw == null) {
            return;
        }
        String key = StringUtils.trimToEmpty(raw);
        if (key.isEmpty()) {
            return;
        }
        if (key.length() > MAX_PROJECT_KEY_LEN) {
            key = key.substring(0, MAX_PROJECT_KEY_LEN).trim();
            if (key.isEmpty()) {
                return;
            }
        }
        if (key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0) {
            return;
        }
        if (registeredProjectKeys.add(key)) {
            saveRegisteredProjectKeys();
        }
    }

    /** Removes a project key from registry (does not mutate existing test cases/runs). */
    public boolean unregisterProjectKey(String raw) {
        String key = StringUtils.trimToNull(raw);
        if (key == null) {
            return false;
        }
        boolean removed = registeredProjectKeys.remove(key);
        if (removed) {
            saveRegisteredProjectKeys();
        }
        return removed;
    }

    /** Number of test cases currently assigned to this project key. */
    public long countTestCasesForProject(String projectKey) {
        String key = StringUtils.trimToNull(projectKey);
        if (key == null) {
            return 0L;
        }
        testCaseLock.readLock().lock();
        try {
            return testCaseIndex.values().stream()
                .filter(tc -> key.equals(StringUtils.trimToEmpty(tc.getProjectKey())))
                .count();
        } finally {
            testCaseLock.readLock().unlock();
        }
    }

    /** Number of test runs currently assigned to this project key. */
    public long countRunsForProject(String projectKey) {
        String key = StringUtils.trimToNull(projectKey);
        if (key == null) {
            return 0L;
        }
        runLock.readLock().lock();
        try {
            return runIndex.values().stream()
                .filter(r -> key.equals(StringUtils.trimToEmpty(r.getProjectKey())))
                .count();
        } finally {
            runLock.readLock().unlock();
        }
    }

    private <T> void loadDirectory(File dir, Class<T> type, Map<String, T> index) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return;

        for (File file : files) {
            try {
                T entity = objectMapper.readValue(file, type);
                String id = getIdFromEntity(entity);
                if (id != null) {
                    index.put(id, entity);
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "[JTM] Failed to load " + file.getName(), e);
            }
        }
    }

    private String getIdFromEntity(Object entity) {
        if (entity instanceof TestCase) return ((TestCase) entity).getId();
        if (entity instanceof TestSuite) return ((TestSuite) entity).getId();
        if (entity instanceof TestRun) return ((TestRun) entity).getId();
        return null;
    }

    // ── ID Generation ──────────────────────────────────────────────────────────

    public synchronized String generateTestCaseId() {
        long next = counters.merge("TC", 1L, Long::sum);
        // Find the actual max to avoid collisions after restart
        long max = testCaseIndex.keySet().stream()
            .filter(k -> k.startsWith("TC-"))
            .mapToLong(k -> {
                try { return Long.parseLong(k.substring(3)); }
                catch (NumberFormatException e) { return 0L; }
            })
            .max().orElse(0L);
        next = Math.max(next, max + 1);
        counters.put("TC", next);
        return String.format("TC-%04d", next);
    }

    public synchronized String generateSuiteId() {
        long next = counters.merge("SUITE", 1L, Long::sum);
        long max = suiteIndex.keySet().stream()
            .filter(k -> k.startsWith("SUITE-"))
            .mapToLong(k -> {
                try {
                    return Long.parseLong(k.substring("SUITE-".length()));
                } catch (NumberFormatException e) {
                    return 0L;
                }
            })
            .max().orElse(0L);
        next = Math.max(next, max + 1);
        counters.put("SUITE", next);
        return String.format("SUITE-%04d", next);
    }

    public synchronized String generateRunId() {
        long next = counters.merge("RUN", 1L, Long::sum);
        long max = runIndex.keySet().stream()
            .filter(k -> k.startsWith("RUN-"))
            .mapToLong(k -> {
                try {
                    return Long.parseLong(k.substring("RUN-".length()));
                } catch (NumberFormatException e) {
                    return 0L;
                }
            })
            .max().orElse(0L);
        next = Math.max(next, max + 1);
        counters.put("RUN", next);
        return String.format("RUN-%04d", next);
    }

    // ── TestCase CRUD ─────────────────────────────────────────────────────────

    public Optional<TestCase> findTestCaseById(String id) {
        testCaseLock.readLock().lock();
        try {
            return Optional.ofNullable(testCaseIndex.get(id));
        } finally {
            testCaseLock.readLock().unlock();
        }
    }

    public List<TestCase> findAllTestCases() {
        return findAllTestCases(null);
    }

    /** @param projectKeyFilter optional; blank = all projects */
    public List<TestCase> findAllTestCases(String projectKeyFilter) {
        testCaseLock.readLock().lock();
        try {
            return testCaseIndex.values().stream()
                .filter(tc -> matchesProject(tc, projectKeyFilter))
                .sorted(Comparator.comparing(TestCase::getId))
                .collect(Collectors.collectingAndThen(
                    Collectors.toCollection(ArrayList::new), Collections::unmodifiableList));
        } finally {
            testCaseLock.readLock().unlock();
        }
    }

    public List<String> findDistinctProjectKeys() {
        testCaseLock.readLock().lock();
        runLock.readLock().lock();
        try {
            TreeSet<String> keys = new TreeSet<>();
            keys.addAll(registeredProjectKeys);
            for (TestCase tc : testCaseIndex.values()) {
                String k = StringUtils.trimToNull(tc.getProjectKey());
                if (k != null) {
                    keys.add(k);
                }
            }
            for (TestRun r : runIndex.values()) {
                String k = StringUtils.trimToNull(r.getProjectKey());
                if (k != null) {
                    keys.add(k);
                }
            }
            return new ArrayList<>(keys);
        } finally {
            runLock.readLock().unlock();
            testCaseLock.readLock().unlock();
        }
    }

    /** Distinct keys plus {@code preferredKey} if non-blank (for form dropdowns). */
    public List<String> findDistinctProjectKeysIncluding(String preferredKey) {
        TreeSet<String> keys = new TreeSet<>(findDistinctProjectKeys());
        String p = StringUtils.trimToNull(preferredKey);
        if (p != null) {
            keys.add(p);
        }
        return new ArrayList<>(keys);
    }

    private static boolean matchesProject(TestCase tc, String projectFilter) {
        if (projectFilter == null || projectFilter.isBlank()) {
            return true;
        }
        return projectFilter.trim().equals(StringUtils.defaultString(tc.getProjectKey()).trim());
    }

    private static boolean matchesProject(TestRun r, String projectFilter) {
        if (projectFilter == null || projectFilter.isBlank()) {
            return true;
        }
        return projectFilter.trim().equals(StringUtils.defaultString(r.getProjectKey()).trim());
    }

    public List<TestCase> findTestCasesPaginated(int page, int pageSize,
                                                   String statusFilter, String typeFilter,
                                                   String suiteId, String query) {
        testCaseLock.readLock().lock();
        try {
            return testCaseIndex.values().stream()
                .filter(tc -> matchesTestCaseFilters(tc, statusFilter, typeFilter, suiteId, query))
                .sorted(Comparator.comparing(TestCase::getId))
                .skip((long) page * pageSize)
                .limit(pageSize)
                .collect(Collectors.toList());
        } finally {
            testCaseLock.readLock().unlock();
        }
    }

    public long countTestCases(String statusFilter, String typeFilter, String suiteId, String query) {
        testCaseLock.readLock().lock();
        try {
            return testCaseIndex.values().stream()
                .filter(tc -> matchesTestCaseFilters(tc, statusFilter, typeFilter, suiteId, query))
                .count();
        } finally {
            testCaseLock.readLock().unlock();
        }
    }

    private static boolean matchesTestCaseFilters(TestCase tc, String statusFilter, String typeFilter,
                                                  String suiteId, String query) {
        if (statusFilter != null && !statusFilter.isEmpty()
            && !tc.getLastStatus().name().equalsIgnoreCase(statusFilter)) {
            return false;
        }
        if (typeFilter != null && !typeFilter.isEmpty()
            && !tc.getType().name().equalsIgnoreCase(typeFilter)) {
            return false;
        }
        if (suiteId != null && !suiteId.isEmpty()) {
            if (!Objects.equals(tc.getParentSuiteId(), suiteId)) {
                return false;
            }
        }
        if (query == null || query.isEmpty()) {
            return true;
        }
        String q = query.toLowerCase(Locale.ROOT);
        if (tc.getTitle().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (tc.getId().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        return tc.getTags() != null && tc.getTags().stream()
            .anyMatch(t -> t.toLowerCase(Locale.ROOT).contains(q));
    }

    public TestCase saveTestCase(TestCase tc) {
        testCaseLock.writeLock().lock();
        try {
            testCaseIndex.put(tc.getId(), tc);
        } finally {
            testCaseLock.writeLock().unlock();
        }
        writeAsync(getTestCasesDir(), tc.getId() + ".json", tc);
        return tc;
    }

    public boolean deleteTestCase(String id) {
        testCaseLock.writeLock().lock();
        try {
            TestCase removed = testCaseIndex.remove(id);
            if (removed != null) {
                deleteFile(getTestCasesDir(), id + ".json");
                return true;
            }
            return false;
        } finally {
            testCaseLock.writeLock().unlock();
        }
    }

    // ── TestSuite CRUD ────────────────────────────────────────────────────────

    public Optional<TestSuite> findSuiteById(String id) {
        suiteLock.readLock().lock();
        try {
            return Optional.ofNullable(suiteIndex.get(id));
        } finally {
            suiteLock.readLock().unlock();
        }
    }

    public List<TestSuite> findAllSuites() {
        suiteLock.readLock().lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(suiteIndex.values()));
        } finally {
            suiteLock.readLock().unlock();
        }
    }

    public List<TestSuite> findRootSuites() {
        suiteLock.readLock().lock();
        try {
            return suiteIndex.values().stream()
                .filter(TestSuite::isRoot)
                .sorted(Comparator.comparingInt(TestSuite::getSortOrder))
                .collect(Collectors.toList());
        } finally {
            suiteLock.readLock().unlock();
        }
    }

    public TestSuite saveSuite(TestSuite suite) {
        suiteLock.writeLock().lock();
        try {
            suiteIndex.put(suite.getId(), suite);
        } finally {
            suiteLock.writeLock().unlock();
        }
        writeAsync(getSuitesDir(), suite.getId() + ".json", suite);
        return suite;
    }

    public boolean deleteSuite(String id) {
        suiteLock.writeLock().lock();
        try {
            TestSuite removed = suiteIndex.remove(id);
            if (removed != null) {
                deleteFile(getSuitesDir(), id + ".json");
                return true;
            }
            return false;
        } finally {
            suiteLock.writeLock().unlock();
        }
    }

    // ── TestRun CRUD ──────────────────────────────────────────────────────────

    public Optional<TestRun> findRunById(String id) {
        runLock.readLock().lock();
        try {
            return Optional.ofNullable(runIndex.get(id));
        } finally {
            runLock.readLock().unlock();
        }
    }

    public List<TestRun> findRunsPaginated(int page, int pageSize, String jobFilter) {
        runLock.readLock().lock();
        try {
            return runIndex.values().stream()
                .filter(r -> jobFilter == null || jobFilter.isEmpty()
                    || r.getJobName().equalsIgnoreCase(jobFilter))
                .sorted(Comparator.comparing(
                    TestRun::getStartedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .skip((long) page * pageSize)
                .limit(pageSize)
                .collect(Collectors.toList());
        } finally {
            runLock.readLock().unlock();
        }
    }

    public List<TestRun> findRecentRuns(int limit) {
        return findRecentRuns(limit, null);
    }

    /** @param projectKeyFilter optional; blank = all projects */
    public List<TestRun> findRecentRuns(int limit, String projectKeyFilter) {
        runLock.readLock().lock();
        try {
            return runIndex.values().stream()
                .filter(r -> matchesProject(r, projectKeyFilter))
                .sorted(Comparator.comparing(
                    TestRun::getStartedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .limit(limit)
                .collect(Collectors.toList());
        } finally {
            runLock.readLock().unlock();
        }
    }

    /**
     * Sums {@link TestCaseResult#getDurationMs()} in {@code run} for results whose {@link TestCase}
     * is typed {@link TestCase.TestCaseType#AUTOMATED} only (manual/other types are ignored).
     * Dashboard chart is labelled accordingly — not total run or job wall time.
     */
    public long sumAutomatedDurationMsForRun(TestRun run) {
        if (run == null) {
            return 0L;
        }
        long sum = 0L;
        testCaseLock.readLock().lock();
        try {
            for (TestCaseResult r : run.getResults()) {
                if (r == null) continue;
                TestCase tc = testCaseIndex.get(r.getTestCaseId());
                if (tc == null) continue;
                if (tc.getType() != TestCase.TestCaseType.AUTOMATED) continue;
                sum += Math.max(0L, r.getDurationMs());
            }
            return sum;
        } finally {
            testCaseLock.readLock().unlock();
        }
    }

    public TestRun saveRun(TestRun run) {
        runLock.writeLock().lock();
        try {
            runIndex.put(run.getId(), run);
        } finally {
            runLock.writeLock().unlock();
        }
        // Runs must persist before the next HTTP read; async writes can race reloads in CI.
        writeSync(getRunsDir(), run.getId() + ".json", run);
        return run;
    }

    /** Deletes a test run from the index and removes its persisted JSON file. */
    public boolean deleteRun(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        runLock.writeLock().lock();
        try {
            TestRun removed = runIndex.remove(id.trim());
            if (removed != null) {
                deleteFile(getRunsDir(), id.trim() + ".json");
                return true;
            }
            return false;
        } finally {
            runLock.writeLock().unlock();
        }
    }

    // ── Audit Log ─────────────────────────────────────────────────────────────

    public void appendAuditEntry(AuditEntry entry) {
        Runnable task = () -> {
            try {
                String month = entry.getTimestamp().toString().substring(0, 7).replace("-", "-");
                File auditDir = new File(getAuditDir(), month);
                String day = entry.getTimestamp().toString().substring(0, 10);
                File auditFile = new File(auditDir, "audit-" + day + ".jsonl");
                auditDir.mkdirs();

                String line = objectMapper.writeValueAsString(entry) + "\n";
                Files.write(auditFile.toPath(), line.getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "[JTM] Failed to write audit entry", e);
            }
        };
        try {
            ensureWriteExecutor().submit(task);
        } catch (RejectedExecutionException ex) {
            // Can happen in tests when a prior Jenkins instance already shut this executor down.
            LOG.log(Level.FINE, "[JTM] Audit writer executor was shut down, recreating", ex);
            ensureWriteExecutor().submit(task);
        }
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    public Map<String, Long> getStatusCounts() {
        return getStatusCounts(null);
    }

    /** @param projectKeyFilter optional; blank = all projects */
    public Map<String, Long> getStatusCounts(String projectKeyFilter) {
        testCaseLock.readLock().lock();
        runLock.readLock().lock();
        try {
            // Status counts must only consider test cases that are linked to at least one run
            // AND have an execution timestamp (status "implemented" in a run).
            Set<String> linkedIds = findLinkedTestCaseIdsInternal(projectKeyFilter);
            java.util.function.Predicate<TestCase> visible =
                tc -> matchesProject(tc, projectKeyFilter)
                    && linkedIds.contains(tc.getId())
                    && tc.getLastRunAt() != null;

            Map<String, Long> counts = new LinkedHashMap<>();
            counts.put("PASSED", testCaseIndex.values().stream()
                .filter(visible)
                .filter(tc -> tc.getLastStatus() == TestCase.TestCaseStatus.PASSED)
                .count());
            counts.put("FAILED", testCaseIndex.values().stream()
                .filter(visible)
                .filter(tc -> tc.getLastStatus() == TestCase.TestCaseStatus.FAILED)
                .count());
            counts.put("BLOCKED", testCaseIndex.values().stream()
                .filter(visible)
                .filter(tc -> tc.getLastStatus() == TestCase.TestCaseStatus.BLOCKED)
                .count());
            counts.put("SKIPPED", testCaseIndex.values().stream()
                .filter(visible)
                .filter(tc -> tc.getLastStatus() == TestCase.TestCaseStatus.SKIPPED)
                .count());
            counts.put("FALSE_POSITIVE", testCaseIndex.values().stream()
                .filter(visible)
                .filter(tc -> tc.getLastStatus() == TestCase.TestCaseStatus.FALSE_POSITIVE)
                .count());
            counts.put("PENDING", testCaseIndex.values().stream()
                .filter(visible)
                .filter(tc -> tc.getLastStatus() == TestCase.TestCaseStatus.PENDING)
                .count());

            long total = testCaseIndex.values().stream()
                .filter(visible)
                .count();
            counts.put("TOTAL", total);
            return counts;
        } finally {
            runLock.readLock().unlock();
            testCaseLock.readLock().unlock();
        }
    }

    public double getOverallPassRate() {
        return getOverallPassRate(null);
    }

    public double getOverallPassRate(String projectKeyFilter) {
        testCaseLock.readLock().lock();
        runLock.readLock().lock();
        try {
            Set<String> linkedIds = findLinkedTestCaseIdsInternal(projectKeyFilter);
            java.util.function.Predicate<TestCase> visible =
                tc -> matchesProject(tc, projectKeyFilter)
                    && linkedIds.contains(tc.getId())
                    && tc.getLastRunAt() != null;

            long total = testCaseIndex.values().stream()
                .filter(visible)
                .count();
            if (total == 0) return 0.0;
            long ok = testCaseIndex.values().stream()
                .filter(visible)
                .filter(tc -> tc.getLastStatus() == TestCase.TestCaseStatus.PASSED
                    || tc.getLastStatus() == TestCase.TestCaseStatus.FALSE_POSITIVE)
                .count();
            return (double) ok / total * 100.0;
        } finally {
            runLock.readLock().unlock();
            testCaseLock.readLock().unlock();
        }
    }

    /**
     * Groups runs by Jenkins job (or by run id when {@link TestRun#getJobName()} is blank)
     * and aggregates status counts from each job's most recent run only.
     *
     * <p>When a run has {@link TestRun#getLinkedTestCaseIds() linked test cases}, counts follow
     * the same scope as {@link TestRun#getPassRate()}: one slot per linked id, using
     * {@link TestRun#getResultFor(String)} (missing entry → {@link TestCaseResult.TestResultStatus#PENDING}).
     * Otherwise falls back to iterating {@link TestRun#getResults()} (pipeline-only / legacy runs).
     */
    public Map<String, Long> getLatestRunStatusCounts(String projectKeyFilter) {
        runLock.readLock().lock();
        try {
            Comparator<TestRun> byRecency = Comparator
                .comparing(TestRun::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(TestRun::getBuildNumber)
                .thenComparing(TestRun::getId);
            Map<String, TestRun> best = new HashMap<>();
            for (TestRun r : runIndex.values()) {
                if (!matchesProject(r, projectKeyFilter)) {
                    continue;
                }
                String key = jobKey(r);
                TestRun existing = best.get(key);
                if (existing == null || byRecency.compare(r, existing) > 0) {
                    best.put(key, r);
                }
            }
            long passed = 0L;
            long failed = 0L;
            long blocked = 0L;
            long pending = 0L;
            long skipped = 0L;
            long falsePositive = 0L;
            for (TestRun r : best.values()) {
                List<String> linked = r.getLinkedTestCaseIds();
                if (linked != null && !linked.isEmpty()) {
                    LinkedHashSet<String> ids = new LinkedHashSet<>(linked);
                    for (String caseId : ids) {
                        TestCaseResult.TestResultStatus s = r.getResultFor(caseId)
                            .map(TestCaseResult::getStatus)
                            .orElse(TestCaseResult.TestResultStatus.PENDING);
                        if (s == null) {
                            s = TestCaseResult.TestResultStatus.PENDING;
                        }
                        switch (s) {
                            case PASSED: passed++; break;
                            case FAILED: failed++; break;
                            case BLOCKED: blocked++; break;
                            case PENDING: pending++; break;
                            case SKIPPED: skipped++; break;
                            case FALSE_POSITIVE: falsePositive++; break;
                            default: break;
                        }
                    }
                } else {
                    for (TestCaseResult res : r.getResults()) {
                        if (res.getStatus() == null) {
                            continue;
                        }
                        switch (res.getStatus()) {
                            case PASSED: passed++; break;
                            case FAILED: failed++; break;
                            case BLOCKED: blocked++; break;
                            case PENDING: pending++; break;
                            case SKIPPED: skipped++; break;
                            case FALSE_POSITIVE: falsePositive++; break;
                            default: break;
                        }
                    }
                }
            }
            Map<String, Long> counts = new LinkedHashMap<>();
            counts.put("PASSED", passed);
            counts.put("FAILED", failed);
            counts.put("BLOCKED", blocked);
            counts.put("FALSE_POSITIVE", falsePositive);
            counts.put("PENDING", pending);
            counts.put("SKIPPED", skipped);
            long total = passed + failed + blocked + falsePositive + pending + skipped;
            counts.put("TOTAL", total);
            return counts;
        } finally {
            runLock.readLock().unlock();
        }
    }

    /**
     * Pass rate (0–100) from {@link #getLatestRunStatusCounts(String)} — latest run per job only.
     */
    public double getLatestRunsPassRate(String projectKeyFilter) {
        Map<String, Long> c = getLatestRunStatusCounts(projectKeyFilter);
        long total = c.getOrDefault("TOTAL", 0L);
        if (total == 0) {
            return 0.0;
        }
        long ok = c.getOrDefault("PASSED", 0L) + c.getOrDefault("FALSE_POSITIVE", 0L);
        return (double) ok / total * 100.0;
    }

    /**
     * Runs with a start time, oldest→newest; if there are more than {@code maxPoints},
     * keeps the {@code maxPoints} most recent.
     */
    public List<TestRun> findFailureTrendRuns(String projectKeyFilter, int maxPoints) {
        runLock.readLock().lock();
        try {
            List<TestRun> list = runIndex.values().stream()
                .filter(r -> matchesProject(r, projectKeyFilter))
                .filter(r -> r.getStartedAt() != null)
                .sorted(Comparator.comparing(TestRun::getStartedAt))
                .collect(Collectors.toList());
            if (list.size() > maxPoints) {
                return new ArrayList<>(list.subList(list.size() - maxPoints, list.size()));
            }
            return new ArrayList<>(list);
        } finally {
            runLock.readLock().unlock();
        }
    }

    private static String jobKey(TestRun r) {
        String j = r.getJobName();
        if (j != null && !j.isBlank()) {
            return j.trim();
        }
        return "__run__:" + r.getId();
    }

    /**
     * @implNote Must be called with {@link #runLock} already held (read lock).
     */
    private Set<String> findLinkedTestCaseIdsInternal(String projectKeyFilter) {
        Set<String> out = new java.util.HashSet<>();
        for (TestRun r : runIndex.values()) {
            if (!matchesProject(r, projectKeyFilter)) {
                continue;
            }
            List<String> ids = r.getLinkedTestCaseIds();
            if (ids == null || ids.isEmpty()) {
                continue;
            }
            out.addAll(ids);
        }
        return out;
    }

    /**
     * Finds all test case ids currently linked to at least one {@link TestRun}.
     *
     * <p>Intended for UI visibility decisions ("status only exists once a test case was linked
     * and a run set its result").</p>
     */
    public Set<String> findLinkedTestCaseIds(String projectKeyFilter) {
        runLock.readLock().lock();
        try {
            return java.util.Collections.unmodifiableSet(findLinkedTestCaseIdsInternal(projectKeyFilter));
        } finally {
            runLock.readLock().unlock();
        }
    }

    public List<TestCase> getFlakyTests(int limit) {
        return getFlakyTests(limit, null);
    }

    public List<TestCase> getFlakyTests(int limit, String projectKeyFilter) {
        testCaseLock.readLock().lock();
        try {
            return testCaseIndex.values().stream()
                .filter(tc -> matchesProject(tc, projectKeyFilter))
                .filter(TestCase::isFlaky)
                .sorted(Comparator.comparingInt(TestCase::getFlakyScore).reversed())
                .limit(limit)
                .collect(Collectors.toList());
        } finally {
            testCaseLock.readLock().unlock();
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private void writeSync(File dir, String filename, Object data) {
        try {
            dir.mkdirs();
            File target = new File(dir, filename);
            File temp = new File(dir, filename + ".tmp");
            objectMapper.writeValue(temp, data);
            Files.move(temp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "[JTM] Failed to persist " + filename, e);
        }
    }

    private void writeAsync(File dir, String filename, Object data) {
        Runnable task = () -> writeSync(dir, filename, data);
        try {
            ensureWriteExecutor().submit(task);
        } catch (RejectedExecutionException ex) {
            LOG.log(Level.FINE, "[JTM] Async writer rejected task, recreating", ex);
            ensureWriteExecutor().submit(task);
        }
    }

    private void deleteFile(File dir, String filename) {
        Runnable task = () -> {
            File f = new File(dir, filename);
            if (f.exists() && !f.delete()) {
                LOG.warning("[JTM] Failed to delete " + f.getAbsolutePath());
            }
        };
        try {
            ensureWriteExecutor().submit(task);
        } catch (RejectedExecutionException ex) {
            LOG.log(Level.FINE, "[JTM] Async delete rejected task, recreating", ex);
            ensureWriteExecutor().submit(task);
        }
    }

    /**
     * Clears all in-memory indexes and deletes persisted JSON under {@code jtm/} (test cases, suites, runs).
     * Audit files are not removed. Requires external synchronization if writes are pending.
     */
    public void resetAllData() {
        testCaseLock.writeLock().lock();
        try {
            testCaseIndex.clear();
            deleteAllJsonFilesSync(getTestCasesDir());
        } finally {
            testCaseLock.writeLock().unlock();
        }
        suiteLock.writeLock().lock();
        try {
            suiteIndex.clear();
            deleteAllJsonFilesSync(getSuitesDir());
        } finally {
            suiteLock.writeLock().unlock();
        }
        runLock.writeLock().lock();
        try {
            runIndex.clear();
            deleteAllJsonFilesSync(getRunsDir());
        } finally {
            runLock.writeLock().unlock();
        }
        registeredProjectKeys.clear();
        File reg = getProjectRegistryFile();
        if (reg.exists() && !reg.delete()) {
            LOG.log(Level.WARNING, "[JTM] Could not delete project registry file");
        }
        counters.clear();
        LOG.warning("[JTM] All test management data was reset (empty store).");
    }

    private void deleteAllJsonFilesSync(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (!f.delete()) {
                LOG.warning("[JTM] Could not delete " + f.getAbsolutePath());
            }
        }
    }

    /**
     * Graceful shutdown — flush pending writes.
     */
    public void shutdown() {
        ExecutorService exec = writeExecutor;
        if (exec == null) {
            return;
        }
        exec.shutdown();
        try {
            if (!exec.awaitTermination(10, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
