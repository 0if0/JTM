package io.jenkins.plugins.jtm.export;

import java.io.Serializable;
import java.time.Instant;

/**
 * Metadata for the optional company logo used in run exports (stored under {@code jtm/branding/}).
 */
public final class JtmExportBranding implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Stored file name on disk, e.g. {@code logo.png}. */
    private String storedFileName;
    private String mimeType;
    private Instant uploadedAt;

    public String getStoredFileName() {
        return storedFileName;
    }

    public void setStoredFileName(String storedFileName) {
        this.storedFileName = storedFileName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(Instant uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
}
