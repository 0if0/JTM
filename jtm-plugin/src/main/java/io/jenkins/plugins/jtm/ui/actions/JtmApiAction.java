package io.jenkins.plugins.jtm.ui.actions;

import hudson.model.Action;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.verb.GET;

import javax.servlet.ServletException;
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

    public Object getDynamic(String name, StaplerRequest req, StaplerResponse rsp) {
        switch (name) {
            case "testcases":
                return (HttpResponse) (r, p, n) -> dispatchTestcases(r, p);
            case "testruns":
                return (HttpResponse) (r, p, n) -> dispatchTestruns(r, p);
            case "dashboard":
                return new JtmApiDashboardAction(root);
            case "testcase":
                return new JtmApiTestcaseBranch(root);
            default:
                return null;
        }
    }

    private void dispatchTestcases(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException {
        String m = req.getMethod();
        if ("GET".equalsIgnoreCase(m)) {
            root.serveApiTestcases(req, rsp);
        } else if ("POST".equalsIgnoreCase(m)) {
            root.serveApiCreateTestcase(req, rsp);
        } else {
            root.serveApiMethodNotAllowed(req, rsp, m);
        }
    }

    private void dispatchTestruns(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException {
        if (!"POST".equalsIgnoreCase(req.getMethod())) {
            root.serveApiMethodNotAllowed(req, rsp, req.getMethod());
            return;
        }
        root.serveApiTestruns(req, rsp);
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
        public void doIndex(StaplerRequest req, StaplerResponse rsp) throws IOException {
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

        public JtmApiTestcaseById getDynamic(String id, StaplerRequest req, StaplerResponse rsp) {
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

        public void doIndex(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
            String m = req.getMethod();
            if ("GET".equalsIgnoreCase(m)) {
                root.serveApiTestcaseGet(id, req, rsp);
            } else if ("POST".equalsIgnoreCase(m)) {
                root.serveApiTestcaseUpdate(id, req, rsp);
            } else if ("DELETE".equalsIgnoreCase(m)) {
                root.serveApiTestcaseDelete(id, req, rsp);
            } else {
                root.serveApiMethodNotAllowed(req, rsp, m);
            }
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

        public void doIndex(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
            if (!"POST".equalsIgnoreCase(req.getMethod())) {
                root.serveApiMethodNotAllowed(req, rsp, req.getMethod());
                return;
            }
            root.serveApiUpdateStatus(req, rsp);
        }
    }
}
