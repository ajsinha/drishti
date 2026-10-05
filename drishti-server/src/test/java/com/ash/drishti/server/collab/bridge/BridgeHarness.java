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
package com.ash.drishti.server.collab.bridge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.collab.Comment;
import com.ash.drishti.identity.collab.CommentThread;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.FileOutboxStore;
import com.ash.drishti.identity.collab.OutboxStore;
import com.ash.drishti.identity.collab.Pin;
import com.ash.drishti.identity.collab.Share;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.identity.collab.ThreadStore;
import com.ash.drishti.server.collab.LinkBuilder;
import com.ash.drishti.server.collab.PanelTitles;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.collab.RateLimits;
import com.ash.drishti.server.collab.mail.MailContentPolicy;
import com.ash.drishti.server.collab.mail.MailRenderer;
import com.ash.drishti.server.collab.mail.MailTemplates;
import com.ash.drishti.server.collab.mail.OutboxDispatcher;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.server.security.Principal;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Everything a bridge test needs, in process: a fake chat endpoint on a free loopback port (it records what it is sent and answers
 * what the test says), the stores as files in a temp directory, and the real bridge classes over mocked identity and entitlement
 * services. Nothing leaves the machine; no real Teams, Slack or webhook URL appears anywhere.
 */
final class BridgeHarness implements AutoCloseable {

    /** One request the fake endpoint received. */
    record Hit(String method, String path, Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst().map(e -> e.getValue().get(0)).orElse(null);
        }
    }

    static final String SECRET_PATH = "/services/T0SECRET/B0SECRET/xyzSECRETtoken";
    static final String SIGNING_SECRET = "s3cr3t-signing-key";
    /** The value of a masked field that someone typed into a note. */
    static final String MASKED_VALUE = "4815162342";

    final HttpServer endpoint;
    final List<Hit> hits = new CopyOnWriteArrayList<>();
    final AtomicInteger status = new AtomicInteger(200);
    final AtomicReference<String> location = new AtomicReference<>();
    final String base;
    final OutboxStore outbox;
    final List<String> audited = new CopyOnWriteArrayList<>();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-05T09:00:00Z"), ZoneOffset.UTC);
    final ShareStore shares = mock(ShareStore.class);
    final ThreadStore threads = mock(ThreadStore.class);
    final Principals principals = mock(Principals.class);
    final Entitlements entitlements = mock(Entitlements.class);
    final PackAccess packs = mock(PackAccess.class);
    final List<Principal> readers = new ArrayList<>();
    final Map<String, String> env = new java.util.concurrent.ConcurrentHashMap<>();
    CollabProperties props;
    BridgeRegistry registry;
    BridgeItemRenderer renderer;
    BridgeSender sender;
    BridgeNotifier notifier;
    OutboxDispatcher dispatcher;
    SimpleMeterRegistry meters = new SimpleMeterRegistry();

    BridgeHarness() throws IOException {
        endpoint = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        endpoint.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            hits.add(new Hit(ex.getRequestMethod(), ex.getRequestURI().getPath(), Map.copyOf(ex.getRequestHeaders()), body));
            int s = status.get();
            if (location.get() != null) {
                ex.getResponseHeaders().add("Location", location.get());
            }
            ex.sendResponseHeaders(s, -1);
            ex.close();
        });
        endpoint.start();
        base = "http://127.0.0.1:" + endpoint.getAddress().getPort();
        outbox = new FileOutboxStore(Files.createTempDirectory("bridge-outbox"));
        env.put("BRIDGE_URL", base + SECRET_PATH);
        env.put("BRIDGE_SECRET", SIGNING_SECRET);
        when(principals.user(any())).thenAnswer(inv -> {
            User u = mock(User.class);
            when(u.displayName()).thenReturn("Ann Shah");
            return Optional.of(u);
        });
        // masks exactly when the reader's role has no "raw": the bundled viewer has none, a role named raw-reader has it
        when(entitlements.masks(any())).thenAnswer(inv -> {
            Principal p = inv.getArgument(0);
            readers.add(p);
            return !p.roles().contains("raw-reader");
        });
        when(packs.ownerOf(any())).thenAnswer(inv -> "trade".equals(inv.getArgument(0)) ? "market-risk" : "genomics-pack");
    }

    /** Configures one bridge; call before {@link #build()}. */
    BridgeHarness configure(String format, List<CollabProperties.Route> routes, int perMinute, String renderAs) {
        CollabProperties.Webhook w = new CollabProperties.Webhook("desk", format, "BRIDGE_URL", "BRIDGE_SECRET", routes);
        props = new CollabProperties(true, null, null, "https://drishti.example", null, null, null, null, null, null, null, null, null,
                new CollabProperties.Outbox(true, Duration.ofSeconds(1), 20, 3, Duration.ofSeconds(30), Duration.ofSeconds(100), Duration.ofSeconds(60), 30),
                null, null, null,
                new CollabProperties.Bridges(true, renderAs, List.of(base), perMinute, Duration.ofSeconds(5), 500, List.of(w)), null);
        return build();
    }

    /** The documents quotes are filled from; empty unless a test stubs it. */
    final com.ash.drishti.server.collab.thread.PinnedDocs docs = mock(com.ash.drishti.server.collab.thread.PinnedDocs.class);

    private BridgeHarness build() {
        com.ash.drishti.api.DataNode doc = com.ash.drishti.api.DataNode.of(java.util.Map.of("mtm", 1234567, "notional", 5000));
        com.ash.drishti.api.DataNode masked = com.ash.drishti.api.DataNode.of(java.util.Map.of("mtm", com.ash.drishti.api.DataNode.MASK, "notional", 5000));
        when(docs.at(any(), any(), any())).thenReturn(java.util.Optional.of(doc));
        when(entitlements.redact(any(), any())).thenAnswer(inv -> entitlements.masks(inv.getArgument(0)) ? masked : inv.getArgument(1));
        registry = new BridgeRegistry(props, env::get);
        MailContentPolicy policy = new MailContentPolicy(props, packs);
        PanelTitles titles = new PanelTitles((kind, id, who) -> Map.of("cashflows", "Cashflows"), entitlements);
        renderer = new BridgeItemRenderer(shares, threads, principals, entitlements, policy, titles, new LinkBuilder(props.consoleUrl()), props, new com.ash.drishti.server.collab.thread.CommentRenderer(entitlements, docs), "Drishti", clock);
        AuditLog audit = mock(AuditLog.class);
        org.mockito.Mockito.doAnswer(inv -> audited.add(inv.getArgument(0) + "|" + inv.getArgument(1) + "|" + inv.getArgument(2) + "|" + inv.getArgument(3)))
                .when(audit).record(any(), any(), any(), any());
        sender = new BridgeSender(registry, renderer, new BridgeClient(props.bridges().timeout()), new RateLimits(), props, audit, clock, "Drishti");
        notifier = new BridgeNotifier(props, registry, outbox, packs, clock);
        dispatcher = new OutboxDispatcher(outbox, props, principals, List.of(), List.of(sender),
                new MailRenderer(new MailTemplates(""), "Drishti", "x"), (m, f) -> {}, meters, clock);
        return this;
    }

    static CollabProperties.Route route(List<String> packs, List<String> kinds, String... events) {
        return new CollabProperties.Route(packs, kinds, List.of(events));
    }

    /** A share of a trade whose note holds a masked value and a value quote. */
    Share share(String id) {
        String body = "price " + MASKED_VALUE + " looks off, see {$.mtm}";
        Share s = new Share(id, "ann", clock.instant(), "trade", "IRS-48213", "cashflows", null,
                new Pin(LocalDate.parse("2026-09-30"), false, clock.instant(), 3, "demo"), body,
                List.of(new Share.Span(6, 6 + MASKED_VALUE.length())), "in-app", null, null);
        when(shares.find(id)).thenReturn(Optional.of(s));
        return s;
    }

    /** A live comment on a trade thread. */
    Comment comment(String id, String state) {
        CommentThread t = new CommentThread("th_1", "trade", "IRS-48213", CommentThread.PANEL, "cashflows", null, null, "cashflows", CommentThread.OPEN,
                "ann", clock.instant(), clock.instant(), 1);
        Comment c = new Comment(id, "th_1", "ann", clock.instant(), null, 1, new Pin(null, true, clock.instant(), 3, "demo"),
                "check " + MASKED_VALUE + " against {$.notional}", List.of(new Share.Span(6, 6 + MASKED_VALUE.length())), state, null);
        when(threads.comment(id)).thenReturn(Optional.of(c));
        when(threads.thread("th_1")).thenReturn(Optional.of(t));
        return c;
    }

    CommentThread thread() {
        return threads.thread("th_1").orElseThrow();
    }

    /** Everything the endpoint received, as one string (to look for what must never be there). */
    String everythingReceived() {
        StringBuilder b = new StringBuilder();
        hits.forEach(h -> b.append(h.path()).append('\n').append(h.headers()).append('\n').append(h.body()).append('\n'));
        return b.toString();
    }

    @Override
    public void close() {
        endpoint.stop(0);
    }
}
