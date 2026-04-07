package io.jenkins.plugins.jtm.importer;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

public class ImportTextEncodingTest {

    @Test
    public void utf8GermanUmlauts_roundTrip() {
        String line = "TC-001,Login mit gültigen Daten,Benutzer kann sich anmelden\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        assertThat(ImportTextEncoding.decode(bytes)).isEqualTo(line);
    }

    @Test
    public void utf8WithBom_stripsBom() {
        String inner = "id,title\nTC-1,Größe";
        byte[] utf8 = inner.getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] raw = new byte[bom.length + utf8.length];
        System.arraycopy(bom, 0, raw, 0, bom.length);
        System.arraycopy(utf8, 0, raw, bom.length, utf8.length);
        assertThat(ImportTextEncoding.decode(raw)).isEqualTo(inner);
    }
}
