package io.jenkins.plugins.jtm.core.service;

import io.jenkins.plugins.jtm.core.domain.TestCase;
import io.jenkins.plugins.jtm.core.domain.TestSuite;
import io.jenkins.plugins.jtm.persistence.JtmStore;
import io.jenkins.plugins.jtm.security.AuditEntry;
import io.jenkins.plugins.jtm.security.JtmPermissions;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Service managing hierarchical TestSuite operations.
 *
 * @author JTM Development Team
 */
public final class TestSuiteService {

    private static final Logger LOG = Logger.getLogger(TestSuiteService.class.getName());
    private static final TestSuiteService INSTANCE = new TestSuiteService();
    public static TestSuiteService get() { return INSTANCE; }

    private final JtmStore store;

    private TestSuiteService() {
        this.store = JtmStore.get();
    }

    // ── Create ────────────────────────────────────────────────────────────────

    public TestSuite createSuite(String name, String parentId, String user) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EDIT);
        if (StringUtils.isBlank(name))
            throw new IllegalArgumentException("Suite name must not be blank");

        String id = store.generateSuiteId();
        TestSuite suite = new TestSuite(id, name.trim(), parentId);
        suite.setCreatedBy(user);
        suite.setCreatedAt(Instant.now());
        suite.setUpdatedAt(Instant.now());

        store.saveSuite(suite);

        // If parent specified, add this as child
        if (parentId != null) {
            store.findSuiteById(parentId).ifPresent(parent -> {
                parent.addChildSuite(id);
                store.saveSuite(parent);
            });
        }

        audit(AuditEntry.Action.TEST_SUITE_CREATED, id, user, "Created: " + name);
        LOG.info("[JTM] Created suite " + id + ": " + name);
        return suite;
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public Optional<TestSuite> findById(String id) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return store.findSuiteById(id);
    }

    public List<TestSuite> findRoots() {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return store.findRootSuites();
    }

    public List<TestSuite> findAll() {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return store.findAllSuites();
    }

    /**
     * Returns the full path of a suite (e.g. "Root > Auth > 2FA").
     */
    public String getSuitePath(String suiteId) {
        List<String> parts = new ArrayList<>();
        String current = suiteId;
        int depth = 0;
        while (current != null && depth++ < 20) {
            Optional<TestSuite> suite = store.findSuiteById(current);
            if (suite.isEmpty()) break;
            parts.add(0, suite.get().getName());
            current = suite.get().getParentId();
        }
        return String.join(" > ", parts);
    }

    /**
     * Returns all TestCase IDs in this suite and all child suites (recursive).
     */
    public List<String> getAllTestCaseIds(String suiteId) {
        List<String> ids = new ArrayList<>();
        collectTestCaseIds(suiteId, ids, 0);
        return ids;
    }

    private void collectTestCaseIds(String suiteId, List<String> ids, int depth) {
        if (depth > 20) return;
        store.findSuiteById(suiteId).ifPresent(suite -> {
            ids.addAll(suite.getTestCaseIds());
            for (String childId : suite.getChildSuiteIds()) {
                collectTestCaseIds(childId, ids, depth + 1);
            }
        });
    }

    // ── Update ────────────────────────────────────────────────────────────────

    public TestSuite updateSuite(String id, String newName, String user) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EDIT);
        TestSuite suite = store.findSuiteById(id)
            .orElseThrow(() -> new NoSuchElementException("Suite not found: " + id));
        if (StringUtils.isBlank(newName))
            throw new IllegalArgumentException("Suite name must not be blank");
        suite.setName(newName.trim());
        suite.setUpdatedAt(Instant.now());
        store.saveSuite(suite);
        return suite;
    }

    /**
     * Assigns a test case to a suite.
     */
    public void assignTestCase(String suiteId, String testCaseId, String user) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EDIT);
        TestSuite suite = store.findSuiteById(suiteId)
            .orElseThrow(() -> new NoSuchElementException("Suite not found: " + suiteId));
        suite.addTestCase(testCaseId);
        store.saveSuite(suite);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    public void deleteSuite(String id, String user) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_ADMIN);
        TestSuite suite = store.findSuiteById(id)
            .orElseThrow(() -> new NoSuchElementException("Suite not found: " + id));

        // Remove from parent's children list
        if (suite.getParentId() != null) {
            store.findSuiteById(suite.getParentId()).ifPresent(parent -> {
                List<String> children = new ArrayList<>(parent.getChildSuiteIds());
                children.remove(id);
                parent.setChildSuiteIds(children);
                store.saveSuite(parent);
            });
        }

        store.deleteSuite(id);
        audit(AuditEntry.Action.TEST_SUITE_DELETED, id, user, "Deleted: " + suite.getName());
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void audit(AuditEntry.Action action, String id, String user, String details) {
        store.appendAuditEntry(
            AuditEntry.builder(UUID.randomUUID().toString(), action)
                .entityType("TestSuite").entityId(id)
                .performedBy(user).details(details).build()
        );
    }
}
