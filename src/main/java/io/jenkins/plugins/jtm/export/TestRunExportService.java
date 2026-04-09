package io.jenkins.plugins.jtm.export;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import io.jenkins.plugins.jtm.core.domain.TestCase;
import io.jenkins.plugins.jtm.core.domain.TestCaseResult;
import io.jenkins.plugins.jtm.core.domain.TestRun;
import io.jenkins.plugins.jtm.core.domain.TestStep;
import io.jenkins.plugins.jtm.core.service.TestCaseService;
import org.apache.commons.lang3.StringUtils;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Builds self-contained HTML and PDF exports for a single {@link TestRun} or a flat multi-run batch.
 */
public final class TestRunExportService {

    /** Safety cap for rows in a multi-run flat export (PDF/HTML size). */
    public static final int MAX_FLAT_EXPORT_ROWS = 4_000;

    private TestRunExportService() {}

    public static List<ExportRow> buildRows(TestRun run) {
        TestCaseService svc = TestCaseService.get();
        List<ExportRow> out = new ArrayList<>();
        for (String id : run.getLinkedTestCaseIds()) {
            Optional<TestCase> tc = svc.findById(id);
            String title = tc.map(TestCase::getTitle).orElse("(deleted test case)");
            TestCaseResult res = run.getResultFor(id).orElse(null);
            String status = res != null && res.getStatus() != null ? res.getStatus().name() : "—";
            String assignee = res != null && StringUtils.isNotBlank(res.getAssignedTo()) ? res.getAssignedTo() : "—";
            String stepsSummary = buildStepsSummary(tc.orElse(null), res);
            out.add(new ExportRow(id, title, status, assignee, stepsSummary));
        }
        return out;
    }

    private static String buildStepsSummary(TestCase tc, TestCaseResult res) {
        if (tc == null || tc.getSteps() == null || tc.getSteps().isEmpty()) {
            return "—";
        }
        List<TestStep> steps = tc.getSteps();
        List<TestStep.StepStatus> st = res != null ? res.getStepStatuses() : List.of();
        List<String> cm = res != null ? res.getStepComments() : List.of();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            String action = StringUtils.abbreviate(steps.get(i).getAction(), 120);
            String stat = i < st.size() && st.get(i) != null ? st.get(i).name() : "NOT_RUN";
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(i + 1).append(": ").append(stat);
            if (!action.isEmpty()) {
                sb.append(" (").append(action).append(')');
            }
            if (i < cm.size() && StringUtils.isNotBlank(cm.get(i))) {
                sb.append(" — ").append(StringUtils.abbreviate(cm.get(i).trim(), 160));
            }
        }
        return sb.toString();
    }

    /**
     * Flattens several runs into one row list: each linked test case appears once per run with run context.
     * Order follows {@code runs} iteration order.
     */
    public static List<FlatExportRow> buildFlatRows(List<TestRun> runs) {
        List<FlatExportRow> out = new ArrayList<>();
        if (runs == null) {
            return out;
        }
        for (TestRun run : runs) {
            for (ExportRow r : buildRows(run)) {
                out.add(
                    new FlatExportRow(
                        run.getId(),
                        run.getName(),
                        run.getJobName(),
                        run.getBuildNumber(),
                        StringUtils.defaultString(run.getProjectScope()),
                        r.getTestCaseId(),
                        r.getTitle(),
                        r.getStatus(),
                        r.getAssignee(),
                        r.getStepsSummary()
                    )
                );
            }
        }
        return out;
    }

    private static void appendSharedReportStyles(StringBuilder html) {
        html.append(":root{--ink:#0b1220;--ink-muted:#3d4f6f;--surface:#fff;--surface-alt:#f4f6f9;--border:#d8dee9;");
        html.append("--accent:#0d3b66;--accent-soft:#e8eef5;--rule:#c5d0e0;--shadow:0 2px 8px rgba(11,18,32,.06);");
        html.append("--ok-bg:#e6f4ea;--ok-fg:#1e5f2f;--fail-bg:#fdeaea;--fail-fg:#8f1d1d;--warn-bg:#fff4e5;--warn-fg:#9a4b00;");
        html.append("--neutral-bg:#eef2f7;--neutral-fg:#3d4f6f;--skip-bg:#eceef8;--skip-fg:#3730a3;}\n");
        html.append("*{box-sizing:border-box;} body{margin:0;color:var(--ink);background:var(--surface-alt);");
        html.append("font-family:system-ui,-apple-system,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;");
        html.append("font-size:15px;line-height:1.55;-webkit-font-smoothing:antialiased;}\n");
        html.append(".report{max-width:1120px;margin:0 auto;padding:0 24px 56px;}\n");
        html.append(".doc-bar{height:4px;background:linear-gradient(90deg,var(--accent) 0%,#1a5f8a 100%);");
        html.append("border-radius:0 0 3px 3px;margin:0 -24px 0;}\n");
        html.append(".doc-ribbon{font-size:11px;font-weight:600;letter-spacing:.12em;text-transform:uppercase;");
        html.append("color:var(--ink-muted);margin:20px 0 4px;}\n");
        html.append(".doc-header{display:grid;grid-template-columns:1fr auto;gap:28px;align-items:start;");
        html.append("padding:28px 0 32px;border-bottom:1px solid var(--rule);}\n");
        html.append("@media(max-width:800px){.doc-header{grid-template-columns:1fr;}}\n");
        html.append(".brand img{max-height:64px;max-width:260px;display:block;margin-bottom:16px;}\n");
        html.append("h1{font-size:1.75rem;font-weight:700;margin:0 0 6px;letter-spacing:-.03em;line-height:1.2;color:var(--ink);}\n");
        html.append(".subtitle{margin:0 0 20px;font-size:.95rem;color:var(--ink-muted);max-width:52ch;}\n");
        html.append(".meta-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px 24px;");
        html.append("font-size:.875rem;color:var(--ink-muted);}\n");
        html.append(".meta-grid dt{margin:0;font-weight:600;color:var(--ink);font-size:.72rem;letter-spacing:.04em;");
        html.append("text-transform:uppercase;}\n");
        html.append(".meta-grid dd{margin:2px 0 0;}\n");
        html.append(".run-outcome{text-align:right;}\n");
        html.append(".run-badge{display:inline-flex;flex-direction:column;align-items:flex-end;gap:6px;");
        html.append("padding:16px 20px;border-radius:10px;border:1px solid var(--border);background:var(--surface);");
        html.append("box-shadow:var(--shadow);min-width:160px;}\n");
        html.append(".run-badge .label{font-size:10px;font-weight:600;letter-spacing:.08em;text-transform:uppercase;");
        html.append("color:var(--ink-muted);}\n");
        html.append(".run-badge .value{font-size:1.35rem;font-weight:700;letter-spacing:-.02em;}\n");
        html.append(".run-badge.run-passed{border-color:#b8d4c0;background:var(--ok-bg);}\n");
        html.append(".run-badge.run-passed .value{color:var(--ok-fg);}\n");
        html.append(".run-badge.run-failed{border-color:#e8b4b4;background:var(--fail-bg);}\n");
        html.append(".run-badge.run-failed .value{color:var(--fail-fg);}\n");
        html.append(".run-badge.run-partial{border-color:#e8c9a0;background:var(--warn-bg);}\n");
        html.append(".run-badge.run-partial .value{color:var(--warn-fg);}\n");
        html.append(".run-badge.run-running,.run-badge.run-aborted{border-color:var(--border);background:var(--accent-soft);}\n");
        html.append(".run-badge.run-running .value,.run-badge.run-aborted .value{color:var(--accent);}\n");
        html.append("section{margin-top:36px;}\n");
        html.append("h2{font-size:13px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:var(--accent);");
        html.append("margin:0 0 14px;padding-bottom:8px;border-bottom:2px solid var(--accent-soft);}\n");
        html.append(".kpi-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:14px;}\n");
        html.append(".kpi{background:var(--surface);border:1px solid var(--border);border-radius:10px;padding:16px 18px;");
        html.append("box-shadow:var(--shadow);}\n");
        html.append(".kpi .k-label{font-size:11px;font-weight:600;color:var(--ink-muted);letter-spacing:.03em;");
        html.append("text-transform:uppercase;margin-bottom:6px;}\n");
        html.append(".kpi .k-value{font-size:1.5rem;font-weight:700;color:var(--accent);letter-spacing:-.02em;line-height:1.1;}\n");
        html.append(".kpi.kpi-rate .k-value{color:#1a5f8a;}\n");
        html.append(".table-wrap{background:var(--surface);border:1px solid var(--border);border-radius:12px;");
        html.append("overflow:hidden;box-shadow:var(--shadow);}\n");
        html.append(".table-wrap--wide{overflow-x:auto;-webkit-overflow-scrolling:touch;}\n");
        html.append("table{width:100%;border-collapse:collapse;font-size:.875rem;}\n");
        html.append("thead th{text-align:left;background:var(--accent);color:#fff;font-weight:600;font-size:11px;");
        html.append("letter-spacing:.05em;text-transform:uppercase;padding:13px 16px;border-bottom:1px solid #0a2d4d;}\n");
        html.append("tbody td{padding:12px 16px;border-top:1px solid var(--border);vertical-align:top;color:var(--ink);}\n");
        html.append("tbody tr:nth-child(even) td{background:var(--surface-alt);}\n");
        html.append("tbody tr:hover td{background:#eef6fc;}\n");
        html.append(".tc-id{font-family:ui-monospace,'Cascadia Code',monospace;font-size:.8rem;color:var(--ink-muted);");
        html.append("font-weight:600;}\n");
        html.append(".run-title-cell{font-size:.82rem;font-weight:600;color:var(--ink);max-width:14rem;}\n");
        html.append(".st{display:inline-block;padding:3px 10px;border-radius:999px;font-size:11px;font-weight:700;");
        html.append("letter-spacing:.02em;border:1px solid transparent;}\n");
        html.append(".st-PASSED{background:var(--ok-bg);color:var(--ok-fg);border-color:#b8d4c0;}\n");
        html.append(".st-FAILED{background:var(--fail-bg);color:var(--fail-fg);border-color:#e8b4b4;}\n");
        html.append(".st-BLOCKED{background:var(--warn-bg);color:var(--warn-fg);border-color:#e8c9a0;}\n");
        html.append(".st-PENDING{background:var(--neutral-bg);color:var(--neutral-fg);border-color:var(--border);}\n");
        html.append(".st-SKIPPED{background:var(--skip-bg);color:var(--skip-fg);border-color:#c7cae8;}\n");
        html.append(".steps-cell{color:var(--ink-muted);font-size:.82rem;line-height:1.5;");
        html.append("white-space:pre-line;}\n");
        html.append("footer.doc-footer{margin-top:40px;padding-top:22px;border-top:1px solid var(--rule);");
        html.append("display:grid;gap:8px;font-size:12px;color:var(--ink-muted);}\n");
        html.append(".footer-strong{color:var(--ink);font-weight:600;}\n");
        html.append("@media print{body{background:#fff;} .report{max-width:none;padding:0;} .doc-bar{margin:0;}");
        html.append(".table-wrap{box-shadow:none;} tbody tr:hover td{background:inherit;} ");
        html.append(".kpi{break-inside:avoid;} thead{display:table-header-group;} tr{break-inside:avoid;}}\n");
        html.append("@page{margin:16mm 14mm;}\n");
    }

    private static String batchRunIdsAbbrev(List<TestRun> runs) {
        if (runs == null || runs.isEmpty()) {
            return "—";
        }
        StringBuilder b = new StringBuilder();
        int max = Math.min(5, runs.size());
        for (int i = 0; i < max; i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(runs.get(i).getId());
        }
        if (runs.size() > 5) {
            b.append(" …");
        }
        return b.toString();
    }

    public static byte[] buildHtml(
        TestRun run,
        List<ExportRow> rows,
        Optional<byte[]> logoBytes,
        Optional<String> logoMime
    ) {
        String logoDataUri = "";
        if (logoBytes.isPresent() && logoMime.isPresent()) {
            String mime = logoMime.get();
            if (mime.contains("svg")) {
                mime = "image/svg+xml";
            }
            String b64 = Base64.getEncoder().encodeToString(logoBytes.get());
            logoDataUri = "data:" + mime + ";base64," + b64;
        }
        String generated = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.now());

        String htmlLang = Locale.getDefault().getLanguage();
        if (StringUtils.isBlank(htmlLang)) {
            htmlLang = "en";
        }
        String runBadge = runStatusCssKey(run.getStatus());

        StringBuilder html = new StringBuilder(24_000);
        html.append("<!DOCTYPE html>\n<html lang=\"").append(ExportHtmlEscape.text(htmlLang))
            .append("\">\n<head>\n<meta charset=\"utf-8\"/>\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>\n");
        html.append("<title>").append(ExportHtmlEscape.text(run.getName())).append(" — Test execution report</title>\n");
        html.append("<style>\n");
        appendSharedReportStyles(html);
        html.append("</style>\n</head>\n<body>\n<div class=\"report\">\n<div class=\"doc-bar\"></div>\n");
        html.append("<p class=\"doc-ribbon\">Test execution report · Quality assurance record</p>\n");
        html.append("<header class=\"doc-header\">\n<div class=\"doc-header-main\">\n");
        if (!logoDataUri.isEmpty()) {
            html.append("<div class=\"brand\"><img src=\"").append(logoDataUri).append("\" alt=\"\"/></div>\n");
        }
        html.append("<h1>").append(ExportHtmlEscape.text(run.getName())).append("</h1>\n");
        html.append("<p class=\"subtitle\">Structured test run documentation for traceability, release sign-off, and audit.</p>\n");
        html.append("<dl class=\"meta-grid\">\n");
        html.append("<div><dt>Run ID</dt><dd>").append(ExportHtmlEscape.text(run.getId())).append("</dd></div>\n");
        html.append("<div><dt>Job / build</dt><dd>").append(ExportHtmlEscape.text(run.getJobName()))
            .append(" · #").append(run.getBuildNumber()).append("</dd></div>\n");
        if (StringUtils.isNotBlank(run.getProjectScope())) {
            html.append("<div><dt>Project</dt><dd>").append(ExportHtmlEscape.text(run.getProjectScope())).append("</dd></div>\n");
        }
        if (run.getStartedAt() != null) {
            html.append("<div><dt>Started</dt><dd>")
                .append(ExportHtmlEscape.text(run.getStartedAtDisplay())).append("</dd></div>\n");
        }
        html.append("<div><dt>Report generated</dt><dd>").append(ExportHtmlEscape.text(generated)).append("</dd></div>\n");
        html.append("</dl>\n</div>\n<div class=\"run-outcome\">\n");
        html.append("<div class=\"run-badge run-").append(runBadge).append("\">\n");
        html.append("<span class=\"label\">Run outcome</span>\n");
        html.append("<span class=\"value\">").append(ExportHtmlEscape.text(String.valueOf(run.getStatus()))).append("</span>\n");
        html.append("</div>\n</div>\n</header>\n");
        html.append("<section aria-labelledby=\"summary-heading\">\n<h2 id=\"summary-heading\">Execution summary</h2>\n");
        html.append("<div class=\"kpi-grid\">\n");
        html.append("<div class=\"kpi kpi-rate\"><div class=\"k-label\">Pass rate</div><div class=\"k-value\">")
            .append(String.format(Locale.ROOT, "%.1f", run.getPassRate())).append("%</div></div>\n");
        html.append("<div class=\"kpi\"><div class=\"k-label\">Passed</div><div class=\"k-value\">")
            .append(run.getPassedCount()).append("</div></div>\n");
        html.append("<div class=\"kpi\"><div class=\"k-label\">Failed</div><div class=\"k-value\">")
            .append(run.getFailedCount()).append("</div></div>\n");
        html.append("<div class=\"kpi\"><div class=\"k-label\">Blocked</div><div class=\"k-value\">")
            .append(run.getBlockedCount()).append("</div></div>\n");
        html.append("</div>\n</section>\n");
        html.append("<section aria-labelledby=\"results-heading\">\n<h2 id=\"results-heading\">Test case results</h2>\n");
        html.append("<div class=\"table-wrap\"><table>\n<thead><tr>\n");
        html.append("<th scope=\"col\">Case ID</th><th scope=\"col\">Title</th><th scope=\"col\">Result</th>");
        html.append("<th scope=\"col\">Assignee</th><th scope=\"col\">Steps &amp; evidence</th>\n");
        html.append("</tr></thead>\n<tbody>\n");
        for (ExportRow r : rows) {
            html.append("<tr>\n<td class=\"tc-id\">").append(ExportHtmlEscape.text(r.getTestCaseId())).append("</td>\n");
            html.append("<td>").append(ExportHtmlEscape.text(r.getTitle())).append("</td>\n");
            String st = r.getStatus();
            String stKey = statusCssKey(st);
            html.append("<td><span class=\"st st-").append(stKey).append("\">")
                .append(ExportHtmlEscape.text(st)).append("</span></td>\n");
            html.append("<td>").append(ExportHtmlEscape.text(r.getAssignee())).append("</td>\n");
            html.append("<td class=\"steps-cell\">").append(ExportHtmlEscape.text(r.getStepsSummary())).append("</td>\n");
            html.append("</tr>\n");
        }
        html.append("</tbody>\n</table></div>\n</section>\n");
        html.append("<footer class=\"doc-footer\">\n");
        html.append("<span><span class=\"footer-strong\">Jenkins Test Management (JTM)</span> — ");
        html.append("This document reflects the linked test cases and results at export time.</span>\n");
        html.append("<span>Document reference: ").append(ExportHtmlEscape.text(run.getId())).append("</span>\n");
        html.append("</footer>\n</div>\n</body>\n</html>");
        return html.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Multi-run export (variant B): one table; each row includes run identity columns.
     */
    public static byte[] buildHtmlFlat(
        List<TestRun> runs,
        List<FlatExportRow> rows,
        Optional<byte[]> logoBytes,
        Optional<String> logoMime
    ) {
        String logoDataUri = "";
        if (logoBytes.isPresent() && logoMime.isPresent()) {
            String mime = logoMime.get();
            if (mime.contains("svg")) {
                mime = "image/svg+xml";
            }
            String b64 = Base64.getEncoder().encodeToString(logoBytes.get());
            logoDataUri = "data:" + mime + ";base64," + b64;
        }
        String generated = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.now());

        String htmlLang = Locale.getDefault().getLanguage();
        if (StringUtils.isBlank(htmlLang)) {
            htmlLang = "en";
        }
        int nRuns = runs.size();
        double avgPass = runs.stream().mapToDouble(TestRun::getPassRate).average().orElse(0);
        long sumPassed = runs.stream().mapToLong(TestRun::getPassedCount).sum();
        long sumFailed = runs.stream().mapToLong(TestRun::getFailedCount).sum();
        long sumBlocked = runs.stream().mapToLong(TestRun::getBlockedCount).sum();
        String idsFoot = batchRunIdsAbbrev(runs);

        StringBuilder html = new StringBuilder(32_000);
        html.append("<!DOCTYPE html>\n<html lang=\"").append(ExportHtmlEscape.text(htmlLang))
            .append("\">\n<head>\n<meta charset=\"utf-8\"/>\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>\n");
        html.append("<title>Multi-run test execution report</title>\n");
        html.append("<style>\n");
        appendSharedReportStyles(html);
        html.append("</style>\n</head>\n<body>\n<div class=\"report\">\n<div class=\"doc-bar\"></div>\n");
        html.append("<p class=\"doc-ribbon\">Multi-run export · Flat result table</p>\n");
        html.append("<header class=\"doc-header\">\n<div class=\"doc-header-main\">\n");
        if (!logoDataUri.isEmpty()) {
            html.append("<div class=\"brand\"><img src=\"").append(logoDataUri).append("\" alt=\"\"/></div>\n");
        }
        html.append("<h1>Multi-run test execution report</h1>\n");
        html.append("<p class=\"subtitle\">Combined linked test cases from the selected runs in a single table for ");
        html.append("comparison, filtering, and release documentation.</p>\n");
        html.append("<dl class=\"meta-grid\">\n");
        html.append("<div><dt>Runs in export</dt><dd>").append(nRuns).append("</dd></div>\n");
        html.append("<div><dt>Result rows</dt><dd>").append(rows.size()).append("</dd></div>\n");
        html.append("<div><dt>Run IDs</dt><dd>").append(ExportHtmlEscape.text(idsFoot)).append("</dd></div>\n");
        html.append("<div><dt>Report generated</dt><dd>").append(ExportHtmlEscape.text(generated)).append("</dd></div>\n");
        html.append("</dl>\n</div>\n<div class=\"run-outcome\">\n");
        html.append("<div class=\"run-badge run-running\">\n");
        html.append("<span class=\"label\">Runs bundled</span>\n");
        html.append("<span class=\"value\">").append(nRuns).append("</span>\n");
        html.append("</div>\n</div>\n</header>\n");
        html.append("<section aria-labelledby=\"summary-heading\">\n<h2 id=\"summary-heading\">Aggregate summary</h2>\n");
        html.append("<div class=\"kpi-grid\">\n");
        html.append("<div class=\"kpi kpi-rate\"><div class=\"k-label\">Avg pass rate (by run)</div><div class=\"k-value\">")
            .append(String.format(Locale.ROOT, "%.1f", avgPass)).append("%</div></div>\n");
        html.append("<div class=\"kpi\"><div class=\"k-label\">Σ Passed (runs)</div><div class=\"k-value\">")
            .append(sumPassed).append("</div></div>\n");
        html.append("<div class=\"kpi\"><div class=\"k-label\">Σ Failed (runs)</div><div class=\"k-value\">")
            .append(sumFailed).append("</div></div>\n");
        html.append("<div class=\"kpi\"><div class=\"k-label\">Σ Blocked (runs)</div><div class=\"k-value\">")
            .append(sumBlocked).append("</div></div>\n");
        html.append("</div>\n</section>\n");
        html.append("<section aria-labelledby=\"flat-heading\">\n<h2 id=\"flat-heading\">All results (flat)</h2>\n");
        html.append("<div class=\"table-wrap table-wrap--wide\"><table>\n<thead><tr>\n");
        html.append("<th scope=\"col\">Run ID</th><th scope=\"col\">Run name</th><th scope=\"col\">Project</th>");
        html.append("<th scope=\"col\">Job / build</th><th scope=\"col\">Case ID</th><th scope=\"col\">Title</th>");
        html.append("<th scope=\"col\">Result</th><th scope=\"col\">Assignee</th><th scope=\"col\">Steps &amp; evidence</th>\n");
        html.append("</tr></thead>\n<tbody>\n");
        for (FlatExportRow r : rows) {
            html.append("<tr>\n<td class=\"tc-id\">").append(ExportHtmlEscape.text(r.getRunId())).append("</td>\n");
            html.append("<td class=\"run-title-cell\">").append(ExportHtmlEscape.text(r.getRunName())).append("</td>\n");
            String pk = StringUtils.isNotBlank(r.getProjectScope()) ? r.getProjectScope() : "—";
            html.append("<td>").append(ExportHtmlEscape.text(pk)).append("</td>\n");
            html.append("<td class=\"tc-id\">").append(ExportHtmlEscape.text(r.getJobBuildLabel())).append("</td>\n");
            html.append("<td class=\"tc-id\">").append(ExportHtmlEscape.text(r.getTestCaseId())).append("</td>\n");
            html.append("<td>").append(ExportHtmlEscape.text(r.getTitle())).append("</td>\n");
            String st = r.getStatus();
            String stKey = statusCssKey(st);
            html.append("<td><span class=\"st st-").append(stKey).append("\">")
                .append(ExportHtmlEscape.text(st)).append("</span></td>\n");
            html.append("<td>").append(ExportHtmlEscape.text(r.getAssignee())).append("</td>\n");
            html.append("<td class=\"steps-cell\">").append(ExportHtmlEscape.text(r.getStepsSummary())).append("</td>\n");
            html.append("</tr>\n");
        }
        html.append("</tbody>\n</table></div>\n</section>\n");
        html.append("<footer class=\"doc-footer\">\n");
        html.append("<span><span class=\"footer-strong\">Jenkins Test Management (JTM)</span> — ");
        html.append("Multi-run flat export; each row is one linked test case in one run.</span>\n");
        html.append("<span>Runs: ").append(ExportHtmlEscape.text(idsFoot)).append("</span>\n");
        html.append("</footer>\n</div>\n</body>\n</html>");
        return html.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] buildPdf(
        TestRun run,
        List<ExportRow> rows,
        Optional<byte[]> logoBytes,
        Optional<String> logoMime
    ) throws DocumentException, IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        float marginSide = 44;
        float marginBottom = 52;
        Document doc = new Document(PageSize.A4, marginSide, marginSide, marginSide, marginBottom);
        PdfWriter writer = PdfWriter.getInstance(doc, out);

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 17);
        Font subtitleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Font.NORMAL, new Color(61, 79, 111));
        Font normal = FontFactory.getFont(FontFactory.HELVETICA, 10, new Color(11, 18, 32));
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(61, 79, 111));
        Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Font.NORMAL, Color.WHITE);
        Font footerFont = FontFactory.getFont(FontFactory.HELVETICA, 8, new Color(100, 116, 139));
        String generatedShort = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.now());

        writer.setPageEvent(new PdfReportFooter(footerFont, run.getId(), generatedShort));
        doc.open();

        Color accent = new Color(13, 59, 102);
        Color borderLight = new Color(216, 226, 239);
        Color rowEven = new Color(244, 246, 249);

        if (logoBytes.isPresent() && isRasterImage(logoBytes.get())) {
            Image img = Image.getInstance(logoBytes.get());
            img.scaleToFit(180, 70);
            img.setAlignment(Element.ALIGN_LEFT);
            doc.add(img);
            doc.add(new Paragraph(" "));
        }

        doc.add(new Paragraph("TEST EXECUTION REPORT", subtitleFont));
        doc.add(new Paragraph(run.getName(), titleFont));
        doc.add(new Paragraph(
            "Run ID " + run.getId() + "  ·  " + run.getJobName() + " #" + run.getBuildNumber()
                + "  ·  Outcome: " + run.getStatus(),
            small));
        if (StringUtils.isNotBlank(run.getProjectScope())) {
            doc.add(new Paragraph("Project: " + run.getProjectScope(), small));
        }
        doc.add(new Paragraph("Generated " + generatedShort + "  ·  JTM", small));
        doc.add(new Paragraph(" "));

        PdfPTable kpi = new PdfPTable(4);
        kpi.setWidthPercentage(100);
        kpi.setSpacingAfter(14f);
        float[] kpiW = {1f, 1f, 1f, 1f};
        kpi.setWidths(kpiW);
        String[] kpiLabels = {"Pass rate", "Passed", "Failed", "Blocked"};
        String[] kpiValues = {
            String.format(Locale.ROOT, "%.1f%%", run.getPassRate()),
            String.valueOf(run.getPassedCount()),
            String.valueOf(run.getFailedCount()),
            String.valueOf(run.getBlockedCount())
        };
        Font kpiLabelF = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7, Font.NORMAL, new Color(100, 116, 139));
        Font kpiValF = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Font.NORMAL, accent);
        for (int i = 0; i < 4; i++) {
            PdfPCell k = new PdfPCell();
            k.setBorder(Rectangle.BOX);
            k.setBorderColor(borderLight);
            k.setBackgroundColor(Color.WHITE);
            k.setPadding(10);
            k.addElement(new Paragraph(kpiLabels[i], kpiLabelF));
            k.addElement(new Paragraph(kpiValues[i], kpiValF));
            kpi.addCell(k);
        }
        doc.add(kpi);

        PdfPTable table = new PdfPTable(5);
        table.setWidths(new float[]{1.15f, 2.85f, 1.15f, 1.35f, 3.5f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String h : new String[]{"Case ID", "Title", "Result", "Assignee", "Steps & notes"}) {
            PdfPCell c = new PdfPCell(new Phrase(h, headerFont));
            c.setBackgroundColor(accent);
            c.setBorderColor(accent);
            c.setPadding(8);
            c.setVerticalAlignment(Element.ALIGN_MIDDLE);
            table.addCell(c);
        }
        int rowIdx = 0;
        for (ExportRow r : rows) {
            Color rowBg = Math.floorMod(rowIdx++, 2) == 1 ? rowEven : Color.WHITE;
            table.addCell(bodyCell(r.getTestCaseId(), normal, rowBg, borderLight, Element.ALIGN_LEFT));
            table.addCell(bodyCell(r.getTitle(), normal, rowBg, borderLight, Element.ALIGN_LEFT));
            table.addCell(statusCell(r.getStatus(), borderLight));
            table.addCell(bodyCell(r.getAssignee(), normal, rowBg, borderLight, Element.ALIGN_LEFT));
            table.addCell(stepsBodyCell(r.getStepsSummary(), small, rowBg, borderLight));
        }
        doc.add(table);
        doc.close();
        return out.toByteArray();
    }

    /**
     * Multi-run flat PDF (landscape A4): one table with run context columns.
     */
    public static byte[] buildPdfFlat(
        List<TestRun> runs,
        List<FlatExportRow> rows,
        Optional<byte[]> logoBytes,
        Optional<String> logoMime
    ) throws DocumentException, IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        float marginSide = 36;
        float marginBottom = 48;
        Document doc = new Document(PageSize.A4.rotate(), marginSide, marginSide, marginSide, marginBottom);
        PdfWriter writer = PdfWriter.getInstance(doc, out);

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15);
        Font subtitleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Font.NORMAL, new Color(61, 79, 111));
        Font normal = FontFactory.getFont(FontFactory.HELVETICA, 8, new Color(11, 18, 32));
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 7, new Color(61, 79, 111));
        Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7, Font.NORMAL, Color.WHITE);
        Font footerFont = FontFactory.getFont(FontFactory.HELVETICA, 7, new Color(100, 116, 139));
        String generatedShort = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.now());

        String batchRef = batchRunIdsAbbrev(runs);
        writer.setPageEvent(new PdfBatchReportFooter(footerFont, runs.size(), batchRef, generatedShort));
        doc.open();

        Color accent = new Color(13, 59, 102);
        Color borderLight = new Color(216, 226, 239);
        Color rowEven = new Color(244, 246, 249);

        if (logoBytes.isPresent() && isRasterImage(logoBytes.get())) {
            Image img = Image.getInstance(logoBytes.get());
            img.scaleToFit(160, 60);
            img.setAlignment(Element.ALIGN_LEFT);
            doc.add(img);
            doc.add(new Paragraph(" "));
        }

        doc.add(new Paragraph("MULTI-RUN TEST EXECUTION REPORT (FLAT)", subtitleFont));
        doc.add(new Paragraph(runs.size() + " run(s) · " + rows.size() + " result row(s)", titleFont));
        doc.add(new Paragraph("Runs: " + batchRef, small));
        doc.add(new Paragraph("Generated " + generatedShort + " · JTM", small));
        doc.add(new Paragraph(" "));

        double avgPass = runs.stream().mapToDouble(TestRun::getPassRate).average().orElse(0);
        long sumPassed = runs.stream().mapToLong(TestRun::getPassedCount).sum();
        long sumFailed = runs.stream().mapToLong(TestRun::getFailedCount).sum();
        long sumBlocked = runs.stream().mapToLong(TestRun::getBlockedCount).sum();

        PdfPTable kpi = new PdfPTable(6);
        kpi.setWidthPercentage(100);
        kpi.setSpacingAfter(12f);
        kpi.setWidths(new float[]{1f, 1f, 1f, 1f, 1f, 1f});
        String[] kpiLabels = {"Runs", "Rows", "Avg pass %", "Σ Passed", "Σ Failed", "Σ Blocked"};
        String[] kpiValues = {
            String.valueOf(runs.size()),
            String.valueOf(rows.size()),
            String.format(Locale.ROOT, "%.1f", avgPass),
            String.valueOf(sumPassed),
            String.valueOf(sumFailed),
            String.valueOf(sumBlocked)
        };
        Font kpiLabelF = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 6, Font.NORMAL, new Color(100, 116, 139));
        Font kpiValF = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Font.NORMAL, accent);
        for (int i = 0; i < 6; i++) {
            PdfPCell k = new PdfPCell();
            k.setBorder(Rectangle.BOX);
            k.setBorderColor(borderLight);
            k.setBackgroundColor(Color.WHITE);
            k.setPadding(8);
            k.addElement(new Paragraph(kpiLabels[i], kpiLabelF));
            k.addElement(new Paragraph(kpiValues[i], kpiValF));
            kpi.addCell(k);
        }
        doc.add(kpi);

        PdfPTable table = new PdfPTable(9);
        table.setWidths(new float[]{2.0f, 2.6f, 1.6f, 2.2f, 2.0f, 2.8f, 1.4f, 1.6f, 3.8f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String h : new String[]{
            "Run ID", "Run name", "Project", "Job / build", "Case ID", "Title", "Result", "Assignee", "Steps"
        }) {
            PdfPCell c = new PdfPCell(new Phrase(h, headerFont));
            c.setBackgroundColor(accent);
            c.setBorderColor(accent);
            c.setPadding(6);
            c.setVerticalAlignment(Element.ALIGN_MIDDLE);
            table.addCell(c);
        }
        int rowIdx = 0;
        for (FlatExportRow r : rows) {
            Color rowBg = Math.floorMod(rowIdx++, 2) == 1 ? rowEven : Color.WHITE;
            table.addCell(bodyCell(r.getRunId(), normal, rowBg, borderLight, Element.ALIGN_LEFT));
            table.addCell(bodyCell(r.getRunName(), normal, rowBg, borderLight, Element.ALIGN_LEFT));
            String pk = StringUtils.isNotBlank(r.getProjectScope()) ? r.getProjectScope() : "—";
            table.addCell(bodyCell(pk, normal, rowBg, borderLight, Element.ALIGN_LEFT));
            table.addCell(bodyCell(r.getJobBuildLabel(), normal, rowBg, borderLight, Element.ALIGN_LEFT));
            table.addCell(bodyCell(r.getTestCaseId(), normal, rowBg, borderLight, Element.ALIGN_LEFT));
            table.addCell(bodyCell(r.getTitle(), normal, rowBg, borderLight, Element.ALIGN_LEFT));
            table.addCell(statusCell(r.getStatus(), borderLight));
            table.addCell(bodyCell(r.getAssignee(), normal, rowBg, borderLight, Element.ALIGN_LEFT));
            table.addCell(stepsBodyCell(r.getStepsSummary(), small, rowBg, borderLight));
        }
        doc.add(table);
        doc.close();
        return out.toByteArray();
    }

    private static PdfPCell bodyCell(String text, Font f, Color bg, Color border, int hAlign) {
        PdfPCell c = new PdfPCell(new Phrase(text != null ? text : "", f));
        c.setPadding(6);
        c.setBackgroundColor(bg);
        c.setBorderColor(border);
        c.setHorizontalAlignment(hAlign);
        c.setVerticalAlignment(Element.ALIGN_TOP);
        return c;
    }

    /** One line per test step; newlines come from {@link #buildStepsSummary}. */
    private static PdfPCell stepsBodyCell(String text, Font f, Color bg, Color border) {
        PdfPCell c = new PdfPCell();
        c.setPadding(6);
        c.setBackgroundColor(bg);
        c.setBorderColor(border);
        c.setHorizontalAlignment(Element.ALIGN_LEFT);
        c.setVerticalAlignment(Element.ALIGN_TOP);
        String t = text != null ? text : "";
        if (t.isEmpty()) {
            c.addElement(new Phrase("", f));
            return c;
        }
        Paragraph p = new Paragraph();
        String[] lines = t.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                p.add(Chunk.NEWLINE);
            }
            p.add(new Chunk(lines[i], f));
        }
        c.addElement(p);
        return c;
    }

    private static PdfPCell statusCell(String status, Color border) {
        Color bg;
        Color fg;
        if ("PASSED".equals(status)) {
            bg = new Color(220, 252, 231);
            fg = new Color(22, 101, 52);
        } else if ("FAILED".equals(status)) {
            bg = new Color(254, 226, 226);
            fg = new Color(153, 27, 27);
        } else if ("BLOCKED".equals(status)) {
            bg = new Color(255, 237, 213);
            fg = new Color(154, 52, 18);
        } else if ("SKIPPED".equals(status)) {
            bg = new Color(224, 231, 255);
            fg = new Color(55, 48, 163);
        } else {
            bg = new Color(241, 245, 249);
            fg = new Color(71, 85, 105);
        }
        Font sf = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Font.NORMAL, fg);
        PdfPCell c = new PdfPCell(new Phrase(status != null ? status : "—", sf));
        c.setBackgroundColor(bg);
        c.setBorderColor(border);
        c.setPadding(6);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return c;
    }

    private static final class PdfReportFooter extends PdfPageEventHelper {
        private final Font font;
        private final String runId;
        private final String generated;

        PdfReportFooter(Font font, String runId, String generated) {
            this.font = font;
            this.runId = runId;
            this.generated = generated;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float cx = (document.left() + document.right()) / 2;
            float y = document.bottom() - 20;
            String line = "JTM · Test execution report · Ref. " + runId + " · Page " + writer.getPageNumber();
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, new Phrase(line, font), cx, y, 0);
            ColumnText.showTextAligned(
                cb,
                Element.ALIGN_CENTER,
                new Phrase("Generated " + generated, font),
                cx,
                y - 11,
                0
            );
        }
    }

    private static final class PdfBatchReportFooter extends PdfPageEventHelper {
        private final Font font;
        private final int runCount;
        private final String runIdsShort;
        private final String generated;

        PdfBatchReportFooter(Font font, int runCount, String runIdsShort, String generated) {
            this.font = font;
            this.runCount = runCount;
            this.runIdsShort = runIdsShort != null ? runIdsShort : "";
            this.generated = generated;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float cx = (document.left() + document.right()) / 2;
            float y = document.bottom() - 18;
            String line = "JTM · Multi-run flat export · " + runCount + " runs · " + runIdsShort
                + " · Page " + writer.getPageNumber();
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, new Phrase(line, font), cx, y, 0);
            ColumnText.showTextAligned(
                cb,
                Element.ALIGN_CENTER,
                new Phrase("Generated " + generated, font),
                cx,
                y - 10,
                0
            );
        }
    }

    private static String runStatusCssKey(TestRun.RunStatus status) {
        if (status == null) {
            return "running";
        }
        switch (status) {
            case PASSED:
                return "passed";
            case FAILED:
                return "failed";
            case PARTIAL:
                return "partial";
            case RUNNING:
                return "running";
            case ABORTED:
                return "aborted";
            default:
                return "running";
        }
    }

    private static String statusCssKey(String status) {
        if (status == null) {
            return "PENDING";
        }
        switch (status) {
            case "PASSED":
            case "FAILED":
            case "BLOCKED":
            case "PENDING":
            case "SKIPPED":
                return status;
            default:
                return "PENDING";
        }
    }

    static boolean isRasterImage(byte[] data) {
        if (data == null || data.length < 8) {
            return false;
        }
        if (data[0] == (byte) 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
            return true;
        }
        if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8 && data[2] == (byte) 0xFF) {
            return true;
        }
        return data[0] == 'G' && data[1] == 'I' && data[2] == 'F';
    }
}
