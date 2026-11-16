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
package com.ash.drishti.server.flight;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.flight.FlightBackend;
import com.ash.drishti.flight.FlightRefusal;
import com.ash.drishti.flight.FlightRequest;
import com.ash.drishti.identity.ApiTokenStore;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.server.admission.AdmissionGate;
import com.ash.drishti.server.admission.Work;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.kernel.api.security.Principal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The server's side of Arrow Flight ({@link FlightBackend}): the token services, the forms over the query layer, busy protection and the
 * audit, reused as they are.
 *
 * <p><b>Who may call</b> (Q3): a personal API token ({@code drk_…}) whose user is enabled, with the scope {@code data:read} (every
 * domain the person may read) or {@code data:read:<domain>}; a token of a user with the {@code service} role (an application) may name the
 * person it acts for in {@code x-drishti-on-behalf-of}, and then that person's roles decide what is read. An embed token (Drishti
 * Elements: an application on behalf of a signed-in person) is accepted with the same scopes. A browser session never.
 */
public final class ServerFlightBackend implements FlightBackend {

    /** The scope that opens every domain the person may read; {@code data:read:<domain>} opens one. */
    public static final String SCOPE = "data:read";

    private final ApiTokenStore tokens;
    private final UserService users;
    private final AuditLog audit;
    private final AdmissionGate gate;
    private final FlightNodes nodes;
    private final Map<FlightRequest.Form, FlightForm> forms = new EnumMap<>(FlightRequest.Form.class);
    private final com.ash.drishti.server.embed.EmbedTokenService embed;

    public ServerFlightBackend(ApiTokenStore tokens, UserService users, AuditLog audit, AdmissionGate gate, FlightNodes nodes, List<FlightForm> forms,
            com.ash.drishti.server.embed.EmbedTokenService embed) {
        this.tokens = tokens;
        this.users = users;
        this.audit = audit;
        this.gate = gate;
        this.nodes = nodes;
        this.embed = embed;
        for (FlightForm f : forms) {
            this.forms.put(f.form(), f);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ who

    @Override
    public Caller authenticate(String token, String onBehalfOf) {
        if (token == null || token.isBlank()) {
            throw new FlightRefusal(ErrorCode.FLIGHT_UNAUTHENTICATED, "no token");
        }
        if (embed != null && com.ash.drishti.server.embed.EmbedTokenService.looksLikeEmbed(token)) {
            try {
                var a = embed.authorize(token, null, "GET", "/api/v1/flight");
                if (onBehalfOf != null && !onBehalfOf.isBlank()) {
                    throw new FlightRefusal(ErrorCode.FLIGHT_UNAUTHENTICATED, "an embed token already acts for its person: send no on-behalf-of");
                }
                return new Caller(a.principal().user(), null, "embed:" + a.app() + ":" + a.jti(), Set.copyOf(a.principal().roles()),
                        Set.copyOf(a.scopes()));
            } catch (com.ash.drishti.server.embed.EmbedException e) {
                throw new FlightRefusal(ErrorCode.FLIGHT_UNAUTHENTICATED, "the embed token was refused: " + e.getMessage());
            }
        }
        if (!ApiTokenStore.looksLikeToken(token)) {
            throw new FlightRefusal(ErrorCode.FLIGHT_UNAUTHENTICATED, "Flight takes a personal API token (My account → API tokens) or an embed token, "
                    + "never a browser session");
        }
        ApiTokenStore.Verified v = tokens.verify(token)
                .orElseThrow(() -> new FlightRefusal(ErrorCode.FLIGHT_UNAUTHENTICATED, "the API token is unknown, revoked or expired"));
        User u = users.find(v.user()).filter(User::enabled)
                .orElseThrow(() -> new FlightRefusal(ErrorCode.FLIGHT_UNAUTHENTICATED, "the account of this token does not exist or is disabled"));
        Set<String> scopes = Set.copyOf(v.scopes());
        if (scopes.stream().noneMatch(s -> s.equals(SCOPE) || s.startsWith(SCOPE + ":"))) {
            throw new FlightRefusal(ErrorCode.FLIGHT_SCOPE, "this token lacks the scope data:read (or data:read:<domain>): make a token with it "
                    + "on My account → API tokens");
        }
        if (onBehalfOf != null && !onBehalfOf.isBlank()) {
            if (!u.roles().contains(Entitlements.SERVICE)) {
                throw new FlightRefusal(ErrorCode.FLIGHT_SCOPE, "only an application's token (a user with the service role) may act on behalf of "
                        + "a person");
            }
            User person = users.find(onBehalfOf.trim()).filter(User::enabled)
                    .orElseThrow(() -> new FlightRefusal(ErrorCode.FLIGHT_UNAUTHENTICATED, "the person " + onBehalfOf + " does not exist or is disabled"));
            return new Caller(u.username(), person.username(), v.id(), Set.copyOf(person.roles()), scopes);
        }
        return new Caller(u.username(), null, v.id(), Set.copyOf(u.roles()), scopes);
    }

    /** The person whose rights a read uses. */
    static Principal principal(Caller c) {
        return new Principal(c.effective(), List.copyOf(c.roles()));
    }

    /** The forms served here: the server's, then a loaded module's (its own engine's form wins over none). */
    private Map<FlightRequest.Form, FlightForm> served() {
        List<FlightForm> live = com.ash.drishti.common.Extensions.of(FlightForm.class);
        if (live.isEmpty()) {
            return forms;
        }
        Map<FlightRequest.Form, FlightForm> out = new EnumMap<>(forms);
        live.forEach(f -> out.put(f.form(), f));
        return out;
    }

    private FlightForm form(FlightRequest r) {
        FlightForm f = served().get(r.form());
        if (f == null) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "form " + r.form().name().toLowerCase(java.util.Locale.ROOT)
                    + " is not served on this node (its engine is off)");
        }
        return f;
    }

    /** Refuses a request the token's scopes do not open. */
    private void scope(Caller c, Principal p, FlightForm f, FlightRequest r) {
        if (c.scopes().contains(SCOPE) || c.scopes().stream().anyMatch(s -> s.equals("data:read:*"))) {
            return;
        }
        String domain = f.domain(p, r);
        if (!c.scopes().contains(SCOPE + ":" + domain)) {
            throw new FlightRefusal(ErrorCode.FLIGHT_SCOPE, "this token opens " + String.join(", ", c.scopes().stream().filter(s -> s.startsWith(SCOPE)).sorted().toList())
                    + ", not data:read:" + domain);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ reads

    @Override
    public Plan plan(Caller caller, FlightRequest request) {
        Principal p = principal(caller);
        FlightForm f = form(request);
        try {
            scope(caller, p, f, request);
            return f.plan(p, request, nodes);
        } catch (DrishtiException e) {
            throw FlightBackends.refusal(e);
        }
    }

    @Override
    public BatchSource open(Caller caller, FlightRequest request, int part) {
        Principal p = principal(caller);
        FlightForm f = form(request);
        try {
            scope(caller, p, f, request);
            return f.open(p, request, part, batchRows);
        } catch (DrishtiException e) {
            throw FlightBackends.refusal(e);
        }
    }

    @Override
    public Subscription subscribe(Caller caller, FlightRequest request, int part) {
        Principal p = principal(caller);
        FlightForm f = form(request);
        try {
            scope(caller, p, f, request);
            return f.subscribe(p, request, part, batchRows);
        } catch (DrishtiException e) {
            throw FlightBackends.refusal(e);
        }
    }

    private volatile int batchRows = 65_536;

    /** The rows of one batch (the engine file's {@code batch-rows}). */
    public ServerFlightBackend batchRows(int rows) {
        this.batchRows = Math.max(1, rows);
        return this;
    }

    @Override
    public AutoCloseable admit(Caller caller, FlightRequest request) {
        if (gate != null) {
            try {
                gate.admit(Work.FLIGHT, caller.effective(), "Flight " + request.form().name().toLowerCase(java.util.Locale.ROOT));
            } catch (DrishtiException e) {
                throw FlightBackends.refusal(e);
            }
        }
        return () -> { };
    }

    @Override
    public List<FlightRequest> catalogue(Caller caller) {
        Principal p = principal(caller);
        Set<FlightRequest> out = new LinkedHashSet<>();
        for (FlightForm f : served().values()) {
            try {
                for (FlightRequest r : f.catalogue(p)) {
                    try {
                        scope(caller, p, f, r);
                        out.add(r);
                    } catch (FlightRefusal | DrishtiException e) {
                        // not opened by this token: not listed
                    }
                }
            } catch (DrishtiException e) {
                // a form this person cannot use lists nothing
            }
        }
        return new ArrayList<>(out);
    }

    @Override
    public void audit(Audit a) {
        if (audit == null) {
            return;
        }
        String detail = "form=" + a.form() + "; part=" + a.part() + (a.stream() ? "; stream" : "") + "; rows=" + a.rows() + "; bytes=" + a.bytes()
                + "; ms=" + a.millis() + "; node=" + a.node() + "; token=" + a.tokenId()
                + (a.onBehalfOf() == null ? "" : "; for=" + a.onBehalfOf()) + (a.refusal() == null ? "" : "; refused=" + a.refusal())
                + "; request=" + cut(a.request(), 2000);
        audit.record(a.person(), a.refusal() == null ? "data.flight" : "data.flight-refused", a.onBehalfOf() == null ? a.person() : a.onBehalfOf(), detail);
    }

    @Override
    public String nodeId() {
        return nodes.selfId();
    }

    public FlightNodes nodes() {
        return nodes;
    }

    /** The forms served here, for Admin → Data out. */
    public List<String> forms() {
        return served().keySet().stream().map(f -> f.name().toLowerCase(java.util.Locale.ROOT)).toList();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
