package io.jenkins.plugins.jtm.ui.actions;

import io.jenkins.plugins.jtm.core.domain.TestStep;
import org.kohsuke.stapler.StaplerRequest2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Parses {@code stepAction} / {@code stepExpected} arrays from multipart form posts
 * (new case + edit test case).
 */
final class JtmStepFormParser {

    private JtmStepFormParser() {}

    static List<TestStep> parseSteps(StaplerRequest2 req) {
        String[] actions = req.getParameterValues("stepAction");
        String[] expected = req.getParameterValues("stepExpected");
        int alen = actions != null ? actions.length : 0;
        int elen = expected != null ? expected.length : 0;
        if (alen == 0 && elen == 0) {
            return Collections.emptyList();
        }
        int len = Math.max(alen, elen);
        if (actions == null) {
            actions = new String[len];
        } else if (actions.length < len) {
            actions = Arrays.copyOf(actions, len);
        }
        if (expected == null) {
            expected = new String[len];
        } else if (expected.length < len) {
            expected = Arrays.copyOf(expected, len);
        }
        List<TestStep> out = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            String a = actions[i] != null ? actions[i].trim() : "";
            String e = expected[i] != null ? expected[i].trim() : "";
            TestStep s = new TestStep();
            s.setOrderIndex(i + 1);
            s.setAction(a);
            s.setExpectedResult(e);
            out.add(s);
        }
        return out;
    }
}
