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
package com.ash.drishti.server.notify;

import com.ash.drishti.api.proxy.ProxySettings;
import com.ash.drishti.api.tls.TlsHttp;
import com.ash.drishti.api.tls.TlsMaterial;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Sends a webhook post or a text message over HTTP. Both go through the TLS and proxy settings every connector uses; redirects are never followed; the answer's
 * body is never read into a message or log; a failure is described without the URL (which may carry a token).
 *
 * <p>A webhook post is JSON, signed: {@code X-Drishti-Signature: sha256=<hex>} is HMAC-SHA256, keyed with the webhook's secret, over
 * {@code <X-Drishti-Timestamp>.<body>}, so the receiver can check who sent it and refuse a replay. The target must be a webhook an administrator defined and its
 * host must be on {@code drishti.notify.webhook.allowed-hosts}. A text message is built from the gateway's templates; the caller supplies the words and they are cut
 * to {@code sms.max-chars}.
 */
public final class NotifyChannels {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final NotifySettings settings;
    private final Clock clock;
    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();

    public NotifyChannels(NotifySettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public NotifySettings settings() {
        return settings;
    }

    /** Whether a text can be sent at all (a gateway is configured). */
    public boolean smsConfigured() {
        return settings.sms().configured();
    }

    // ---- webhook -------------------------------------------------------------------------------------------------------------

    /** Posts {@code body} to the webhook called {@code name}. Returns the HTTP status; throws {@link NotifyException}. */
    public int webhook(String name, String event, ObjectNode body) {
        NotifySettings.Webhook w = settings.webhooks().get(name);
        if (w == null) {
            throw new NotifyException("no webhook named '" + name + "' is defined (drishti.notify.webhooks)", true);
        }
        URI uri = uri(w.url(), "webhook '" + name + "'");
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !(settings.allowHttp() && "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new NotifyException("webhook '" + name + "' is not https:// (drishti.notify.webhook.allow-http is false)", true);
        }
        if (!settings.hostAllowed(w.host())) {
            throw new NotifyException("webhook '" + name + "' points at a host that is not on drishti.notify.webhook.allowed-hosts", true);
        }
        byte[] payload;
        try {
            payload = JSON.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new NotifyException("the payload could not be written", true);
        }
        String ts = Long.toString(clock.instant().getEpochSecond());
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(settings.webhookTimeout()).header("Content-Type", "application/json").header("X-Drishti-Event", event)
                .header("X-Drishti-Timestamp", ts).header("User-Agent", "Drishti-Webhook");
        String secret = secret(w);
        if (secret != null) {
            b.header("X-Drishti-Signature", "sha256=" + sign(secret, ts, payload));
        }
        for (Map.Entry<String, String> h : w.headers().entrySet()) {
            b.header(h.getKey(), expand(h.getValue(), "webhook '" + name + "' header " + h.getKey()));
        }
        return send("webhook:" + name, uri, w.http(), b.POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build(), settings.webhookTimeout(), "the webhook");
    }

    private String secret(NotifySettings.Webhook w) {
        try {
            return NotifySettings.resolve(w.secret(), "webhooks." + w.name() + ".secret");
        } catch (RuntimeException e) {
            throw new NotifyException("webhook '" + w.name() + "': " + e.getMessage(), true);
        }
    }

    /** {@code sha256=} value of HMAC-SHA256(secret, timestamp + "." + body), hex. Public so a receiver's test can compute the same. */
    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- sms -----------------------------------------------------------------------------------------------------------------

    /** The text cut to the gateway's length limit with an ellipsis. */
    public String fit(String text) {
        String t = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        int max = settings.sms().maxChars();
        return t.length() <= max ? t : t.substring(0, max - 1).stripTrailing() + "…";
    }

    /** Sends {@code text} (cut to the limit) to {@code phone} through the gateway. Returns the HTTP status; throws {@link NotifyException}. */
    public int sms(String phone, String text) {
        NotifySettings.Sms s = settings.sms();
        if (!s.configured()) {
            throw new NotifyException("no SMS gateway is configured (drishti.notify.sms.url)", true);
        }
        if (phone == null || !phone.strip().matches("\\+?[0-9][0-9 ()-]{5,20}")) {
            throw new NotifyException("the phone number is not a number the gateway can take", true);
        }
        String to = phone.strip().replaceAll("[ ()-]", "");
        String words = fit(text);
        boolean get = "GET".equals(s.method());
        String type = s.contentType().toLowerCase(Locale.ROOT);
        URI uri = uri(fill(s.url(), to, words, s.sender(), "url"), "the SMS gateway");
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(s.timeout()).header("User-Agent", "Drishti-Sms");
        for (Map.Entry<String, String> h : s.headers().entrySet()) {
            b.header(h.getKey(), expand(h.getValue(), "sms header " + h.getKey()));
        }
        if (get) {
            b.GET();
        } else {
            String template = s.body() == null ? "{\"to\":\"{to}\",\"message\":\"{text}\"}" : s.body();
            b.header("Content-Type", s.contentType()).POST(HttpRequest.BodyPublishers.ofString(fill(template, to, words, s.sender(), type), StandardCharsets.UTF_8));
        }
        return send("sms", uri, s.http(), b.build(), s.timeout(), "the SMS gateway");
    }

    /** {to}, {text} and {sender} in a template, each escaped for where it lands: URL-encoded, JSON-escaped or form-encoded. */
    private String fill(String template, String to, String text, String sender, String kind) {
        String expanded = expand(template, "sms " + kind);
        boolean url = kind.equals("url") || kind.contains("x-www-form-urlencoded");
        boolean json = kind.contains("json");
        return expanded.replace("{to}", escape(to, url, json)).replace("{text}", escape(text, url, json)).replace("{sender}", escape(sender, url, json));
    }

    private static String escape(String v, boolean url, boolean json) {
        if (url) {
            return URLEncoder.encode(v, StandardCharsets.UTF_8);
        }
        if (json) {
            try {
                String q = JSON.writeValueAsString(v);
                return q.substring(1, q.length() - 1);
            } catch (IOException e) {
                return "";
            }
        }
        return v;
    }

    // ---- http ----------------------------------------------------------------------------------------------------------------

    private static String expand(String raw, String where) {
        try {
            String v = NotifySettings.resolve(raw, "value");
            return v == null ? "" : v;
        } catch (RuntimeException e) {
            throw new NotifyException(where + ": " + e.getMessage(), true);
        }
    }

    private static URI uri(String text, String what) {
        try {
            URI u = URI.create(text.strip());
            if (u.getHost() == null || u.getScheme() == null) {
                throw new IllegalArgumentException("no host");
            }
            return u;
        } catch (IllegalArgumentException e) {
            throw new NotifyException(what + " has a URL that is not valid", true);
        }
    }

    private int send(String id, URI uri, Map<String, String> http, HttpRequest request, Duration timeout, String what) {
        HttpClient client = clients.computeIfAbsent(id, k -> build(uri, http, timeout));
        HttpResponse<Void> r;
        try {
            r = client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (HttpTimeoutException e) {
            throw new NotifyException(what + " did not answer in time", false);
        } catch (IOException e) {
            throw new NotifyException(what + " could not be reached (" + e.getClass().getSimpleName() + ")", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotifyException("interrupted while sending", false);
        }
        int s = r.statusCode();
        if (s >= 200 && s < 300) {
            return s;
        }
        if (s >= 300 && s < 400) {
            throw new NotifyException(what + " answered HTTP " + s + " (a redirect); redirects are not followed", true);
        }
        throw new NotifyException(what + " answered HTTP " + s, s == 408 || s == 429 || s >= 500 ? false : true);
    }

    private static HttpClient build(URI uri, Map<String, String> http, Duration timeout) {
        try {
            ProxySettings proxy = ProxySettings.resolve(http, uri.toString());
            HttpClient.Builder b = proxy.apply(HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1));
            TlsMaterial tls = TlsHttp.material(http, uri.toString(), "the URL");
            return (tls == null ? b : TlsHttp.apply(b, tls)).build();
        } catch (RuntimeException e) {
            throw new NotifyException("the TLS or proxy settings are not usable: " + e.getMessage(), true);
        }
    }

    /** Names of the hooks (for the forms). */
    public List<String> webhookNames() {
        return settings.webhookNames();
    }
}
