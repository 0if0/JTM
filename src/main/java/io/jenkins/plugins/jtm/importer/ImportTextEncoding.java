package io.jenkins.plugins.jtm.importer;

import java.nio.charset.StandardCharsets;

/**
 * Decodes uploaded import file bytes as text. Uses UTF-8 (with BOM strip). Replaces invalid
 * UTF-8 sequences (same as {@link String#String(byte[], int, int, java.nio.charset.Charset)}).
 * Avoids the old strict-UTF-8 + windows-1252 fallback that mojibaked valid UTF-8
 * (e.g. {@code gültigen} → {@code gÃ¼ltigen}).
 */
public final class ImportTextEncoding {

    private ImportTextEncoding() {}

    public static String decode(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        int off = 0;
        if (raw.length >= 3 && raw[0] == (byte) 0xEF && raw[1] == (byte) 0xBB && raw[2] == (byte) 0xBF) {
            off = 3;
        }
        return new String(raw, off, raw.length - off, StandardCharsets.UTF_8);
    }
}
