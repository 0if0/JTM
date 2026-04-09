package io.jenkins.plugins.jtm.export;

import io.jenkins.plugins.jtm.core.domain.TestRun;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class TestRunExportServiceTest {

    @Test
    public void html_containsRunMetadata() {
        TestRun run = new TestRun();
        run.setId("RUN-EXPORT-1");
        run.setName("Release 42");
        run.setJobName("manual");
        run.setBuildNumber(3);
        run.setProjectScope("WebApp");
        run.setStatus(TestRun.RunStatus.PARTIAL);
        List<ExportRow> rows = List.of(
            new ExportRow("TC-1", "Login", "PASSED", "alice", "1: PASSED")
        );
        byte[] html = TestRunExportService.buildHtml(run, rows, Optional.empty(), Optional.empty());
        String s = new String(html, StandardCharsets.UTF_8);
        assertThat(s).contains("RUN-EXPORT-1").contains("Release 42").contains("TC-1").contains("Login");
    }

    @Test
    public void pdf_nonEmpty() throws Exception {
        TestRun run = new TestRun();
        run.setId("RUN-PDF-1");
        run.setName("PDF Run");
        run.setJobName("j");
        run.setBuildNumber(1);
        run.setStatus(TestRun.RunStatus.PASSED);
        List<ExportRow> rows = List.of(
            new ExportRow("TC-9", "Case", "FAILED", "—", "—")
        );
        byte[] pdf = TestRunExportService.buildPdf(run, rows, Optional.empty(), Optional.empty());
        assertThat(pdf.length).isGreaterThan(200);
        assertThat(new String(pdf, 0, Math.min(5, pdf.length), StandardCharsets.ISO_8859_1)).startsWith("%PDF");
    }

    @Test
    public void isRasterImage_detectsPng() {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        assertThat(TestRunExportService.isRasterImage(png)).isTrue();
    }

    @Test
    public void flatExportRow_jobBuildLabel() {
        FlatExportRow r = new FlatExportRow(
            "R1", "N", "my-job", 42, "P", "TC-1", "T", "PASSED", "u", "—");
        assertThat(r.getJobBuildLabel()).isEqualTo("my-job #42");
    }

    @Test
    public void htmlFlat_containsRunColumns() {
        TestRun run = new TestRun();
        run.setId("RUN-B");
        run.setName("Beta");
        run.setJobName("j");
        run.setBuildNumber(2);
        run.setProjectScope("X");
        run.setStatus(TestRun.RunStatus.PASSED);
        List<TestRun> runs = List.of(run);
        List<FlatExportRow> flat = List.of(
            new FlatExportRow("RUN-B", "Beta", "j", 2, "X", "TC-9", "Login", "PASSED", "alice", "1: PASSED")
        );
        byte[] html = TestRunExportService.buildHtmlFlat(runs, flat, Optional.empty(), Optional.empty());
        String s = new String(html, StandardCharsets.UTF_8);
        assertThat(s).contains("RUN-B").contains("Beta").contains("TC-9").contains("Multi-run");
    }

    @Test
    public void pdfFlat_nonEmpty() throws Exception {
        TestRun run = new TestRun();
        run.setId("RUN-PDF-M");
        run.setName("M");
        run.setJobName("j");
        run.setBuildNumber(1);
        run.setStatus(TestRun.RunStatus.PASSED);
        List<TestRun> runs = List.of(run);
        List<FlatExportRow> flat = List.of(
            new FlatExportRow("RUN-PDF-M", "M", "j", 1, "", "TC-1", "Case", "FAILED", "—", "—")
        );
        byte[] pdf = TestRunExportService.buildPdfFlat(runs, flat, Optional.empty(), Optional.empty());
        assertThat(pdf.length).isGreaterThan(200);
        assertThat(new String(pdf, 0, Math.min(5, pdf.length), StandardCharsets.ISO_8859_1)).startsWith("%PDF");
    }
}
