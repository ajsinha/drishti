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
package com.ash.drishti.server.ui;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.rachana.ui.BoardCheck;
import com.ash.drishti.rachana.ui.BoardCheckContext;
import com.ash.drishti.rachana.ui.BoardDoc;
import com.ash.drishti.rachana.ui.ChipCatalog;
import com.ash.drishti.rachana.ui.ChipDoc;
import com.ash.drishti.rachana.ui.UiDocuments;
import com.ash.drishti.rachana.ui.UiProblem;
import com.ash.drishti.server.bi.runs.RunProblem;
import com.ash.drishti.server.security.Principal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Renders boards (docs/architecture/UI_BOARD.md section 6): the board check first (DRS-2071 with the problems, DRS-2075 for a size limit), the powers
 * (DRS-2074), the per-person limits (DRS-2076), then a {@link BoardRender} session per render, ended by its owner, by idle, or when the person opens more
 * than the session limit (the oldest ends, DRS-2079). One audit row per render, with no values.
 */
public final class RenderService implements AutoCloseable {

    /** A render running: its session and its first pass. */
    public record Started(BoardRender render, CompletableFuture<BoardRender.Outcome> done) {}

    /** One open session: the render and how its stream is ended. */
    private record Session(BoardRender render, BoardRender.Sink sink, Runnable end) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ChipStore chips;
    private final BoardStore boards;
    private final UiAccess access;
    private final UiSettings ui;
    private final RenderSettings settings;
    private final BoardSuppliers suppliers;
    private final IdTokens tokens;
    private final Executor exec;
    private final Path dynamicDir;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> painting = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;

    public RenderService(ChipStore chips, BoardStore boards, UiAccess access, UiSettings ui, RenderSettings settings, BoardSuppliers suppliers,
            IdTokens tokens, Executor exec, Path dynamicDir) {
        this.chips = chips;
        this.boards = boards;
        this.access = access;
        this.ui = ui;
        this.settings = settings;
        this.suppliers = suppliers;
        this.tokens = tokens;
        this.exec = exec;
        this.dynamicDir = dynamicDir;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "drishti-ui-render-sweep");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(this::sweep, 30, 30, TimeUnit.SECONDS);
    }

    public RenderSettings settings() {
        return settings;
    }

    // ---- starting ------------------------------------------------------------------------------------------------------

    /** {@code POST /ui/render}: an unsaved board document. */
    public Started dynamic(Principal p, String text, Map<String, Object> params, String device, BoardRender.Sink sink, Runnable end) {
        if (!access.mayRenderDynamic(p)) {
            throw new DrishtiException(ErrorCode.UI_FORBIDDEN, p.embedApp() != null
                    ? "an embedded application renders saved boards only (UI_BOARD.md 14.1 item 8)"
                    : p.user() + " may not render an unsaved board (the ui.board.render-dynamic power)");
        }
        admit(p);
        boolean counted = false;
        try {
            String hash = sha(text);
            BoardDoc doc = checked(text, new BoardCheckContext(chips.then(chips.draftsOf(p)), null, settings.checkLimits(), false, false, null, false));
            keep(hash, text);
            AtomicInteger n = painting.computeIfAbsent(p.user(), u -> new AtomicInteger());
            n.incrementAndGet();
            counted = true;
            Started s = start(p, doc, "dynamic:sha256:" + hash, hash, params, device, sink, end, true);
            s.done().whenComplete((o, e) -> n.decrementAndGet());
            return s;
        } catch (RuntimeException e) {
            if (counted) {
                painting.get(p.user()).decrementAndGet();
            }
            throw e;
        }
    }

    /** {@code GET /ui/boards/{name}/render}: a saved board the caller may open (DRS-2073 otherwise); embed tokens may. */
    public Started saved(Principal p, BoardStore.Ref ref, Map<String, Object> params, String device, BoardRender.Sink sink, Runnable end) {
        if (!access.mayView(p)) {
            throw new DrishtiException(ErrorCode.UI_FORBIDDEN, p.user() + " may not open boards");
        }
        BoardStore.Saved saved = boards.get(ref, p);
        boolean shared = BoardStore.WORKSPACE.equals(ref.scope());
        ChipCatalog catalog = shared ? chips : chips.then(chips.draftsOf(p));
        BoardDoc doc = checked(saved.text(), new BoardCheckContext(catalog, null, settings.checkLimits(), shared,
                shared && ui.certifiedOnly().contains(ref.space()), null, false));
        return start(p, doc, ref.id(), sha(saved.text()), params, device, sink, end, false);
    }

    private BoardDoc checked(String text, BoardCheckContext ctx) {
        int bytes = text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > settings.bytes()) {
            throw new RunProblem(ErrorCode.UI_TOO_LARGE, "the board is " + bytes + " bytes, over the limit of " + settings.bytes(), Map.of());
        }
        UiDocuments.Read<BoardDoc> read = UiDocuments.parseBoard(text);
        if (read.doc() == null || BoardCheck.failed(read.problems())) {
            throw new RunProblem(ErrorCode.UI_DOCUMENT_INVALID, "the board document cannot be read", Map.of("problems", shown(read.problems())));
        }
        List<UiProblem> problems = BoardCheck.check(read.doc(), ctx);
        List<UiProblem> sizes = problems.stream().filter(u -> u.error() && "BC-09".equals(u.rule())).toList();
        if (!sizes.isEmpty()) {
            throw new RunProblem(ErrorCode.UI_TOO_LARGE, sizes.get(0).message(), Map.of("problems", shown(sizes)));
        }
        if (BoardCheck.failed(problems)) {
            throw new RunProblem(ErrorCode.UI_CHECK_FAILED, "the board check failed", Map.of("problems", shown(problems)));
        }
        return read.doc();
    }

    private static List<Map<String, Object>> shown(List<UiProblem> problems) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UiProblem u : problems) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rule", u.rule());
            m.put("level", u.level().name().toLowerCase(java.util.Locale.ROOT));
            m.put("path", u.path());
            m.put("message", u.message());
            if (u.fix() != null) {
                m.put("fix", u.fix());
            }
            out.add(m);
        }
        return out;
    }

    /** 30 dynamic renders a minute and 4 at once per person (configurable): DRS-2076 with Retry-After past either. */
    private void admit(Principal p) {
        long now = System.currentTimeMillis();
        Deque<Long> d = recent.computeIfAbsent(p.user(), u -> new ArrayDeque<>());
        synchronized (d) {
            while (!d.isEmpty() && d.peekFirst() <= now - 60_000) {
                d.pollFirst();
            }
            if (d.size() >= settings.dynamicPerMinute()) {
                long wait = Math.max(1, (d.peekFirst() + 60_000 - now + 999) / 1000);
                throw new RunProblem(ErrorCode.UI_RATE_LIMITED, "over " + settings.dynamicPerMinute() + " unsaved renders a minute; try again in " + wait + " s",
                        Map.of(), wait);
            }
            AtomicInteger n = painting.get(p.user());
            if (n != null && n.get() >= settings.dynamicAtOnce()) {
                throw new RunProblem(ErrorCode.UI_RATE_LIMITED, "over " + settings.dynamicAtOnce() + " unsaved renders painting at once", Map.of(), 1);
            }
            d.addLast(now);
        }
    }

    private Started start(Principal p, BoardDoc doc, String key, String hash, Map<String, Object> params, String device, BoardRender.Sink sink,
            Runnable end, boolean dynamic) {
        Map<String, ChipDoc> resolved = new LinkedHashMap<>();
        Map<com.ash.drishti.rachana.ui.ChipRef, ChipDoc> local = new LinkedHashMap<>();
        doc.localChips().forEach(c -> local.put(c.ref(), c));
        ChipCatalog catalog = chips.then(chips.draftsOf(p));
        doc.chips().forEach((n, i) -> resolved.put(n, local.containsKey(i.use()) ? local.get(i.use())
                : catalog.find(i.use()).map(ChipCatalog.Entry::doc).orElseThrow(() -> new DrishtiException(ErrorCode.UI_CHIP_NOT_FOUND, "no chip " + i.useText()))));
        byte[] b = new byte[12];
        RANDOM.nextBytes(b);
        String id = "rnd-" + HexFormat.of().formatHex(b);
        BoardRender r = new BoardRender(id, p, doc, key, hash, resolved, params, device, suppliers, tokens, settings, exec, sink);
        openSession(p, new Session(r, sink, end));
        long t0 = System.nanoTime();
        CompletableFuture<BoardRender.Outcome> done = r.start().whenComplete((o, e) -> audit(p, dynamic, r, doc, o, (System.nanoTime() - t0) / 1_000_000));
        return new Started(r, done);
    }

    private void openSession(Principal p, Session s) {
        synchronized (sessions) {
            List<Session> mine = new ArrayList<>(sessions.values().stream().filter(x -> x.render.owner().user().equals(p.user()))
                    .sorted(java.util.Comparator.comparingLong(x -> x.render.lastUsed())).toList());
            while (mine.size() >= settings.sessions()) {
                Session oldest = mine.remove(0);
                end(oldest, "more than " + settings.sessions() + " renders open");
            }
            sessions.put(s.render.id(), s);
        }
    }

    /** One audit row: who, what, the board (its id or its document's hash), the chips and versions, the suppliers read, duration, failures. No values. */
    private void audit(Principal p, boolean dynamic, BoardRender r, BoardDoc doc, BoardRender.Outcome o, long ms) {
        StringBuilder chipList = new StringBuilder();
        doc.chips().forEach((n, i) -> chipList.append(chipList.isEmpty() ? "" : ",").append(n).append('=').append(i.useText()));
        String detail = "chips=" + chipList + "; suppliers=" + String.join(",", new java.util.TreeSet<>(r.suppliersRead())) + "; ms=" + ms
                + "; failed=" + (o == null ? "?" : o.errors());
        access.audit(p, dynamic ? "ui.render.dynamic" : "ui.render", r.boardKey(), detail);
    }

    /** Keeps a dynamic document by its hash for an auditor ({@code keep-dynamic}, 7 days), pruning older ones. */
    private void keep(String hash, String text) {
        if (dynamicDir == null || settings.keepDynamic().isZero()) {
            return;
        }
        try {
            Files.createDirectories(dynamicDir);
            Path f = dynamicDir.resolve(hash + ".board");
            if (!Files.exists(f)) {
                Files.writeString(f, text);
            }
            long cutoff = System.currentTimeMillis() - settings.keepDynamic().toMillis();
            try (var s = Files.list(dynamicDir)) {
                s.filter(x -> {
                    try {
                        return Files.getLastModifiedTime(x).toMillis() < cutoff;
                    } catch (IOException e) {
                        return false;
                    }
                }).forEach(x -> x.toFile().delete());
            }
        } catch (IOException e) {
            // keeping is for the auditor's convenience; a full disk does not stop a render
        }
    }

    // ---- a session's life -----------------------------------------------------------------------------------------------

    /** {@code POST /ui/renders/{id}/pins}: the owner's pin change; the chips downstream are painted again on the session's stream. */
    public CompletableFuture<BoardRender.Outcome> pin(Principal p, String renderId, String instance, String pin, Object value) {
        return session(p, renderId).render.pin(instance, pin, value);
    }

    /** {@code DELETE /ui/renders/{id}}. */
    public void end(Principal p, String renderId) {
        end(session(p, renderId), "ended by its owner");
    }

    private Session session(Principal p, String renderId) {
        Session s = sessions.get(renderId);
        if (s == null || s.render.closed()) {
            throw new DrishtiException(ErrorCode.UI_RENDER_ENDED, "the render " + renderId + " has ended; render again");
        }
        if (!s.render.owner().user().equals(p.user())) {
            throw new DrishtiException(ErrorCode.UI_FORBIDDEN, "the render " + renderId + " is not yours");
        }
        return s;
    }

    /** Ends a session whatever its state (the stream closed, an error). */
    public void forget(String renderId) {
        Session s = sessions.remove(renderId);
        if (s != null) {
            s.render.close();
        }
    }

    private void end(Session s, String why) {
        sessions.remove(s.render.id());
        if (!s.render.closed()) {
            s.render.close();
            try {
                s.sink.event("ended", Map.of("code", ErrorCode.UI_RENDER_ENDED.code(), "message", why));
            } catch (RuntimeException e) {
                // the stream is already gone
            }
            if (s.end != null) {
                s.end.run();
            }
        }
    }

    void sweep() {
        long cutoff = System.currentTimeMillis() - settings.idle().toMillis();
        for (Session s : List.copyOf(sessions.values())) {
            if (s.render.lastUsed() < cutoff) {
                end(s, "idle for " + settings.idle().toMinutes() + " min");
            }
        }
    }

    /** Open sessions (tests, health). */
    public int open() {
        return sessions.size();
    }

    @Override
    public void close() {
        sweeper.shutdownNow();
        List.copyOf(sessions.values()).forEach(s -> end(s, "the server is stopping"));
    }

    static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
