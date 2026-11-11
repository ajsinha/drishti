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

import com.ash.drishti.api.tls.Secrets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code drishti.notify.*}: where alerts and subscriptions may send besides mail and the inbox. Read once from the flat settings under {@code drishti.notify.}.
 *
 * <pre>
 * webhook.allowed-hosts          hooks.example.com, *.corp.example   the only hosts a webhook may point at (empty: no webhook is sent)
 * webhook.allow-http             false     true lets a webhook use http:// (testing only)
 * webhook.timeout                10s
 * webhooks.&lt;name&gt;.url           https://hooks.example.com/drishti
 * webhooks.&lt;name&gt;.secret        ${OPS_HOOK_SECRET}   signs every post (HMAC-SHA256); a ${ENV} reference, never the text
 * webhooks.&lt;name&gt;.header.&lt;H&gt;    a fixed header, ${ENV} allowed
 * webhooks.&lt;name&gt;.tls.* / .proxy.*   the same TLS and proxy keys as a connector
 * sms.url                        https://sms.example/send?to={to}&amp;text={text}   the HTTP gateway; no vendor is built in
 * sms.method                     POST (default) or GET
 * sms.body                       {"to":"{to}","message":"{text}"}   the request body template ({to}, {text}, {sender})
 * sms.content-type               application/json (default)
 * sms.header.&lt;H&gt;                e.g. sms.header.Authorization = Bearer ${SMS_TOKEN}
 * sms.sender                     the sender id, {sender} in the templates
 * sms.max-chars                  160     longer text is cut with an ellipsis
 * sms.include-values             false   true lets an alert's text carry the person's own figure (default: title, severity and link only)
 * sms.timeout                    10s
 * sms.tls.* / sms.proxy.*        as for a connector
 * </pre>
 * Nothing here holds a secret: a {@code ${ENV}} reference is resolved from the process environment when the message is sent.
 */
public final class NotifySettings {

    /** One named webhook an administrator defined. */
    public record Webhook(String name, String url, String secret, Map<String, String> headers, Map<String, String> http) {

        /** The host of the URL, lower case, or blank when it does not parse. */
        public String host() {
            try {
                String h = java.net.URI.create(url).getHost();
                return h == null ? "" : h.toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                return "";
            }
        }
    }

    /** The SMS gateway: templates, headers and the length limit. */
    public record Sms(String url, String method, String body, String contentType, Map<String, String> headers, String sender, int maxChars, Duration timeout,
            Map<String, String> http) {

        public boolean configured() {
            return url != null && !url.isBlank();
        }
    }

    private final List<String> allowedHosts;
    private final boolean allowHttp;
    private final Duration webhookTimeout;
    private final Map<String, Webhook> webhooks;
    private final Sms sms;
    private final Map<String, String> flat;

    private NotifySettings(Map<String, String> flat) {
        this.flat = flat;
        this.allowedHosts = list(flat.get("webhook.allowed-hosts"));
        this.allowHttp = Boolean.parseBoolean(flat.getOrDefault("webhook.allow-http", "false").strip());
        this.webhookTimeout = duration(flat.get("webhook.timeout"), Duration.ofSeconds(10));
        Map<String, Webhook> hooks = new TreeMap<>();
        for (String key : flat.keySet()) {
            if (key.startsWith("webhooks.") && key.endsWith(".url")) {
                String name = key.substring("webhooks.".length(), key.length() - ".url".length());
                if (!name.isEmpty() && name.indexOf('.') < 0) {
                    String p = "webhooks." + name + ".";
                    hooks.put(name, new Webhook(name, flat.get(key).strip(), flat.get(p + "secret"), sub(flat, p + "header."), http(flat, p)));
                }
            }
        }
        this.webhooks = Map.copyOf(hooks);
        this.sms = new Sms(flat.get("sms.url"), flat.getOrDefault("sms.method", "POST").strip().toUpperCase(Locale.ROOT), flat.get("sms.body"),
                flat.getOrDefault("sms.content-type", "application/json").strip(), sub(flat, "sms.header."), flat.getOrDefault("sms.sender", "").strip(),
                (int) Math.max(20, Math.min(1000, number(flat.get("sms.max-chars"), 160))), duration(flat.get("sms.timeout"), Duration.ofSeconds(10)), http(flat, "sms."));
    }

    /** From the flat map of {@code drishti.notify.*} keys with the prefix removed (e.g. {@code webhooks.ops.url}). */
    public static NotifySettings of(Map<String, String> flat) {
        return new NotifySettings(flat == null ? Map.of() : new LinkedHashMap<>(flat));
    }

    public Map<String, Webhook> webhooks() {
        return webhooks;
    }

    public Sms sms() {
        return sms;
    }

    /** Whether a text may carry the alert's one-line message (with the person's own figure); false (default): title, severity and link only. */
    public boolean smsIncludeValues() {
        return Boolean.parseBoolean(flat.getOrDefault("sms.include-values", "false").strip());
    }

    public boolean allowHttp() {
        return allowHttp;
    }

    public Duration webhookTimeout() {
        return webhookTimeout;
    }

    public List<String> allowedHosts() {
        return allowedHosts;
    }

    /** Whether the administrator's allow-list admits {@code host}: an exact name, or {@code *.suffix} for any name under the suffix. */
    public boolean hostAllowed(String host) {
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        for (String a : allowedHosts) {
            if (a.equals(h) || a.startsWith("*.") && h.endsWith(a.substring(1)) && h.length() > a.length() - 1) {
                return true;
            }
        }
        return false;
    }

    /** The names a person may pick (the URLs and secrets stay with the administrator). */
    public List<String> webhookNames() {
        return new ArrayList<>(webhooks.keySet());
    }

    /** A secret or a header value with its {@code ${ENV}} references resolved now. */
    public static String resolve(String raw, String key) {
        return raw == null || raw.isBlank() ? null : Secrets.get(Map.of(key, raw), key);
    }

    /** The value of a setting with its {@code ${ENV}} references resolved, or null. */
    public String get(String key) {
        return resolve(flat.get(key), key);
    }

    private static Map<String, String> sub(Map<String, String> flat, String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        flat.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                out.put(k.substring(prefix.length()), v);
            }
        });
        return out;
    }

    /** The {@code tls.*} and {@code proxy.*} keys under a prefix, in the shape the shared TLS and proxy readers take. */
    private static Map<String, String> http(Map<String, String> flat, String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        flat.forEach((k, v) -> {
            if (k.startsWith(prefix + "tls.") || k.startsWith(prefix + "proxy.")) {
                out.put(k.substring(prefix.length()), v);
            } else if (k.equals(prefix + "proxy")) {
                out.put("proxy", v);
            }
        });
        return out;
    }

    private static List<String> list(String s) {
        List<String> out = new ArrayList<>();
        if (s != null) {
            for (String p : s.split("[,\\s]+")) {
                if (!p.isBlank()) {
                    out.add(p.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        return List.copyOf(out);
    }

    private static double number(String s, double dflt) {
        try {
            return s == null || s.isBlank() ? dflt : Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static Duration duration(String s, Duration dflt) {
        return s == null || s.isBlank() ? dflt : com.ash.drishti.common.RetryPolicy.parse(s);
    }
}
