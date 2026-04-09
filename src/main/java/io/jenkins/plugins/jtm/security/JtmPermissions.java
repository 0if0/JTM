package io.jenkins.plugins.jtm.security;

import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import io.jenkins.plugins.jtm.core.domain.TestCase;
import org.jvnet.localizer.Localizable;

/**
 * JTM-specific Jenkins permissions (declared for possible future use).
 * <p><strong>Current behaviour:</strong> JTM does not enforce these — all users may use all features.
 * Re-enable checks in {@link #checkPermission} / {@link #hasPermission} when you need authorization again.
 */
public final class JtmPermissions {

    public static final PermissionGroup GROUP = new PermissionGroup(
        JtmPermissions.class,
        new NonLocalizableString("Test Management (JTM)")
    );

    public static final Permission TEST_VIEW = new Permission(
        GROUP, "View",
        new NonLocalizableString("View test cases, runs, and dashboard"),
        hudson.model.Hudson.READ,
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
        new NonLocalizableString("Full administrative access to JTM plugin"),
        TEST_EDIT,
        PermissionScope.JENKINS
    );

    private JtmPermissions() {}

    /** No-op: open access. */
    public static void checkPermission(Permission p) {
        // intentionally empty
    }

    /** Always true: open access. */
    public static boolean hasPermission(Permission p) {
        return true;
    }

    /** Always true: open access. */
    public static boolean canEditTestCase(TestCase tc) {
        return true;
    }

    /** Always true: open access. */
    public static boolean canDeleteTestCase(TestCase tc) {
        return true;
    }

    /** No-op: open access. */
    public static void checkEditTestCase(TestCase tc) {
        // intentionally empty
    }

    /** No-op: open access. */
    public static void checkDeleteTestCase(TestCase tc) {
        // intentionally empty
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
