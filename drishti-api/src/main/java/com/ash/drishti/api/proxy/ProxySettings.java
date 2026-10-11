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
package com.ash.drishti.api.proxy;

import com.ash.drishti.api.SettingSpec;
import com.ash.drishti.api.tls.Secrets;
import com.ash.drishti.api.tls.TlsException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The outbound HTTP proxy of one connector, AI provider or MCP server: the one mechanism every HTTP client in Drishti shares,
 * next to {@link com.ash.drishti.api.tls.TlsSettings}.
 *
 * <p>Keys (under {@code proxy.}; written as a {@code proxy:} block in the connector file):
 * <ul>
 *   <li>{@code url}: {@code http://host:port} of the proxy. Plain {@code http://} targets are forwarded through it and
 *       {@code https://} targets are tunnelled with {@code CONNECT}, so TLS ({@code tls.*}) is still negotiated with the target
 *       itself. The connection to the proxy is plain HTTP; a proxy that itself needs TLS is refused with a clear message.
 *   <li>{@code username}, {@code password}: Basic proxy authentication. The password is only a {@code ${ENV}} reference (or
 *       {@code password-file}), never a literal.
 *   <li>{@code no-proxy}: hosts that bypass the proxy (names, suffixes, addresses, CIDR blocks; see {@link NoProxy}).
 *   <li>{@code enabled}: false switches it off for this connector.
 * </ul>
 * {@code proxy: none} (the bare setting) switches it off for this connector whatever the site default says.
 *
 * <p>Where the effective proxy comes from, highest first: the connector's own {@code proxy:}; the site default
 * ({@link SiteProxy}, {@code drishti.http.proxy.*}); the standard {@code HTTPS_PROXY} / {@code HTTP_PROXY} / {@code NO_PROXY}
 * environment variables when the site asks for them ({@code drishti.http.proxy.from-environment}); none. {@link #source()} says
 * which, and {@link #describe()} / {@link #note()} are the masked forms for health and the Admin pages (the password is never
 * in them, in {@link #toString()}, or in a log line).
 */
public final class ProxySettings {

    /** Where the effective proxy came from. */
    public enum Source {
        /** No proxy anywhere: connections go direct. */
        NONE("none"),
        /** The connector says {@code proxy: none} (or {@code proxy.enabled: false}): direct, whatever the site default says. */
        DISABLED("disabled by the connector"),
        CONNECTOR("connector"),
        SITE("site default"),
        ENVIRONMENT("environment");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final ProxySettings NONE = new ProxySettings(Source.NONE, null, 0, null, null, new NoProxy(List.of()), null);
    private static final ProxySettings DISABLED = new ProxySettings(Source.DISABLED, null, 0, null, null, new NoProxy(List.of()), null);

    private final Source source;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final NoProxy noProxy;
    private final String variable;

    private ProxySettings(Source source, String host, int port, String username, String password, NoProxy noProxy, String variable) {
        this.source = source;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.noProxy = noProxy;
        this.variable = variable;
        if (host != null && username != null) {
            allowBasicThroughTunnels();         // before any JDK HTTP client or URL connection is created
        }
    }

    public static ProxySettings none() {
        return NONE;
    }

    /** The proxy for a connector whose target is {@code targetUrl} (null when not known), reading the process environment. */
    public static ProxySettings resolve(Map<String, String> settings, String targetUrl) {
        return resolve(settings, targetUrl, System::getenv);
    }

    public static ProxySettings resolve(Map<String, String> settings, String targetUrl, Function<String, String> env) {
        String bare = settings.get("proxy");
        if (bare != null && !bare.isBlank()) {
            if (isNone(bare)) {
                return DISABLED;
            }
            throw new ProxyException("proxy: '" + bare + "' is not a proxy: write 'proxy: none' to go direct, or a block with 'url: http://host:port'");
        }
        if (anyGiven(settings)) {
            return parse(settings, Source.CONNECTOR, env);
        }
        ProxySettings site = parse(SiteProxy.settings(), Source.SITE, env);
        if (site.active()) {
            return site;
        }
        if (SiteProxy.fromEnvironment()) {
            return fromEnvironment(targetUrl, env);
        }
        return NONE;
    }

    private static boolean isNone(String v) {
        String s = v.strip().toLowerCase(Locale.ROOT);
        return s.equals("none") || s.equals("off") || s.equals("false") || s.equals("direct");
    }

    private static boolean anyGiven(Map<String, String> settings) {
        return settings.keySet().stream().anyMatch(k -> k.startsWith("proxy."));
    }

    private static ProxySettings parse(Map<String, String> settings, Source source, Function<String, String> env) {
        if (settings.isEmpty()) {
            return NONE;
        }
        boolean connector = source == Source.CONNECTOR;
        String where = connector ? "proxy." : "drishti.http.proxy.";
        String enabled = get(settings, "proxy.enabled", env);
        if (enabled != null) {
            if (!enabled.equalsIgnoreCase("true") && !enabled.equalsIgnoreCase("false")) {
                throw new ProxyException(where + "enabled: '" + enabled + "' is not true or false");
            }
            if (enabled.equalsIgnoreCase("false")) {
                return connector ? DISABLED : NONE;
            }
        }
        String url = get(settings, "proxy.url", env);
        if (url == null) {
            if (connector) {
                throw new ProxyException(where + "url is missing: give the proxy as http://host:port (or write 'proxy: none')");
            }
            return NONE;
        }
        URI uri = parseUrl(url, where + "url");
        String user = get(settings, "proxy.username", env);
        String pw;
        try {
            pw = Secrets.secret(settings, "proxy.password", env);
        } catch (TlsException e) {
            throw new ProxyException(e.getMessage());
        }
        if (uri.getRawUserInfo() != null) {
            throw new ProxyException(where + "url must not carry credentials: put them in " + where + "username and " + where
                    + "password (an ${ENV} reference)");
        }
        if ((user == null) != (pw == null || pw.isEmpty())) {
            throw new ProxyException(where + (user == null ? "password is given without " + where + "username" : "username is given without " + where + "password"));
        }
        List<String> skip = new ArrayList<>(NoProxy.split(get(settings, "proxy.no-proxy", env)));
        settings.entrySet().stream().filter(e -> e.getKey().matches("proxy\\.no-proxy\\.\\d+")).sorted(Map.Entry.comparingByKey())
                .forEach(e -> skip.addAll(NoProxy.split(e.getValue())));        // a YAML list in an AI or MCP file arrives indexed
        return new ProxySettings(source, uri.getHost(), portOf(uri), user, pw == null || pw.isEmpty() ? null : pw, new NoProxy(skip), null);
    }

    private static String get(Map<String, String> settings, String key, Function<String, String> env) {
        try {
            return Secrets.get(settings, key, env);
        } catch (TlsException e) {
            throw new ProxyException(e.getMessage());
        }
    }

    private static ProxySettings fromEnvironment(String targetUrl, Function<String, String> env) {
        boolean https = targetUrl != null && targetUrl.regionMatches(true, 0, "https://", 0, 8);
        String name = null;
        String url = null;
        for (String n : https ? new String[] {"HTTPS_PROXY", "https_proxy"} : new String[] {"HTTP_PROXY", "http_proxy"}) {
            String v = env.apply(n);
            if (v != null && !v.isBlank()) {
                name = n.toUpperCase(Locale.ROOT);
                url = v.strip();
                break;
            }
        }
        if (url == null) {
            return NONE;
        }
        URI uri = parseUrl(url, name);
        String user = null;
        String pw = null;
        if (uri.getRawUserInfo() != null) {
            String[] up = uri.getRawUserInfo().split(":", 2);
            user = URLDecoder.decode(up[0], StandardCharsets.UTF_8);
            pw = up.length > 1 ? URLDecoder.decode(up[1], StandardCharsets.UTF_8) : null;
        }
        String skip = env.apply("NO_PROXY");
        if (skip == null) {
            skip = env.apply("no_proxy");
        }
        return new ProxySettings(Source.ENVIRONMENT, uri.getHost(), portOf(uri), user, pw, new NoProxy(NoProxy.split(skip)), name);
    }

    private static URI parseUrl(String url, String what) {
        String text = url.contains("://") ? url : "http://" + url;
        URI uri;
        try {
            uri = new URI(text);
        } catch (java.net.URISyntaxException e) {
            throw new ProxyException(what + ": '" + mask(url) + "' is not a proxy address (http://host:port)");
        }
        if (uri.getHost() == null) {
            throw new ProxyException(what + ": '" + mask(url) + "' is not a proxy address (http://host:port)");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https")) {
            throw new ProxyException(what + ": a proxy reached over TLS (https://) is not supported by the JDK and SDK clients Drishti uses; "
                    + "use http://" + uri.getHost() + ":" + portOf(uri) + " (https targets are still tunnelled with CONNECT and keep their own TLS)");
        }
        if (!scheme.equals("http")) {
            throw new ProxyException(what + ": '" + scheme + "://' is not supported: use http://host:port");
        }
        return uri;
    }

    private static int portOf(URI uri) {
        return uri.getPort() > 0 ? uri.getPort() : 80;
    }

    private static String mask(String url) {
        return url.replaceAll("://[^/@\\s]*@", "://***@");
    }

    // -- what it says ----------------------------------------------------------------------------------------------

    public Source source() {
        return source;
    }

    /** True when requests go through a proxy (for hosts the no-proxy list does not exempt). */
    public boolean active() {
        return host != null;
    }

    /** True when a proxy decision was made (a proxy is set, or the connector said none): SDK clients then ignore the JVM's own proxy properties. */
    public boolean decided() {
        return source != Source.NONE;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String username() {
        return username;
    }

    /** The password for the client that must send it; never log or display it. */
    public String password() {
        return password;
    }

    public boolean hasCredentials() {
        return username != null;
    }

    public NoProxy noProxy() {
        return noProxy;
    }

    /** {@code http://host:port}, without credentials. */
    public URI uri() {
        return host == null ? null : URI.create("http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port);
    }

    /** True when {@code host} is exempt (a no-proxy match), so a connection to it goes direct. */
    public boolean bypasses(String targetHost) {
        return noProxy.matches(targetHost);
    }

    /** True when a request to {@code targetHost} goes through the proxy. */
    public boolean appliesTo(String targetHost) {
        return active() && !bypasses(targetHost);
    }

    /** The masked form for health and the Admin pages: never the password. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", active());
        m.put("source", source.name().toLowerCase(Locale.ROOT));
        m.put("sourceLabel", source.label() + (variable == null ? "" : " (" + variable + ")"));
        if (active()) {
            m.put("url", uri().toString());
            m.put("username", username);
            m.put("password", password == null ? null : "***");
            m.put("noProxy", noProxy.entries());
        }
        return m;
    }

    /** A one-line, credential-free statement for health and the connection test, or "" when no proxy is in use. */
    public String note() {
        return active() ? "via proxy " + host + ":" + port + " (" + source.label() + ")" : "";
    }

    /** {@code health} with the proxy noted: {@code UP via proxy corp:3128 (site default)}. */
    public String annotate(String health) {
        return active() ? health + " " + note() : health;
    }

    /**
     * {@link #annotate(String)} for a connector with one target: when the target is on the no-proxy list the note says the proxy
     * is configured but not used for it ({@code UP (proxy not used for intranet.example: no-proxy)}).
     */
    public String annotate(String health, String targetUrl) {
        if (!active()) {
            return health;
        }
        String h = null;
        try {
            h = targetUrl == null ? null : URI.create(targetUrl).getHost();
        } catch (IllegalArgumentException ignored) {
            // an unusable URL is reported where it is parsed
        }
        return h != null && bypasses(h) ? health + " (proxy " + host + ":" + port + " not used for " + h + ": no-proxy)" : annotate(health);
    }

    @Override
    public String toString() {
        return active() ? "ProxySettings[" + source.label() + ", " + host + ":" + port + (username == null ? "" : ", user=" + username + ", password=***") + "]"
                : "ProxySettings[" + source.label() + "]";
    }

    // -- the JDK HttpClient ----------------------------------------------------------------------------------------

    /**
     * Gives the builder this proxy (and the Basic credentials, when there are any); a builder for a connector with no proxy comes
     * back unchanged. Targets on the no-proxy list go direct.
     */
    public HttpClient.Builder apply(HttpClient.Builder b) {
        if (!active()) {
            return b;
        }
        allowBasicThroughTunnels();
        b.proxy(selector());
        if (username != null) {
            b.authenticator(authenticator());
        }
        return b;
    }

    /**
     * {@code client.send}, with a failure that belongs to the proxy told as such (a refused login, an unreachable proxy, a
     * refused CONNECT) instead of the JDK's wording. Use it for every request of a client built with {@link #apply}.
     */
    public <T> java.net.http.HttpResponse<T> send(HttpClient client, java.net.http.HttpRequest request,
            java.net.http.HttpResponse.BodyHandler<T> handler) throws java.io.IOException, InterruptedException {
        try {
            return client.send(request, handler);
        } catch (java.io.IOException e) {
            throw explain(e);
        }
    }

    /** The failure with the proxy named when it is the proxy's: see {@link #send}. Unchanged when no proxy is in use or it does not look like the proxy's. */
    public java.io.IOException explain(java.io.IOException e) {
        if (!active()) {
            return e;
        }
        String via = host + ":" + port;
        String msg = String.valueOf(e.getMessage());
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = String.valueOf(t.getMessage());
            if (m.contains("too many authentication attempts") || m.matches("(?s).*\\b407\\b.*")) {
                return new java.io.IOException("the proxy " + via + " refused the credentials (HTTP 407 Proxy Authentication Required): "
                        + (hasCredentials() ? "check proxy.username and proxy.password" : "it needs proxy.username and proxy.password"), e);
            }
            if (t instanceof java.nio.channels.UnresolvedAddressException || t instanceof java.net.UnknownHostException) {
                return new java.io.IOException("cannot resolve the name " + (t instanceof java.net.UnknownHostException ? m : host)
                        + " (DNS): check proxy.url, or the target's name if the proxy reported it", e);
            }
            if (m.contains("Unable to tunnel through proxy")) {
                return new java.io.IOException("the proxy " + via + " refused to open a tunnel (CONNECT) to the target: " + m
                        + " (is the target allowed by the proxy, and its port 443 or the one you named?)", e);
            }
        }
        if (e instanceof java.net.ConnectException) {
            return new java.io.IOException("cannot connect to the proxy " + via + " (" + msg + "): check proxy.url and that the proxy is running", e);
        }
        return e;
    }

    /** The selector the JDK client asks, for every request: the proxy, or direct for a no-proxy host. */
    public ProxySelector selector() {
        final Proxy via = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port));
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI target) {
                return target == null || target.getHost() == null || !bypasses(target.getHost()) ? List.of(via) : List.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI target, SocketAddress address, java.io.IOException failure) {
                // the client reports the failure to the caller; nothing to remember here
            }
        };
    }

    /** Answers the proxy's 407 challenge (and nobody else's) with the credentials. */
    public Authenticator authenticator() {
        final PasswordAuthentication cred = new PasswordAuthentication(username, password.toCharArray());
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return getRequestorType() == RequestorType.PROXY ? cred : null;
            }
        };
    }

    /**
     * The JDK refuses Basic proxy credentials for a {@code CONNECT} tunnel by default
     * ({@code jdk.http.auth.tunneling.disabledSchemes=Basic}), which would make every authenticated https target fail with 407.
     * Drishti's proxy is a deliberate setting and Basic travels to a proxy the operator named, so the scheme is allowed unless the
     * operator set the property explicitly. It has to happen before the first JDK HTTP client is created; the server does it at
     * start.
     */
    public static void allowBasicThroughTunnels() {
        if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        }
    }

    // -- the connector form ------------------------------------------------------------------------------------------

    /**
     * The keys {@link #resolve} reads, as form/validator specs. The connector settings catalogue offers this section to every
     * plugin that speaks HTTP; the password is a secret (an {@code ${ENV}} reference only).
     */
    public static List<SettingSpec> settingSpecs() {
        String g = "proxy";
        List<SettingSpec> out = new ArrayList<>();
        out.add(new SettingSpec("proxy", "string", false, null,
                "Write 'proxy: none' to go direct for this connector even when the site has a default proxy; or use the proxy.* block", false, g));
        out.add(new SettingSpec("proxy.url", "string", false, null,
                "Outbound HTTP proxy, http://host:port (https targets are tunnelled with CONNECT). Absent: the site default (drishti.http.proxy.*) applies", false, g));
        out.add(new SettingSpec("proxy.username", "string", false, null, "Proxy user (Basic authentication)", false, g));
        out.add(new SettingSpec("proxy.password", "string", false, null,
                "Proxy password: ${ENV} placeholder, or use proxy.password-file", true, g));
        out.add(new SettingSpec("proxy.password-file", "path", false, null, "File whose first line is the proxy password", false, g));
        out.add(new SettingSpec("proxy.no-proxy", "list", false, null,
                "Hosts that bypass the proxy: names, .suffixes, addresses, CIDR blocks (10.0.0.0/8)", false, g));
        out.add(new SettingSpec("proxy.enabled", "boolean", false, "true", "false switches the proxy off for this connector", false, g));
        return List.copyOf(out);
    }
}
