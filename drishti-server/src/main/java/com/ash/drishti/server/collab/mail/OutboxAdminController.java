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
package com.ash.drishti.server.collab.mail;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.OutboxItem;
import com.ash.drishti.identity.collab.OutboxStore;
import com.ash.drishti.server.collab.Notifier;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin: the outbox (pending, failed, dead letters, with the reason and the attempts, never the message), sending a dead letter again,
 * and a test mail to the caller's own address. {@code admin} only. Rows say who, which template and which share; no content.
 */
@RestController
@RequestMapping("/api/v1/admin/collab")
public class OutboxAdminController {

    private final OutboxStore store;
    private final OutboxDispatcher dispatcher;
    private final Entitlements entitlements;
    private final Principals principals;
    private final CollabProperties props;
    private final java.util.List<Notifier> notifiers;
    private final String product;

    public OutboxAdminController(OutboxStore store, OutboxDispatcher dispatcher, Entitlements entitlements, Principals principals,
            CollabProperties props, List<Notifier> notifiers,
            @org.springframework.beans.factory.annotation.Value("${drishti.branding.product:Drishti}") String product) {
        this.store = store;
        this.dispatcher = dispatcher;
        this.entitlements = entitlements;
        this.principals = principals;
        this.props = props;
        this.notifiers = notifiers;
        this.product = product;
    }

    @GetMapping("/outbox")
    public Map<String, Object> outbox(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "100") int limit) {
        entitlements.requireAdmin(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", props.email().enabled());
        out.put("available", notifiers.stream().anyMatch(n -> "email".equals(n.channel()) && n.available()));
        out.put("dispatching", props.outbox().enabled());
        out.put("counts", store.counts());
        out.put("items", store.list(state, Math.min(Math.max(1, limit), 500)).stream().map(OutboxAdminController::row).toList());
        return out;
    }

    @PostMapping("/outbox/{seq}/retry")
    public Map<String, Object> retry(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable long seq) {
        entitlements.requireAdmin(p);
        if (!store.requeue(seq, Instant.now())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "delivery " + seq + " is not a dead letter or cancelled; only those can be sent again");
        }
        return row(store.find(seq).orElseThrow());
    }

    /** Sends a test message to the caller's own address now and says whether the server accepted it. */
    @PostMapping("/mail-test")
    public Map<String, Object> mailTest(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        if (notifiers.stream().noneMatch(n -> "email".equals(n.channel()) && n.available())) {
            throw new DrishtiException(ErrorCode.MAIL_UNAVAILABLE,
                    "email is off: set drishti.collab.email.enabled, spring.mail.host and drishti.collab.console-url");
        }
        String address = principals.user(p.user()).map(User::email).orElse(null);
        dispatcher.sendTest(address, product, props.consoleUrl(), p.user());
        return Map.of("sent", true, "to", address);
    }

    private static Map<String, Object> row(OutboxItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", i.seq());
        m.put("channel", i.channel());
        m.put("recipient", i.recipient());
        m.put("template", i.template());
        m.put("ref", i.refId());
        m.put("state", i.state());
        m.put("attempts", i.attempts());
        m.put("nextAt", i.nextAt());
        m.put("lastError", i.lastError());
        m.put("createdAt", i.createdAt());
        m.put("sentAt", i.sentAt());
        return m;
    }
}
