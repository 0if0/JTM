package io.jenkins.plugins.jtm.postbuild;

import io.jenkins.plugins.jtm.core.domain.TestCaseResult;
import org.apache.commons.lang3.StringUtils;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses JUnit XML reports into {@link TestCaseResult} rows for JTM import.
 */
public final class JUnitXmlImportParser {

    private JUnitXmlImportParser() {}

    public static final class ImportedCase {
        private final String caseKey; // lgtm[java] not a password; stable mapping key (classname + name)
        private final String displayTitle; // "name" from junit testcase (keeps spaces/umlauts)
        private final String projectKey; // lgtm[java] not a credential; derived project scope key
        private final boolean explicitIdProvided;
        private final TestCaseResult result;

        private ImportedCase(String caseKey,
                              String displayTitle,
                              String projectKey,
                              boolean explicitIdProvided,
                              TestCaseResult result) {
            this.caseKey = caseKey;
            this.displayTitle = displayTitle;
            this.projectKey = projectKey;
            this.explicitIdProvided = explicitIdProvided;
            this.result = result;
        }

        public String getCaseKey() { return caseKey; }
        public String getDisplayTitle() { return displayTitle; }
        public String getProjectKey() { return projectKey; }
        public boolean isExplicitIdProvided() { return explicitIdProvided; }
        public TestCaseResult getResult() { return result; }
    }

    public static final class ParseResult {
        private final List<ImportedCase> cases;
        private final String projectKey; // lgtm[java] not a credential; fallback project scope key

        public ParseResult(List<ImportedCase> cases, String projectKey) {
            this.cases = cases;
            this.projectKey = projectKey;
        }

        public String getProjectKey() {
            return projectKey;
        }

        public List<ImportedCase> getCases() {
            return cases;
        }

        public List<TestCaseResult> getResults() {
            List<TestCaseResult> out = new ArrayList<>(cases.size());
            for (ImportedCase c : cases) {
                out.add(c.getResult());
            }
            return out;
        }

        public Set<String> getLinkedCaseIds() {
            Set<String> linked = new LinkedHashSet<>();
            for (ImportedCase c : cases) {
                linked.add(c.getResult().getTestCaseId());
            }
            return linked;
        }
    }

    public static ParseResult parse(InputStream in) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setExpandEntityReferences(false);
        var doc = f.newDocumentBuilder().parse(in);
        var nodes = doc.getElementsByTagName("testcase");
        List<ImportedCase> cases = new ArrayList<>();
        // Fallback project key if we cannot resolve a testsuite for a testcase.
        String fallbackProjectKey = "";
        var suites = doc.getElementsByTagName("testsuite");
        if (suites != null && suites.getLength() > 0) {
            var s0 = suites.item(0);
            if (s0 != null && s0.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                var se = (org.w3c.dom.Element) s0;
                fallbackProjectKey = StringUtils.trimToEmpty(se.getAttribute("name"));
            }
        }
        for (int i = 0; i < nodes.getLength(); i++) {
            var n = nodes.item(i);
            if (n.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) {
                continue;
            }
            var el = (org.w3c.dom.Element) n;
            String classname = StringUtils.defaultString(el.getAttribute("classname")).trim();
            String name = StringUtils.defaultString(el.getAttribute("name")).trim();
            String caseKey = (StringUtils.defaultString(classname) + "#" + name).trim();
            boolean explicitId = name.matches("^TC-[A-Za-z0-9._-]+$");
            String caseId = explicitId ? name : toCaseId(classname, name);
            String projectKey = resolveNearestTestsuiteProjectKey(el).orElse(fallbackProjectKey);
            TestCaseResult.TestResultStatus status = statusFromCaseElement(el);
            long durationMs = parseDurationMs(el.getAttribute("time"));
            TestCaseResult r = new TestCaseResult(caseId, status, durationMs);
            r.setExecutedBy("postbuild");
            if (status == TestCaseResult.TestResultStatus.FAILED) {
                r.setErrorMessage(firstChildText(el, "failure"));
            } else if (status == TestCaseResult.TestResultStatus.BLOCKED) {
                r.setErrorMessage(firstChildText(el, "error"));
            } else if (status == TestCaseResult.TestResultStatus.SKIPPED) {
                r.setComment("skipped");
            }
            cases.add(new ImportedCase(caseKey, name, projectKey, explicitId, r));
        }
        return new ParseResult(cases, fallbackProjectKey);
    }

    private static java.util.Optional<String> resolveNearestTestsuiteProjectKey(org.w3c.dom.Element testcaseEl) {
        org.w3c.dom.Node p = testcaseEl.getParentNode();
        while (p != null) {
            if (p.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                org.w3c.dom.Element e = (org.w3c.dom.Element) p;
                if ("testsuite".equalsIgnoreCase(e.getTagName())) {
                    return java.util.Optional.ofNullable(StringUtils.trimToNull(e.getAttribute("name")));
                }
            }
            p = p.getParentNode();
        }
        return java.util.Optional.empty();
    }

    /**
     * If the JUnit {@code name} looks like a JTM id ({@code TC-…}), use it so imports match existing cases.
     * Otherwise derive a stable {@code AUTO-…} id from classname + name.
     */
    public static String toCaseId(String classname, String name) {
        String n = StringUtils.trimToEmpty(name);
        if (n.matches("^TC-[A-Za-z0-9._-]+$")) {
            return n;
        }
        String key = (StringUtils.defaultString(classname) + "#" + n).trim();
        if (key.isEmpty()) {
            key = "unknown-testcase";
        }
        String base = key.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (base.length() > 80) {
            base = base.substring(0, 80);
        }
        return "AUTO-" + base;
    }

    private static TestCaseResult.TestResultStatus statusFromCaseElement(org.w3c.dom.Element el) {
        if (el.getElementsByTagName("failure").getLength() > 0) {
            return TestCaseResult.TestResultStatus.FAILED;
        }
        if (el.getElementsByTagName("error").getLength() > 0) {
            return TestCaseResult.TestResultStatus.BLOCKED;
        }
        if (el.getElementsByTagName("skipped").getLength() > 0) {
            return TestCaseResult.TestResultStatus.SKIPPED;
        }
        return TestCaseResult.TestResultStatus.PASSED;
    }

    private static long parseDurationMs(String seconds) {
        if (StringUtils.isBlank(seconds)) {
            return 0L;
        }
        try {
            return Math.max(0L, Math.round(Double.parseDouble(seconds) * 1000d));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String firstChildText(org.w3c.dom.Element el, String tag) {
        var list = el.getElementsByTagName(tag);
        if (list.getLength() == 0) {
            return null;
        }
        return StringUtils.trimToNull(list.item(0).getTextContent());
    }
}
