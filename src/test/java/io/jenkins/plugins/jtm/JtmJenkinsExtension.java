package io.jenkins.plugins.jtm;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JtmJenkinsRule;

/** Supplies one JenkinsRule instance to the setup and test method of each test. */
public class JtmJenkinsExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
        ExtensionContext.Namespace.create(JtmJenkinsExtension.class);

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        JenkinsRule rule = new JtmJenkinsRule(
            context.getRequiredTestClass(), context.getRequiredTestMethod());
        try {
            rule.before();
        } catch (Throwable failure) {
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new Exception(failure);
        }
        context.getRoot().getStore(NAMESPACE).put(context.getRequiredTestInstance(), rule);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        JenkinsRule rule = context.getRoot().getStore(NAMESPACE).remove(
            context.getRequiredTestInstance(), JenkinsRule.class);
        if (rule != null) {
            rule.after();
        }
    }

    @Override
    public boolean supportsParameter(
        ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == JenkinsRule.class;
    }

    @Override
    public Object resolveParameter(
        ParameterContext parameterContext, ExtensionContext extensionContext)
        throws ParameterResolutionException {
        JenkinsRule rule = extensionContext.getRoot().getStore(NAMESPACE).get(
            extensionContext.getRequiredTestInstance(), JenkinsRule.class);
        if (rule == null) {
            throw new ParameterResolutionException("JenkinsRule was not initialized");
        }
        return rule;
    }
}
