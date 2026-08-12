package com.hyde.sfauth;

/**
 * Central configuration for the JWT Bearer login.
 *
 * Each value reads from an environment variable first (recommended for CI so the
 * private key path / consumer key aren't committed), and falls back to the literal
 * below if the variable is not set. Edit the fallbacks for quick local runs, but
 * keep the private KEY FILE itself out of source control.
 */
public final class SalesforceConfig {

    /**
     * Login / audience host.
     *   Sandbox:    https://test.salesforce.com
     *   Production: https://login.salesforce.com
     *   Or your My Domain URL if the login policy enforces it, e.g.
     *   https://yourco--uat.sandbox.my.salesforce.com
     */
    public static final String LOGIN_URL =
            envOr("SF_AUDIENCE", "https://test.salesforce.com");

    /** Connected App consumer key -> JWT 'iss'. */
    public static final String CONSUMER_KEY =
            envOr("SF_CONSUMER_KEY", "PASTE_CONSUMER_KEY_HERE");

    /** Automation user's username -> JWT 'sub'. */
    public static final String USERNAME =
            envOr("SF_USERNAME", "svc-testauto@yourco.com.uat");

    /** Absolute path to the PEM private key file (jwt_testauto.key). */
    public static final String PRIVATE_KEY_PATH =
            envOr("SF_PRIVATE_KEY", "C:/secure/jwt_testauto.key");

    /** Relative path to land on after login. */
    public static final String START_PATH =
            envOr("SF_START_PATH", "/lightning/page/home");

    /** false = show the browser (so you can watch it), true = headless. */
    public static final boolean HEADLESS =
            Boolean.parseBoolean(envOr("SF_HEADLESS", "false"));

    /** How long to keep the browser open at the end to visually confirm the home screen. */
    public static final int CONFIRM_WAIT_SECONDS =
            Integer.parseInt(envOr("SF_CONFIRM_WAIT", "20"));

    private SalesforceConfig() { }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
