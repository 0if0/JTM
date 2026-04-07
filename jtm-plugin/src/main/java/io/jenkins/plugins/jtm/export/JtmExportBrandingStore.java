package io.jenkins.plugins.jtm.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists export branding (company logo) once under {@code $JENKINS_HOME/jtm/branding/}.
 */
public final class JtmExportBrandingStore {

    private static final Logger LOG = Logger.getLogger(JtmExportBrandingStore.class.getName());
    private static final String META = "branding.json";
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JtmExportBrandingStore() {}

    public static JtmExportBrandingStore get() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        private static final JtmExportBrandingStore INSTANCE = new JtmExportBrandingStore();
    }

    private Path brandingDir() {
        return Path.of(Jenkins.get().getRootDir().getPath(), "jtm", "branding");
    }

    private Path metaFile() {
        return brandingDir().resolve(META);
    }

    public Optional<JtmExportBranding> readMeta() {
        Path f = metaFile();
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(f.toFile(), JtmExportBranding.class));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[JTM] Failed to read branding metadata", e);
            return Optional.empty();
        }
    }

    public Optional<byte[]> readLogoBytes() {
        Optional<JtmExportBranding> meta = readMeta();
        if (meta.isEmpty() || meta.get().getStoredFileName() == null) {
            return Optional.empty();
        }
        Path p = brandingDir().resolve(meta.get().getStoredFileName());
        if (!Files.isRegularFile(p)) {
            return Optional.empty();
        }
        try {
            byte[] b = Files.readAllBytes(p);
            if (b.length > MAX_BYTES) {
                return Optional.empty();
            }
            return Optional.of(b);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[JTM] Failed to read branding logo file", e);
            return Optional.empty();
        }
    }

    public Optional<String> getLogoMimeType() {
        return readMeta().map(JtmExportBranding::getMimeType).filter(s -> s != null && !s.isBlank());
    }

    public boolean hasLogo() {
        return readLogoBytes().isPresent();
    }

    public void saveLogo(byte[] data, String mimeType, String originalHint) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("Empty file");
        }
        if (data.length > MAX_BYTES) {
            throw new IOException("Logo file too large (max 2 MB)");
        }
        String ext = extensionForMime(mimeType, originalHint);
        Files.createDirectories(brandingDir());
        String fileName = "logo" + ext;
        Path target = brandingDir().resolve(fileName);
        Files.write(target, data);

        JtmExportBranding m = new JtmExportBranding();
        m.setStoredFileName(fileName);
        m.setMimeType(mimeType != null ? mimeType : "application/octet-stream");
        m.setUploadedAt(Instant.now());
        JSON.writeValue(metaFile().toFile(), m);
    }

    public void clearLogo() throws IOException {
        Optional<JtmExportBranding> meta = readMeta();
        if (meta.isPresent() && meta.get().getStoredFileName() != null) {
            Path p = brandingDir().resolve(meta.get().getStoredFileName());
            Files.deleteIfExists(p);
        }
        Files.deleteIfExists(metaFile());
    }

    private static String extensionForMime(String mimeType, String originalHint) {
        if (originalHint != null) {
            String lower = originalHint.toLowerCase(Locale.ROOT);
            int dot = lower.lastIndexOf('.');
            if (dot >= 0 && dot < lower.length() - 1) {
                String ext = lower.substring(dot);
                if (ext.length() <= 6 && ext.matches("\\.[a-z0-9]+")) {
                    return ext;
                }
            }
        }
        if (mimeType == null) {
            return ".bin";
        }
        switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "image/png":
                return ".png";
            case "image/jpeg":
            case "image/jpg":
                return ".jpg";
            case "image/gif":
                return ".gif";
            case "image/svg+xml":
                return ".svg";
            case "image/webp":
                return ".webp";
            default:
                return ".bin";
        }
    }
}
