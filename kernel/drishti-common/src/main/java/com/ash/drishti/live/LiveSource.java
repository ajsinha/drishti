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
package com.ash.drishti.live;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.api.live.LiveTransport;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * A live connector as the router sees it (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 7): a messaging plugin
 * ({@link LiveTransport}) with the kernel's {@link LiveFeed} and {@link LiveState} behind it. The plugin connects, waits calmly for its
 * broker, receives and writes back; every read (a record, a kind's rows, a search, the ticks of open views and live sets) is answered
 * here from the one live state, the same for every transport. The kernel wraps every plugin that is a transport in one of these.
 *
 * <p>The connector's settings read here: {@code cache-mb} (256: the documents held in memory; past it they are read back from the
 * transport), {@code disk-cache.enabled} and its {@code dir}, {@code root}, {@code max-gb}, {@code reset-at}, {@code zone} (the day's rows
 * on local disk), {@code rejects.keep-days} (7), and the decode settings of {@link LiveFeed}.
 */
public final class LiveSource implements SourcePlugin {

    private static final System.Logger LOG = System.getLogger(LiveSource.class.getName());

    private final SourcePlugin plugin;
    private final LiveTransport transport;
    private final KindContracts contracts;
    private final Set<String> kinds;
    private final UnaryOperator<EntityDocument> shape;
    private final LiveDates dates;
    private volatile LiveFeed feed;
    private volatile LiveState state;
    private volatile ScheduledFuture<?> expiry;
    private volatile ScheduledFuture<?> landings;
    private volatile Landing landing = Landing.NONE;

    /** Where this connector's landed kinds go: the server's lake writer (LIVE_CONNECTORS_DESIGN.md section 2.4). */
    public void landing(Landing l) {
        landing = l == null ? Landing.NONE : l;
    }
    private volatile String name;

    /**
     * @param plugin    the plugin: a {@link LiveTransport}
     * @param contracts the loaded kinds' contracts
     * @param kinds     the kinds the connector serves (empty: what its messages name)
     * @param shape     the connector's where/set/map, applied as rows arrive (null: none)
     */
    public LiveSource(SourcePlugin plugin, KindContracts contracts, Set<String> kinds, UnaryOperator<EntityDocument> shape) {
        this(plugin, contracts, kinds, shape, LiveDates.NONE);
    }

    /** As above, the live date from {@code dates} (LIVE_CONNECTORS_DESIGN.md section 5). */
    public LiveSource(SourcePlugin plugin, KindContracts contracts, Set<String> kinds, UnaryOperator<EntityDocument> shape, LiveDates dates) {
        this.dates = dates == null ? LiveDates.NONE : dates;
        if (!(plugin instanceof LiveTransport t)) {
            throw new IllegalArgumentException(plugin.manifest().name() + " is not a live transport");
        }
        this.plugin = plugin;
        this.transport = t;
        this.contracts = contracts == null ? KindContracts.NONE : contracts;
        this.kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
        this.shape = shape;
    }

    /** The plugin this connector runs (its transport). */
    public SourcePlugin plugin() {
        return plugin;
    }

    /** The feed, once started (null before). */
    public LiveFeed feed() {
        return feed;
    }

    /** Whether the transport replays what its broker holds at start (a topic) or delivers each message once (a queue). */
    public boolean replayable() {
        return transport.replayable();
    }

    /**
     * An administrator's pause (LIVE_CONNECTORS_DESIGN.md section 7.9): a queue's messages wait at the broker (not acknowledged); a
     * replayable topic's are skipped and counted, read again at the next start. False when the feed has not started.
     */
    public boolean pause(String by) {
        LiveFeed f = feed;
        if (f == null) {
            return false;
        }
        f.pause(transport.replayable(), by);
        return true;
    }

    /** Takes messages again after a pause; false when the feed has not started. */
    public boolean resume() {
        LiveFeed f = feed;
        if (f == null) {
            return false;
        }
        f.resume();
        return true;
    }

    /** The feed's state (section 7.9); {@code stale} from its {@code stale-after}, rejects over {@code rejectRatio} make it degraded. */
    public FeedState state(boolean stale, double rejectRatio) {
        LiveFeed f = feed;
        String h = health();
        if (f == null) {
            return h != null && h.startsWith("WAITING") ? FeedState.WAITING : h != null && h.startsWith("DOWN") ? FeedState.DOWN : FeedState.CATCHING_UP;
        }
        return f.state(h, stale, rejectRatio);
    }

    @Override
    public PluginManifest manifest() {
        PluginManifest m = plugin.manifest();
        Set<String> served = new HashSet<>(kinds.isEmpty() ? m.kinds() : kinds);
        LiveFeed f = feed;
        if (kinds.isEmpty() && f != null) {
            served.addAll(f.heldKinds());
        }
        return new PluginManifest(name == null ? m.name() : name, m.version(), served, new SourceCapabilities(true, false, true, false));
    }

    @Override
    public void start(SourceContext ctx) throws Exception {
        plugin.start(ctx);
        this.name = ctx.setting("source-name", plugin.manifest().name());
        long cacheBytes = Long.parseLong(ctx.setting("cache-mb", "256").strip()) * 1024 * 1024;
        LiveState s = transport.replayable() ? new LiveState(cacheBytes, diskCache(ctx, name))
                : new LiveState(cacheBytes, durableStore(ctx, name), true);   // a queue sends nothing again: the state is kept on disk
        RejectLog rejects = new RejectLog(under("live/rejects", name), Integer.parseInt(ctx.setting("rejects.keep-days", "7").strip()), Clock.systemUTC());
        LiveFeed f = new LiveFeed(name, kinds, ctx.settings(), contracts, shape, bytes -> {
            try {
                return ctx.parseJson(new ByteArrayInputStream(bytes));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, s, rejects, dates, new LiveCheckpoints(under("live/checkpoints", name + ".json")), under("live/reconcile", null));
        s.reload(e -> f.reread(e, transport::readBack));
        f.lander(new LiveLander(name, ctx.settings(), b -> landing.land(b), Clock.systemUTC()));
        this.state = s;
        this.feed = f;
        transport.current(this::fetchQuietly);
        expiry = ctx.scheduler().scheduleWithFixedDelay(() -> {
            try {
                f.expire(Instant.now());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, name + ": live state expiry: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.MINUTES);
        landings = ctx.scheduler().scheduleWithFixedDelay(() -> {
            try {
                f.landIfDue();                                         // a window due (its age, its keys, "apply now") becomes a batch
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, name + ": a landing failed: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);
        if (!transport.replayable()) {
            f.caughtUp();                                              // a queue replays nothing: what the durable state holds is all there is
        }
        transport.receive(f);
    }

    /** {@code <data folder>/<sub>[/<name>]}; null when the data folder is not absolute or not named (kept in memory only). */
    private static Path under(String sub, String name) {
        try {
            Path root = Path.of(com.ash.drishti.api.DataDir.under(sub));
            return !root.isAbsolute() ? null : name == null ? root : root.resolve(name);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /**
     * The durable store of a transport that cannot replay (LIVE_CONNECTORS_DESIGN.md section 10: what the queue connectors' own state store
     * was): {@code disk-cache.dir}, else {@code <data folder>/state/<connector>} (the folder an earlier release's queue connector kept its
     * state in, read back in its old form once), {@code disk-cache.max-gb} (10), {@code disk-cache.durability} ({@code sync}: on disk
     * before a message is acknowledged). Never cleared by a schedule. The former {@code state.*} settings are not read (said once).
     */
    private static com.ash.drishti.diskcache.DiskCache durableStore(SourceContext ctx, String name) throws IOException {
        if (ctx.settings().keySet().stream().anyMatch(k -> k.startsWith("state."))) {
            LOG.log(System.Logger.Level.INFO, name + ": the state.* settings are no longer read: the live state is kept on disk with disk-cache.dir, "
                    + "disk-cache.max-gb and disk-cache.durability (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 10)");
        }
        String dir = ctx.setting("disk-cache.dir", com.ash.drishti.api.DataDir.under("state") + "/" + name);
        return new com.ash.drishti.diskcache.DiskCache(Path.of(dir), (long) (Double.parseDouble(ctx.setting("disk-cache.max-gb", "10")) * 1024 * 1024 * 1024),
                null, java.time.ZoneId.of(ctx.setting("disk-cache.zone", "America/New_York")), ctx.scheduler(), Clock.systemUTC(), true,
                com.ash.drishti.diskcache.DiskCache.Durability.parse(ctx.setting("disk-cache.durability", "sync")));
    }

    private static com.ash.drishti.diskcache.DiskCache diskCache(SourceContext ctx, String name) throws IOException {
        if (!Boolean.parseBoolean(ctx.setting("disk-cache.enabled", "false").strip())) {
            return null;
        }
        String dir = ctx.setting("disk-cache.dir", ctx.setting("disk-cache.root", com.ash.drishti.api.DataDir.under("cache")) + "/" + name);
        String at = ctx.setting("disk-cache.reset-at", "02:00").strip();
        return new com.ash.drishti.diskcache.DiskCache(Path.of(dir),
                (long) (Double.parseDouble(ctx.setting("disk-cache.max-gb", "10")) * 1024 * 1024 * 1024),
                at.isBlank() || "never".equals(at) ? null : java.time.LocalTime.parse(at),
                java.time.ZoneId.of(ctx.setting("disk-cache.zone", "America/New_York")), ctx.scheduler(), Clock.systemUTC());
    }

    private Optional<EntityDocument> fetchQuietly(EntityRef ref) {
        LiveFeed f = feed;
        return f == null ? Optional.empty() : f.fetch(ref);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) {
        return fetchQuietly(ref);
    }

    /** The live state's row of {@code ref}, a delete included; empty when the feed has not seen it. */
    public Optional<LiveState.Entry> entry(EntityRef ref) {
        LiveState s = state;
        return s == null ? Optional.empty() : s.entry(ref.kind(), ref.id());
    }

    @Override
    public boolean documents(String kind, AsOf asOf, Consumer<EntityDocument> sink) {
        LiveFeed f = feed;
        if (f == null) {
            return false;
        }
        f.documents(kind, sink);
        return true;
    }

    @Override
    public Subscription subscribe(EntityRef ref, Consumer<EntityDocument> listener) {
        LiveFeed f = feed;
        return f == null ? Subscription.NONE : f.subscribe(ref, listener);
    }

    @Override
    public Subscription subscribeKind(String kind, Consumer<EntityDocument> listener) {
        LiveFeed f = feed;
        return f == null ? Subscription.NONE : f.subscribeKind(kind, listener);
    }

    /** Every row of a kind this connector serves ticks from its feed, also when a store answers the read (a companion over a lake). */
    @Override
    public boolean pushes(EntityRef ref) {
        return manifest().kinds().contains(ref.kind());
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        LiveFeed f = feed;
        return f == null ? List.of() : f.search(kind, text, limit);
    }

    @Override
    public Instant lastUpdate() {
        LiveFeed f = feed;
        return f == null ? null : f.lastMessage();
    }

    @Override
    public String health() {
        String h = plugin.health();
        LiveFeed f = feed;
        if (f != null && f.notKept() != null) {
            return "DOWN: " + f.notKept() + " (messages are not acknowledged and come again)";
        }
        if (f != null && !f.isCaughtUp() && h != null && h.startsWith("UP") && !h.contains("catching up")) {
            return "UP (catching up)";
        }
        return h;
    }

    @Override
    public Map<String, Object> cacheStats() {
        Map<String, Object> out = new LinkedHashMap<>(plugin.cacheStats());
        LiveFeed f = feed;
        if (f != null) {
            out.put("live", f.stats());
            out.put("indexedEntities", f.stats().get("rows"));
        }
        return out;
    }

    @Override
    public void purgeCaches() {
        LiveState s = state;
        if (s != null) {
            s.purge();
        }
        plugin.purgeCaches();
    }

    @Override
    public Optional<String> writeKey() {
        return plugin.writeKey();
    }

    @Override
    public List<String> checkWrite(String command, Map<String, Object> block, Set<String> known) {
        return plugin.checkWrite(command, block, known);
    }

    @Override
    public com.ash.drishti.api.write.WriteResult write(com.ash.drishti.api.write.WriteCommand command) throws Exception {
        return plugin.write(command);
    }

    @Override
    public void close() {
        ScheduledFuture<?> e = expiry;
        if (e != null) {
            e.cancel(false);
        }
        ScheduledFuture<?> l = landings;
        if (l != null) {
            l.cancel(false);
        }
        LiveFeed f = feed;
        if (f != null && f.lander() != null) {
            try {
                f.lander().applyNow();
                f.landIfDue();                                         // what waits is landed before the connector goes, not lost
            } catch (RuntimeException x) {
                LOG.log(System.Logger.Level.WARNING, name + ": the window waiting at close was not landed: " + x.getMessage());
            }
        }
        plugin.close();
        LiveState s = state;
        if (s != null) {
            s.close();
        }
    }
}
