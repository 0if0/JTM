package io.jenkins.plugins.jtm.ui.actions;

import hudson.Extension;
import hudson.model.User;
import hudson.model.UserProperty;
import hudson.model.UserPropertyDescriptor;
import edu.umd.cs.findbugs.annotations.Nullable;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Persists the last selected JTM project scope ({@code ?project=}) for the user so navigation
 * without an explicit query parameter can restore it.
 */
public final class JtmUserPreferredProjectProperty extends UserProperty {

    @Nullable
    private String preferredProjectKey; // lgtm[java] not a credential; UI convenience project scope key

    @DataBoundConstructor
    public JtmUserPreferredProjectProperty(String preferredProjectKey) {
        this.preferredProjectKey = StringUtils.trimToNull(preferredProjectKey);
    }

    public JtmUserPreferredProjectProperty() {
        this.preferredProjectKey = null;
    }

    @Nullable
    public String getPreferredProjectKey() {
        return preferredProjectKey;
    }

    public void setPreferredProjectKey(@Nullable String preferredProjectKey) {
        this.preferredProjectKey = StringUtils.trimToNull(preferredProjectKey);
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
