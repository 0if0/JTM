package io.jenkins.plugins.jtm.ui.actions;

import io.jenkins.plugins.jtm.core.domain.TestCaseResult;
import io.jenkins.plugins.jtm.core.domain.TestStep;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One row on the test run detail page: a linked test case and its result in this run (if any).
 */
public final class RunLinkedCaseRow implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String testCaseId;
    private final String title;
    private final TestCaseResult result;
    /** Steps in list order (aligns with stepStatuses indices). */
    private final List<StepRow> stepRows;

    public RunLinkedCaseRow(String testCaseId, String title, TestCaseResult result,
                            List<TestStep> steps) {
        this.testCaseId = testCaseId;
        this.title = title != null ? title : "";
        this.result = result;
        List<TestStep> safe = steps != null ? steps : List.of();
        List<StepRow> rows = new ArrayList<>();
        for (int i = 0; i < safe.size(); i++) {
            rows.add(new StepRow(i, safe.get(i), this.result));
        }
        this.stepRows = Collections.unmodifiableList(rows);
    }

    public String getTestCaseId() {
        return testCaseId;
    }

    public String getTitle() {
        return title;
    }

    public TestCaseResult getResult() {
        return result;
    }

    public List<StepRow> getStepRows() {
        return stepRows;
    }

    public boolean isStepRowsEmpty() {
        return stepRows.isEmpty();
    }

    public boolean isPending() {
        return result == null || result.getStatus() == TestCaseResult.TestResultStatus.PENDING;
    }

    /** Value for the overall-result dropdown (must match {@link TestCaseResult.TestResultStatus} names). */
    public String getOverallResultSelectValue() {
        if (result == null) {
            return TestCaseResult.TestResultStatus.PENDING.name();
        }
        return result.getStatus().name();
    }

    public String getAssignedToDisplay() {
        if (result == null || result.getAssignedTo() == null || result.getAssignedTo().isBlank()) {
            return "";
        }
        return result.getAssignedTo();
    }

    /** Whole-case comment when the test case has no steps (same field as {@link TestCaseResult#getComment()}). */
    public String getCaseCommentValue() {
        if (result == null || result.getComment() == null) {
            return "";
        }
        return result.getComment();
    }

    /** One step row for Jelly: list index matches {@link TestCaseResult#getStepStatuses()} positions. */
    public static final class StepRow implements Serializable {
        private static final long serialVersionUID = 1L;
        private final int listIndex;
        private final TestStep step;
        /** Same run result as the parent row; used for Jelly step dropdown (see overallResultSelectValue). */
        private final TestCaseResult caseResult;

        public StepRow(int listIndex, TestStep step, TestCaseResult caseResult) {
            this.listIndex = listIndex;
            this.step = step;
            this.caseResult = caseResult;
        }

        public int getListIndex() {
            return listIndex;
        }

        public TestStep getStep() {
            return step;
        }

        /**
         * Current persisted step status for this row (for {@code &lt;option selected&gt;}), same idea as
         * {@link #getOverallResultSelectValue()}. Resolves on the row so Jelly can compare {@code sr.stepStatusSelectValue == opt}
         * reliably (multi-arg {@code it.stepStatusForCase(...)} did not match options in nested loops on some Jenkins/JEXL builds).
         */
        public String getStepStatusSelectValue() {
            if (caseResult == null || listIndex < 0) {
                return TestStep.StepStatus.NOT_RUN.name();
            }
            List<TestStep.StepStatus> statuses = caseResult.getStepStatuses();
            if (listIndex >= statuses.size()) {
                return TestStep.StepStatus.NOT_RUN.name();
            }
            TestStep.StepStatus st = statuses.get(listIndex);
            return (st != null ? st : TestStep.StepStatus.NOT_RUN).name();
        }

        /** Persisted step comment for this index (may be empty). */
        public String getStepCommentValue() {
            if (caseResult == null || listIndex < 0) {
                return "";
            }
            List<String> comments = caseResult.getStepComments();
            if (listIndex >= comments.size()) {
                return "";
            }
            String c = comments.get(listIndex);
            return c != null ? c : "";
        }
    }
}
