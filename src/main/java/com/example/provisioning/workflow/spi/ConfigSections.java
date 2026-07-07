package com.example.provisioning.workflow.spi;

/**
 * Section keys used inside {@code default_config.json}. A step consults
 * {@link ProvisionContext#hasSection(String)} with one of these to
 * decide whether it applies to the current job.
 */
public final class ConfigSections {

    public static final String BRANDING = "branding";
    public static final String RFS_UI_PRM_PREFERENCES = "rfs_ui_prm_preferences";
    public static final String CITATIONS = "citations";
    public static final String LICENSE = "license";
    public static final String RECOMMENDATION_MODELS = "recommendation_models";
    public static final String BOOSTERS = "boosters";

    private ConfigSections() {
    }
}
