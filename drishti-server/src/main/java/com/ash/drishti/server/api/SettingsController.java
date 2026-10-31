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
    static final Set<String> THEMES = Set.of("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark",
            "terminal-green", "terminal-crimson", "terminal-blue");
    static final Set<String> DENSITIES = Set.of("comfortable", "compact");
    static final Set<String> DESIGNER_MODES = Set.of("customize", "describe", "outline", "grid");
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
                case "designerMode" -> next.put("designerMode", oneOf(v, DESIGNER_MODES, "designerMode"));
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
                case "lens", "homeLanding" -> {          // the edition a person opened as a lens; their choice of an edition's landing (EDITIONS.md 8)
                    if (!v.asText().matches("[a-z0-9][a-z0-9-]{0,63}")) {
                        throw bad(e.getKey() + " is an edition or landing name (lower-case letters, digits and dashes)");
                    }
                    next.put(e.getKey(), v.asText());
                }
                case "tradingView" -> {                 // Drishti Trading's "View as": a trader or a desk (EDITIONS.md 14); never widens access
                    if (!v.asText().matches("(trader|desk):[A-Za-z0-9][A-Za-z0-9._~-]{0,79}")) {
                        throw bad("tradingView is trader:<trader id> or desk:<desk id>");
                    }
                    next.put("tradingView", v.asText());
                }
                case "varView" -> next.set("varView", varView(v));   // 2.9.7: the VaR page's horizon, method and confidences
                case "flash" -> next.put("flash", bool(v, "flash"));
                case "searchLimit" -> {
                    if (!v.canConvertToInt() || v.asInt() < 10 || v.asInt() > 1000) {
                        throw bad("searchLimit is 10 to 1000");
                    }
                    next.put("searchLimit", v.asInt());
                }
                case "pinned" -> next.set("pinned", pins(v));
                case "savedSearches" -> next.set("savedSearches", savedSearches(v));
                case "notify" -> next.set("notify", notify(v, next.path("notify")));
                case "refresh" -> next.set("refresh", refresh(v, next.path("refresh")));
                default -> throw bad("unknown setting '" + e.getKey() + "'");
            }
        });
        store.put(p.user(), NS, NS, next);
        return withDefaults(next);
    }

    /**
     * The VaR page's last lens (2.9.7, VAR_GUIDE.md section 18.6): {@code {"h": "1d"|"10d", "m": "sqrt10"|"sum", "q": 0.99, "esq": 0.975}},
     * each optional; the page asks the engine, which says whether a confidence is offered, so only the shape is checked here.
     */
    private ObjectNode varView(JsonNode v) {
        if (!v.isObject()) {
            throw bad("varView is {h, m, q, esq}");
        }
        ObjectNode out = json.createObjectNode();
        v.fields().forEachRemaining(e -> {
            JsonNode x = e.getValue();
            if (x == null || x.isNull()) {
                return;
            }
            switch (e.getKey()) {
                case "h" -> out.put("h", oneOf(x, java.util.Set.of("1d", "10d"), "varView.h"));
                case "m" -> out.put("m", oneOf(x, java.util.Set.of("sqrt10", "sum"), "varView.m"));
                case "q", "esq" -> {
                    if (!x.isNumber() || x.asDouble() <= 0.5 || x.asDouble() >= 1) {
                        throw bad("varView." + e.getKey() + " is a confidence in (0.5, 1)");
                    }
                    out.put(e.getKey(), x.asDouble());
                }
                default -> throw bad("varView has h, m, q and esq, not '" + e.getKey() + "'");
            }
        });
        return out;
    }

    private static final Pattern SCREEN = Pattern.compile("^[a-z0-9-]{1,40}/[A-Za-z0-9._@-]{1,80}$");
    private static final Pattern PANEL = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    static final int MAX_SCREENS = 100;
    static final int MAX_PANELS = 40;

    /**
     * The reader's refresh choices (SUTRA_NEXT.md 9), per screen ({@code <kind>/<sutra>}): {@code {"trade/my-desk": {"all": true, "panels":
     * {"curve": {"paused": true, "every": 60}}}}}. A patch replaces the screens it names ({@code null} forgets one) and keeps the rest; the
     * oldest screens go past {@value #MAX_SCREENS}. Whether a panel may be paused, and how fast it may go, the console decides from the Sutra.
     */
    private ObjectNode refresh(JsonNode v, JsonNode current) {
        if (!v.isObject()) {
            throw bad("refresh is an object of screens");
        }
        ObjectNode out = current.isObject() ? ((ObjectNode) current).deepCopy() : json.createObjectNode();
        v.fields().forEachRemaining(e -> {
            if (!SCREEN.matcher(e.getKey()).matches()) {
                throw bad("refresh: '" + e.getKey() + "' is not a screen (<kind>/<sutra>)");
            }
            out.remove(e.getKey());
            JsonNode s = e.getValue();
            if (s == null || s.isNull()) {
                return;
            }
            if (!s.isObject()) {
                throw bad("refresh." + e.getKey() + " is an object");
            }
            ObjectNode screen = out.putObject(e.getKey());
            if (s.has("all")) {
                screen.put("all", bool(s.get("all"), "refresh.all"));
            }
            JsonNode panels = s.path("panels");
            if (!panels.isMissingNode() && !panels.isObject() || panels.size() > MAX_PANELS) {
                throw bad("refresh panels are an object of at most " + MAX_PANELS + " panels");
            }
            ObjectNode kept = screen.putObject("panels");
            panels.fields().forEachRemaining(pe -> {
                JsonNode c = pe.getValue();
                if (!PANEL.matcher(pe.getKey()).matches() || !c.isObject()) {
                    throw bad("refresh: '" + pe.getKey() + "' is not a panel's choice");
                }
                ObjectNode one = kept.putObject(pe.getKey());
                if (c.has("paused")) {
                    one.put("paused", bool(c.get("paused"), "refresh paused"));
                }
                if (c.has("every")) {
                    if (!c.get("every").canConvertToInt() || c.get("every").asInt() < 1 || c.get("every").asInt() > 86_400) {
                        throw bad("refresh every is 1 to 86400 seconds");
                    }
                    one.put("every", c.get("every").asInt());
                }
            });
        });
        while (out.size() > MAX_SCREENS) {
            out.remove(out.fieldNames().next());
        }
        return out;
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
        out.put("designerMode", s.path("designerMode").asText("customize"));       // the Sutra designer's mode a person opens first
        out.put("flash", s.path("flash").asBoolean(true));
        out.put("searchLimit", s.path("searchLimit").asInt(100));
        ObjectNode email = json.createObjectNode();
        com.ash.drishti.server.collab.mail.NotifyPrefs.EVENTS.forEach(e -> email.put(e, s.path("notify").path("email").path(e).asBoolean(true)));
        out.putObject("notify").set("email", email);
        out.set("refresh", s.path("refresh").isObject() ? s.get("refresh") : json.createObjectNode());
        out.set("pinned", s.path("pinned").isArray() ? s.get("pinned") : json.createArrayNode());
        out.set("savedSearches", s.path("savedSearches").isArray() ? s.get("savedSearches") : json.createArrayNode());
        out.set("lens", s.path("lens").isTextual() ? s.get("lens") : json.nullNode());                  // EDITIONS.md 8: the console reads them back
        out.set("homeLanding", s.path("homeLanding").isTextual() ? s.get("homeLanding") : json.nullNode());
        out.set("tradingView", s.path("tradingView").isTextual() ? s.get("tradingView") : json.nullNode());   // Drishti Trading's View as
        out.set("varView", s.path("varView").isObject() ? s.get("varView") : json.nullNode());        // the VaR page's lens (2.9.7)
        return out;
    }

    /** {@code {email: {share, mention, reply}}}: which events are emailed; a patch changes only the events it names. */
    private ObjectNode notify(JsonNode v, JsonNode current) {
        if (!v.isObject() || !v.path("email").isObject() || v.size() != 1) {
            throw bad("notify is {email: {share, mention, reply}} with true or false for each");
        }
        ObjectNode email = current.path("email").isObject() ? ((ObjectNode) current.get("email")).deepCopy() : json.createObjectNode();
        v.get("email").fields().forEachRemaining(e -> {
            if (!com.ash.drishti.server.collab.mail.NotifyPrefs.EVENTS.contains(e.getKey())) {
                throw bad("unknown notification '" + e.getKey() + "'; the events are " + com.ash.drishti.server.collab.mail.NotifyPrefs.EVENTS);
            }
            email.put(e.getKey(), bool(e.getValue(), "notify.email." + e.getKey()));
        });
        ObjectNode out = json.createObjectNode();
        out.set("email", email);
        return out;
    }

    /** Named searches from the query-by-form page: {@code [{name, path}]}, at most 50, the path being a console search or form address. */
    private ArrayNode savedSearches(JsonNode v) {
        if (!v.isArray() || v.size() > 50) {
            throw bad("savedSearches is a list of at most 50 searches");
        }
        ArrayNode out = json.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode e : v) {
            String name = e.path("name").asText().strip();
            String path = e.path("path").asText();
            if (name.isEmpty() || name.length() > 64 || !path.matches("/(form|s)[/?].{0,1990}")) {
                throw bad("each saved search needs a name (up to 64 characters) and a path starting /form/ or /s?");
            }
            if (seen.add(name.toLowerCase(java.util.Locale.ROOT))) {
                out.addObject().put("name", name).put("path", path);
            }
        }
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
