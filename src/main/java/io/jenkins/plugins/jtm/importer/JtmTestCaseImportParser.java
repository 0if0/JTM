package io.jenkins.plugins.jtm.importer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Parses JSON bundles for bulk test case import (cases + steps).
 */
public final class JtmTestCaseImportParser {

    private static final ObjectMapper OM = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JtmTestCaseImportParser() {}

    public static ImportBundle parse(String json) throws IOException {
        return OM.readValue(json, ImportBundle.class);
    }

    public static ImportBundle parse(InputStream in) throws IOException {
        return OM.readValue(in, ImportBundle.class);
    }

    public static ImportBundle parseBytes(byte[] utf8) throws IOException {
        return parse(new String(utf8, StandardCharsets.UTF_8));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ImportBundle {
        public int version;
        public List<ImportCaseDto> testCases = Collections.emptyList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ImportCaseDto {
        public String id;
        public String title;
        public String description;
        public String type;
        public String priority;
        public String risk;
        public String lifecycleStatus;
        // lgtm[java] not a credential; logical project scope key
        public String projectKey;
        public List<String> tags = Collections.emptyList();
        public List<ImportStepDto> steps = Collections.emptyList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ImportStepDto {
        public String action;
        public String expectedResult;
    }
}
