package io.jenkins.plugins.jtm.ui.actions;

import hudson.Extension;
import hudson.model.User;
import hudson.model.UserProperty;
import hudson.model.UserPropertyDescriptor;
import edu.umd.cs.findbugs.annotations.Nullable;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Persists the last selected JTM project scope ({@code ?project=}) for the user so navigation
 * without an explicit query parameter can restore it.
 */
public final class JtmUserPreferredProjectProperty extends UserProperty {

    @Nullable
    private String preferredProjectScope;

    @DataBoundConstructor
    public JtmUserPreferredProjectProperty(String preferredProjectScope) {
        this.preferredProjectScope = StringUtils.trimToNull(preferredProjectScope);
    }

    public JtmUserPreferredProjectProperty() {
        this.preferredProjectScope = null;
    }

    /**
     * Legacy user {@code config.xml} element; merged when {@code preferredProjectScope} is blank.
     */
    @Deprecated
    @DataBoundSetter
    public void setPreferredProjectKey(String legacy) {
        if (preferredProjectScope == null && legacy != null) {
            preferredProjectScope = StringUtils.trimToNull(legacy);
        }
    }

    @Nullable
    public String getPreferredProjectScope() {
        return preferredProjectScope;
    }

    public void setPreferredProjectScope(@Nullable String preferredProjectScope) {
        this.preferredProjectScope = StringUtils.trimToNull(preferredProjectScope);
    }

    @Override
    public UserPropertyDescriptor getDescriptor() {
        return (UserPropertyDescriptor) Jenkins.get().getDescriptorOrDie(JtmUserPreferredProjectProperty.class);
    }

    @Extension
    public static final class DescriptorImpl extends UserPropertyDescriptor {

        public DescriptorImpl() {
            super(JtmUserPreferredProjectProperty.class);
        }

        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public String getDisplayName() {
            return "JTM project scope";
        }

        @Override
        public UserProperty newInstance(User user) {
            return new JtmUserPreferredProjectProperty();
        }
    }
}
