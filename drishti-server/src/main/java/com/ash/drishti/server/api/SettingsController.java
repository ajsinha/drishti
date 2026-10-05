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
package com.ash.drishti.server.api;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Personal settings (W21), kept on the server so they follow the user to any browser: theme, the page to land on after
 * signing in, the clock's time zone, density, whether changed values flash, pinned entities and the default number
 * of search results. Every value is validated here; a patch changes only the fields it names ({@code null} resets
 * one to its default).
 */
@RestController
@RequestMapping("/api/v1/me/settings")
public class SettingsController {

    static final String NS = "settings";
    static final Set<String> THEMES = Set.of("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark");
    static final Set<String> DENSITIES = Set.of("comfortable", "compact");
    private static final Pattern LOCALE = Pattern.compile("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,3}$");
    private static final Pattern LANDING = Pattern.compile("^/(t|w/[^/?#]{1,64}|m/[^/?#]{1,64}|v/[a-z0-9-]{1,40}/[^/?#]{1,128}|help)$");
    private static final Pattern KIND = Pattern.compile("^[a-z0-9-]{1,40}$");
    private static final int MAX_PINS = 20;

    private final PreferenceStore store;
    private final ObjectMapper json = new ObjectMapper();

    public SettingsController(PreferenceStore store) {
        this.store = store;
    }

    @GetMapping
    public ObjectNode get(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return withDefaults(stored(p.user()));
    }

    @PatchMapping
    public ObjectNode patch(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (!body.isObject()) {
            throw bad("settings are an object");
        }
        ObjectNode next = stored(p.user()).deepCopy();
        body.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            if (v == null || v.isNull()) {
                next.remove(e.getKey());
                return;
            }
            switch (e.getKey()) {
                case "theme" -> next.put("theme", oneOf(v, THEMES, "theme"));
                case "density" -> next.put("density", oneOf(v, DENSITIES, "density"));
                case "landing" -> {
                    if (!LANDING.matcher(v.asText()).matches()) {
                        throw bad("landing must be /t, /help, a workspace (/w/…), a monitor (/m/…) or a view (/v/kind/id)");
                    }
                    next.put("landing", v.asText());
                }
                case "clockZone" -> {
                    try {
                        next.put("clockZone", ZoneId.of(v.asText()).getId());
                    } catch (DateTimeException ex) {
                        throw bad("unknown time zone '" + v.asText() + "'");
                    }
                }
                case "locale" -> {
                    if (!LOCALE.matcher(v.asText()).matches()) {
                        throw bad("locale is a language tag such as en, fr or fr-CA");
                    }
                    next.put("locale", v.asText());
                }
                case "flash" -> next.put("flash", bool(v, "flash"));
                case "searchLimit" -> {
                    if (!v.canConvertToInt() || v.asInt() < 10 || v.asInt() > 1000) {
                        throw bad("searchLimit is 10 to 1000");
                    }
                    next.put("searchLimit", v.asInt());
                }
                case "pinned" -> next.set("pinned", pins(v));
                default -> throw bad("unknown setting '" + e.getKey() + "'");
            }
        });
        store.put(p.user(), NS, NS, next);
        return withDefaults(next);
    }

    private ObjectNode stored(String user) {
        return store.get(user, NS, NS).filter(JsonNode::isObject).map(n -> (ObjectNode) n).orElseGet(json::createObjectNode);
    }

    private ObjectNode withDefaults(ObjectNode s) {
        ObjectNode out = json.createObjectNode();
        out.set("theme", s.path("theme").isMissingNode() ? json.nullNode() : s.get("theme"));
        out.put("landing", s.path("landing").asText("/t"));
        out.set("clockZone", s.path("clockZone").isMissingNode() ? json.nullNode() : s.get("clockZone"));
        out.set("locale", s.path("locale").isMissingNode() ? json.nullNode() : s.get("locale"));
        out.put("density", s.path("density").asText("comfortable"));
        out.put("flash", s.path("flash").asBoolean(true));
        out.put("searchLimit", s.path("searchLimit").asInt(100));
        out.set("pinned", s.path("pinned").isArray() ? s.get("pinned") : json.createArrayNode());
        return out;
    }

    private ArrayNode pins(JsonNode v) {
        if (!v.isArray() || v.size() > MAX_PINS) {
            throw bad("pinned is a list of at most " + MAX_PINS + " entities");
        }
        ArrayNode out = json.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode e : v) {
            String kind = e.path("kind").asText();
            String id = e.path("id").asText();
            if (!KIND.matcher(kind).matches() || id.isBlank() || id.length() > 128) {
                throw bad("each pinned entity needs a kind and an id");
            }
            if (seen.add(kind + "/" + id)) {
                out.addObject().put("kind", kind).put("id", id);
            }
        }
        return out;
    }

    private static String oneOf(JsonNode v, Set<String> allowed, String what) {
        if (!v.isTextual() || !allowed.contains(v.asText())) {
            throw bad(what + " is one of " + new java.util.TreeSet<>(allowed));
        }
        return v.asText();
    }

    private static boolean bool(JsonNode v, String what) {
        if (!v.isBoolean()) {
            throw bad(what + " is true or false");
        }
        return v.asBoolean();
    }

    private static DrishtiException bad(String message) {
        return new DrishtiException(ErrorCode.BAD_REQUEST, message);
    }
}
