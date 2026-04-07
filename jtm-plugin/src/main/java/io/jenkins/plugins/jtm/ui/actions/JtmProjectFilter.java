package io.jenkins.plugins.jtm.ui.actions;

import hudson.model.User;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.Stapler;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads optional {@code ?project=} query parameter for scoping test cases and runs in the UI.
 * When the request has no {@code project} parameter, falls back to the signed-in user’s
 * saved preference ({@link JtmUserPreferredProjectProperty}).
 */
public final class JtmProjectFilter {

    private static final Logger LOG = Logger.getLogger(JtmProjectFilter.class.getName());

    private JtmProjectFilter() {}

    /** Trimmed project key, or {@code null} when “all projects”. */
    public static String current() {
        if (Stapler.getCurrentRequest() == null) {
            return preferredProjectFromUser();
        }
        if (Stapler.getCurrentRequest().getParameterMap().containsKey("project")) {
            String p = StringUtils.trimToNull(Stapler.getCurrentRequest().getParameter("project"));
            savePreferredProject(p);
            return p;
        }
        return preferredProjectFromUser();
    }

    private static String preferredProjectFromUser() {
        User u = User.current();
        if (u == null) {
            return null;
        }
        JtmUserPreferredProjectProperty prop = u.getProperty(JtmUserPreferredProjectProperty.class);
        if (prop == null || StringUtils.isBlank(prop.getPreferredProjectKey())) {
            return null;
        }
        return prop.getPreferredProjectKey();
    }

    private static void savePreferredProject(String trimmedOrNull) {
        User u = User.current();
        if (u == null) {
            return;
        }
        try {
            JtmUserPreferredProjectProperty prop = u.getProperty(JtmUserPreferredProjectProperty.class);
            if (prop == null) {
                prop = new JtmUserPreferredProjectProperty();
                u.addProperty(prop);
            }
            prop.setPreferredProjectKey(trimmedOrNull);
            u.save();
        } catch (IOException e) {
            LOG.log(Level.FINE, "[JTM] Could not persist preferred project for user", e);
        }
    }

    /** Suffix like {@code ?project=foo} or empty string. */
    public static String urlQueryParam() {
        String p = current();
        if (p == null) {
            return "";
        }
        try {
            return "?project=" + URLEncoder.encode(p, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return "?project=" + p;
        }
    }
}
