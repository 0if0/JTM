package io.jenkins.plugins.jtm.importer;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Parses CSV/TXT imports into the same DTO bundle used by JSON imports.
 *
 * Format:
 * id,title,description,type,priority,risk,lifecycleStatus,projectKey,tags,steps
 *
 * steps field can contain multiple entries separated by {@code |}
 * and each entry is {@code action=>expectedResult}.
 */
public final class JtmDelimitedImportParser {

    private JtmDelimitedImportParser() {}

    public static JtmTestCaseImportParser.ImportBundle parseCsv(String content) {
        JtmTestCaseImportParser.ImportBundle b = new JtmTestCaseImportParser.ImportBundle();
        b.version = 1;
        if (content == null || content.isBlank()) {
            b.testCases = Collections.emptyList();
            return b;
        }

        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        if (lines.length == 0) {
            b.testCases = Collections.emptyList();
            return b;
        }

        List<String> header = splitCsvLine(lines[0]);
        List<JtmTestCaseImportParser.ImportCaseDto> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) {
                continue;
            }
            List<String> cols = splitCsvLine(line);
            JtmTestCaseImportParser.ImportCaseDto dto = new JtmTestCaseImportParser.ImportCaseDto();
            dto.id = get(cols, header, "id");
            dto.title = get(cols, header, "title");
            dto.description = get(cols, header, "description");
            dto.type = get(cols, header, "type");
            dto.priority = get(cols, header, "priority");
            dto.risk = get(cols, header, "risk");
            dto.lifecycleStatus = get(cols, header, "lifecycleStatus");
            dto.projectScope = get(cols, header, "projectKey");

            String tags = get(cols, header, "tags");
            dto.tags = tags == null || tags.isBlank()
                ? Collections.emptyList()
                : Arrays.stream(tags.split("[,;]"))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toList());

            dto.steps = parseSteps(get(cols, header, "steps"));
            out.add(dto);
        }
        b.testCases = out;
        return b;
    }

    private static List<JtmTestCaseImportParser.ImportStepDto> parseSteps(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        List<JtmTestCaseImportParser.ImportStepDto> steps = new ArrayList<>();
        for (String entry : raw.split("\\|")) {
            String e = entry == null ? "" : entry.trim();
            if (e.isBlank()) {
                continue;
            }
            String[] parts = e.split("=>", 2);
            JtmTestCaseImportParser.ImportStepDto s = new JtmTestCaseImportParser.ImportStepDto();
            s.action = parts[0].trim();
            s.expectedResult = parts.length > 1 ? parts[1].trim() : "";
            steps.add(s);
        }
        return steps;
    }

    private static String get(List<String> cols, List<String> header, String key) {
        int idx = -1;
        for (int i = 0; i < header.size(); i++) {
            String column = header.get(i);
            if (column != null && column.equalsIgnoreCase(key)) {
                idx = i;
                break;
            }
        }
        if (idx < 0 || idx >= cols.size()) {
            return null;
        }
        String v = cols.get(idx);
        return v == null ? null : v.trim();
    }

    private static List<String> splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        if (line == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (c == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }
}
