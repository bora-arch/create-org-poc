package com.example.provisioning.workflow.spi;

/**
 * Section keys used inside {@code default_config.json}. Only
 * {@code EnablePrmLicensesStep}/{@code DisablePrmLicensesStep} — the
 * one mutually-exclusive pair whose behavior genuinely depends on
 * {@code org_type} — consult this; they read
 * {@code DefaultConfigProvider} directly rather than through a
 * centrally-computed context field, since no other step needs it.
 */
public final class ConfigSections {

    public static final String LICENSE = "license";

    private ConfigSections() {
    }
}
