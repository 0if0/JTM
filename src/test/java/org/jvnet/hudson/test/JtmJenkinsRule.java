package org.jvnet.hudson.test;

import java.lang.reflect.Method;

/** JenkinsRule variant initialized with the JUnit 5 test description. */
public class JtmJenkinsRule extends JenkinsRule {

    public JtmJenkinsRule(Class<?> testClass, Method testMethod) {
        try {
            Class<?> descriptionType = Class.forName("org.junit.runner.Description");
            Object description = descriptionType
                .getMethod("createTestDescription", Class.class, String.class,
                    java.lang.annotation.Annotation[].class)
                .invoke(null, testClass, testMethod.getName(), testMethod.getAnnotations());
            JenkinsRule.class.getDeclaredField("testDescription").set(this, description);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not initialize Jenkins test description", failure);
        }
    }
}
