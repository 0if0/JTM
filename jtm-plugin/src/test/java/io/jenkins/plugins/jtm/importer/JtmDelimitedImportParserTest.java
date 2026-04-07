package io.jenkins.plugins.jtm.importer;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class JtmDelimitedImportParserTest {

    @Test
    public void parsesCsvRowsWithSteps() {
        String csv = "id,title,description,type,priority,risk,lifecycleStatus,projectKey,tags,steps\n"
            + "TC-CSV-1,Case 1,Desc,MANUAL,HIGH,MEDIUM,READY,Demo,\"a;b\","
            + "\"Open=>Visible|Submit=>Saved\"";
        JtmTestCaseImportParser.ImportBundle b = JtmDelimitedImportParser.parseCsv(csv);
        assertThat(b.testCases).hasSize(1);
        assertThat(b.testCases.get(0).id).isEqualTo("TC-CSV-1");
        assertThat(b.testCases.get(0).steps).hasSize(2);
        assertThat(b.testCases.get(0).steps.get(1).expectedResult).isEqualTo("Saved");
    }
}
