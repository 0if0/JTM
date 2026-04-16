package io.jenkins.plugins.jtm;

import hudson.Plugin;
import io.jenkins.plugins.jtm.persistence.JtmStore;

/**
 * Plugin lifecycle: flush JTM store async writer on shutdown.
 */
@SuppressWarnings("deprecation")
public class JtmPlugin extends Plugin {

    @Override
    public void stop() throws Exception {
        JtmStore.get().shutdown();
        super.stop();
    }
}
