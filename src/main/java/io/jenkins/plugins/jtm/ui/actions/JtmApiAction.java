package io.jenkins.plugins.jtm.ui.actions;

import hudson.model.Action;
import io.jenkins.plugins.jtm.security.JtmPermissions;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.GET;

import jakarta.servlet.ServletException;
import java.io.IOException;

/**
 * Stapler tree at {@code /jtm/api/...}.
 * <p>Methods like {@code doApiFoo} on {@link JtmRootAction} do <strong>not</strong> map to
 * {@code /jtm/api/foo}; they bind to unrelated paths (e.g. {@code apiFoo}). This object
 * provides the URLs used by the UI and documentation.
 */
public final class JtmApiAction implements Action {

    private final JtmRootAction root;

    JtmApiAction(JtmRootAction root) {
        this.root = root;
    }

    @Override
    public String getUrlName() {
        return "api";
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    public Object getDynamic(String name, StaplerRequest2 req, StaplerResponse2 rsp) {
        switch (name) {
            case "testcases":
                return new JtmApiTestcasesAction(root);
            case "testruns":
                return new JtmApiTestrunsAction(root);
            case "dashboard":
                return new JtmApiDashboardAction(root);
            case "testcase":
                return new JtmApiTestcaseBranch(root);
            default:
                return null;
        }
    }

    static final class JtmApiTestcasesAction implements Action {
        private final JtmRootAction root;

        JtmApiTestcasesAction(JtmRootAction root) {
            this.root = root;
        }

        @Override
        public String getUrlName() {
            return "testcases";
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getIconFileName() {
            return null;
        }

        @GET
        public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
            JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
            root.serveApiTestcases(req, rsp);
        }

        /**
         * URL: /jtm/api/testcases/create
         */
        @RequirePOST
        public void doCreate(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
            JtmPermissions.checkPermission(JtmPermissions.TEST_EDIT);
            root.serveApiCreateTestcase(req, rsp);
        }
    }

    static final class JtmApiTestrunsAction implements Action {
        private final JtmRootAction root;

        JtmApiTestrunsAction(JtmRootAction root) {
            this.root = root;
        }

        @Override
        public String getUrlName() {
            return "testruns";
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getIconFileName() {
            return null;
        }

        @GET
        public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
            JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
            root.serveApiTestruns(req, rsp);
        }
    }

    // ── /jtm/api/dashboard/summary ────────────────────────────────────────────

    static final class JtmApiDashboardAction implements Action {
        private final JtmRootAction root;

        JtmApiDashboardAction(JtmRootAction root) {
            this.root = root;
        }

        @Override
        public String getUrlName() {
            return "dashboard";
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getIconFileName() {
            return null;
        }

        public JtmApiDashboardSummary getSummary() {
            return new JtmApiDashboardSummary(root);
        }
    }

    static final class JtmApiDashboardSummary implements Action {
        private final JtmRootAction root;

        JtmApiDashboardSummary(JtmRootAction root) {
            this.root = root;
        }

        @Override
        public String getUrlName() {
            return "summary";
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getIconFileName() {
            return null;
        }

        @GET
        public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
            root.serveApiDashboardSummary(req, rsp);
        }
    }

    // ── /jtm/api/testcase/{id}[/status] ─────────────────────────────────────

    static final class JtmApiTestcaseBranch implements Action {
        private final JtmRootAction root;

        JtmApiTestcaseBranch(JtmRootAction root) {
            this.root = root;
        }

        @Override
        public String getUrlName() {
            return "testcase";
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getIconFileName() {
            return null;
        }

        public JtmApiTestcaseById getDynamic(String id, StaplerRequest2 req, StaplerResponse2 rsp) {
            return new JtmApiTestcaseById(root, id);
        }
    }

    static final class JtmApiTestcaseById implements Action {
        private final JtmRootAction root;
        private final String id;

        JtmApiTestcaseById(JtmRootAction root, String id) {
            this.root = root;
            this.id = id;
        }

        @Override
        public String getUrlName() {
            return id;
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getIconFileName() {
            return null;
        }

        @GET
        public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
            root.serveApiTestcaseGet(id, req, rsp);
        }

        /**
         * Mutation endpoint intentionally split from {@code doIndex} so Jenkins can enforce
         * CSRF protection through {@link RequirePOST}.
         * URL: /jtm/api/testcase/{id}/update
         */
        @RequirePOST
        public void doUpdate(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
            root.serveApiTestcaseUpdate(id, req, rsp);
        }

        /**
         * URL: /jtm/api/testcase/{id}/delete
         */
        @RequirePOST
        public void doDelete(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            root.serveApiTestcaseDelete(id, req, rsp);
        }

        public JtmApiTestcaseStatus getStatus() {
            return new JtmApiTestcaseStatus(root);
        }
    }

    static final class JtmApiTestcaseStatus implements Action {
        private final JtmRootAction root;

        JtmApiTestcaseStatus(JtmRootAction root) {
            this.root = root;
        }

        @Override
        public String getUrlName() {
            return "status";
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getIconFileName() {
            return null;
        }

        @RequirePOST
        public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
            root.serveApiUpdateStatus(req, rsp);
        }
    }
}
