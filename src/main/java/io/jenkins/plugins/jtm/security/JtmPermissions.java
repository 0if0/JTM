package io.jenkins.plugins.jtm.security;

import hudson.model.Hudson;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import io.jenkins.plugins.jtm.core.domain.TestCase;
import jenkins.model.Jenkins;
import org.jvnet.localizer.Localizable;

/**
 * JTM-specific Jenkins permissions.
 *
 * <p>Hierarchy (each permission implies those listed after the arrow):
 * {@link #TEST_ADMIN} → {@link #TEST_EDIT} → {@link #TEST_EXECUTE} → {@link #TEST_VIEW} → {@link Hudson#READ}.
 * {@code TEST_ADMIN} is <em>not</em> implied by {@code TEST_EXECUTE}; it extends {@code TEST_EDIT} only.
 */
public final class JtmPermissions {

    public static final PermissionGroup GROUP = new PermissionGroup(
        JtmPermissions.class,
        new NonLocalizableString("Test Management (JTM)")
    );

    public static final Permission TEST_VIEW = new Permission(
        GROUP, "View",
        new NonLocalizableString("View test cases, runs, and dashboard"),
        Hudson.READ,
        PermissionScope.JENKINS
    );

    public static final Permission TEST_EXECUTE = new Permission(
        GROUP, "Execute",
        new NonLocalizableString("Execute tests and update test case status"),
        TEST_VIEW,
        PermissionScope.JENKINS
    );

    public static final Permission TEST_EDIT = new Permission(
        GROUP, "Edit",
        new NonLocalizableString("Create, edit, and delete test cases and suites"),
        TEST_EXECUTE,
        PermissionScope.JENKINS
    );

    public static final Permission TEST_ADMIN = new Permission(
        GROUP, "Admin",
        new NonLocalizableString("Full administrative access to JTM plugin (e.g. export branding)"),
        TEST_EDIT,
        PermissionScope.JENKINS
    );

    private JtmPermissions() {}

    public static void checkPermission(Permission p) {
        Jenkins.get().checkPermission(p);
    }

    public static boolean hasPermission(Permission p) {
        return Jenkins.get().hasPermission(p);
    }

    public static boolean canEditTestCase(TestCase tc) {
        return Jenkins.get().hasPermission(TEST_EDIT);
    }

    public static boolean canDeleteTestCase(TestCase tc) {
        return Jenkins.get().hasPermission(TEST_EDIT);
    }

    public static void checkEditTestCase(TestCase tc) {
        Jenkins.get().checkPermission(TEST_EDIT);
    }

    public static void checkDeleteTestCase(TestCase tc) {
        Jenkins.get().checkPermission(TEST_EDIT);
    }

    private static final class NonLocalizableString extends Localizable {
        private final String text;
        NonLocalizableString(String text) {
            super(null, null);
            this.text = text;
        }
        @Override public String toString() { return text; }
        @Override public String toString(java.util.Locale locale) { return text; }
    }
}
