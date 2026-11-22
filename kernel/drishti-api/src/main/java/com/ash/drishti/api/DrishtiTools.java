/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.api;

import com.ash.tools.net.ProxySettings;
import com.ash.tools.net.SiteProxy;
import com.ash.tools.net.TlsContexts;
import com.ash.tools.net.TlsHttp;
import com.ash.tools.net.TlsMaterial;
import com.ash.tools.net.TlsSettings;
import com.ash.tools.settings.Placeholders;
import com.ash.tools.settings.SettingSpec;
import com.ash.tools.settings.SettingsFile;
import com.ash.tools.time.LoadGuard;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The names Drishti hands the shared tools ({@code drishti-tools}: settings and placeholders, TLS and proxy). The tools name no product:
 * the setting file's property, the install folder's setting, the environment variable that allows an insecure TLS trust, and the key
 * prefix a proxy message points the operator at are parameters, and this is the one place Drishti gives them. Every TLS context, every
 * site-proxy setup and the settings file of Drishti go through here.
 */
public final class DrishtiTools {
    /** The system property that names the settings files (one absolute path, or several separated by commas). */
    public static final String SETTINGS_FILE = "drishti.settings.file";
    /** The environment variable that must be {@code true} for {@code tls.insecure-trust-all} to be accepted. */
    public static final String ALLOW_INSECURE_TLS = "DRISHTI_ALLOW_INSECURE_TLS";
    /** How a message names the site's proxy settings. */
    public static final String SITE_PROXY_PREFIX = "drishti.http.proxy.";

    /** The prefix of the environment variable that names the business zone of a load ({@code LAKE_BUSINESS_ZONE}: the loaders are the lake's). */
    public static final String ENV_PREFIX = "LAKE_";

    private DrishtiTools() {}

    /** The guard a loader's command line asks for ({@code --zone}, {@code --as-of}, ...); the business zone from {@code LAKE_BUSINESS_ZONE}. */
    public static LoadGuard loadGuard(String[] args) {
        return LoadGuard.fromArgs(args, ENV_PREFIX);
    }

    /** Applies the settings files named by {@link #SETTINGS_FILE}; {@code ${drishti.home}} in a value is the install folder. */
    public static List<String> applySettingsFile() {
        return SettingsFile.applyFrom(SETTINGS_FILE, Roots.HOME);
    }

    /** The site-wide proxy ({@code drishti.http.proxy.*}), handed over by the server at start. */
    public static void siteProxy(Map<String, String> settings, boolean fromEnvironment) {
        SiteProxy.configure(settings, fromEnvironment, SITE_PROXY_PREFIX);
    }

    /** The TLS material of {@code s}, placeholders resolved the way every Drishti file is. */
    public static TlsMaterial tls(TlsSettings s) {
        return tls(s, Placeholders::process, Clock.systemUTC());
    }

    /** As {@link #tls(TlsSettings)} with the environment and the clock given (a test passes both). */
    public static TlsMaterial tls(TlsSettings s, Function<String, String> env, Clock clock) {
        return TlsContexts.build(s, env, clock, ALLOW_INSECURE_TLS);
    }

    /** The TLS material for an HTTPS URL a setting names, or null for a plain URL. */
    public static TlsMaterial tlsMaterial(Map<String, String> settings, String url, String urlSetting) {
        return TlsHttp.material(settings, url, urlSetting, Placeholders::process, ALLOW_INSECURE_TLS);
    }

    /** The settings a TLS block ({@code tls.*}, or a prefix of the caller's) takes, for the settings catalogue. */
    public static List<SettingSpec> tlsSettingSpecs(String prefix) {
        return TlsSettings.settingSpecs(prefix, ALLOW_INSECURE_TLS);
    }

    /**
     * The one place a Drishti HTTP client is started: a builder with the proxy {@code proxy} resolved (null: none, as for the cluster's own peers) and the
     * TLS material {@code tls} (null: the JVM's trust) applied. The caller adds its timeouts, redirects and version and builds. A guard
     * ({@code ArchitectureRulesTest}) allows {@code HttpClient.newBuilder} nowhere else.
     */
    public static HttpClient.Builder httpBuilder(ProxySettings proxy, TlsMaterial tls) {
        HttpClient.Builder b = HttpClient.newBuilder();
        if (proxy != null) {
            b = proxy.apply(b);
        }
        return tls == null ? b : TlsHttp.apply(b, tls);
    }

    /** {@link #httpBuilder(ProxySettings, TlsMaterial)} with the proxy and no client TLS identity (the JVM's trust). */
    public static HttpClient.Builder httpBuilder(ProxySettings proxy) {
        return httpBuilder(proxy, null);
    }

    /** A builder for an outbound call whose target is not known yet: the site proxy (or the environment's) for https targets, the JVM's trust. */
    public static HttpClient.Builder httpBuilder() {
        return httpBuilder(ProxySettings.resolve(Map.of(), "https://"), null);
    }

    /** A builder for the URL {@code url} (named {@code urlSetting} in messages) with the {@code settings}' {@code proxy.*} and {@code tls.*} (or the site's). */
    public static HttpClient.Builder httpBuilder(Map<String, String> settings, String url, String urlSetting) {
        return httpBuilder(ProxySettings.resolve(settings, url), tlsMaterial(settings, url, urlSetting));
    }
}
