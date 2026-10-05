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
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.security.Principal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends what the outbox holds. Every {@code outbox.tick} it claims up to {@code batch} due rows under a lease (so any number of servers
 * may run it and none sends a row twice, except after a crash between send and record, when the fixed {@code Message-ID} lets mail
 * clients collapse the duplicate), renders each for the recipient's rights now, and sends. A transient failure retries with exponential
 * backoff ({@code backoff}, doubling, capped at {@code max-backoff}); a permanent one (an SMTP 5xx, a refused address) or the
 * {@code max-attempts}th failure makes a dead letter, visible to administrators, who can send it again. A row whose recipient has no
 * address, was disabled, can no longer reach the entity, or turned the mail off is cancelled, not sent.
 */
public final class OutboxDispatcher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final Pattern ADDRESS = Pattern.compile("^[^\\s,;<>\"()\\[\\]\\\\]+@[^\\s,;<>\"()\\[\\]\\\\@]+$");

    private final OutboxStore store;
    private final CollabProperties props;
    private final Principals principals;
    private final Map<String, ItemRenderer> renderers;
    private final Map<String, OutboxChannel> channels;
    private final MailRenderer renderer;
    private final MailTransport transport;
    private final Clock clock;
    private final String serverId = "srv-" + UUID.randomUUID().toString().substring(0, 8);
    private final Counter sent;
    private final Counter retried;
    private final Counter dead;
    private final Counter cancelled;
    private final AtomicLong pendingGauge = new AtomicLong();
    private final AtomicLong deadGauge = new AtomicLong();
    private volatile Instant lastPurge = Instant.EPOCH;
    private ScheduledExecutorService loop;

    public OutboxDispatcher(OutboxStore store, CollabProperties props, Principals principals, List<ItemRenderer> renderers, MailRenderer renderer,
            MailTransport transport, MeterRegistry meters, Clock clock) {
        this(store, props, principals, renderers, List.of(), renderer, transport, meters, clock);
    }

    @SuppressWarnings("java:S107")
    public OutboxDispatcher(OutboxStore store, CollabProperties props, Principals principals, List<ItemRenderer> renderers,
            List<OutboxChannel> channels, MailRenderer renderer, MailTransport transport, MeterRegistry meters, Clock clock) {
        this.channels = channels.stream().collect(java.util.stream.Collectors.toMap(OutboxChannel::channel, c -> c));
        this.store = store;
        this.props = props;
        this.principals = principals;
        this.renderers = renderers.stream().collect(java.util.stream.Collectors.toMap(ItemRenderer::template, r -> r));
        this.renderer = renderer;
        this.transport = transport;
        this.clock = clock;
        this.sent = meters.counter("drishti.collab.mail", "outcome", "sent");
        this.retried = meters.counter("drishti.collab.mail", "outcome", "retry");
        this.dead = meters.counter("drishti.collab.mail", "outcome", "dead");
        this.cancelled = meters.counter("drishti.collab.mail", "outcome", "cancelled");
        meters.gauge("drishti.collab.outbox", List.of(io.micrometer.core.instrument.Tag.of("state", "pending")), pendingGauge);
        meters.gauge("drishti.collab.outbox", List.of(io.micrometer.core.instrument.Tag.of("state", "dead")), deadGauge);
    }

    /** Starts the loop (a no-op when this server is not a dispatcher). */
    public synchronized void start() {
        if (loop != null || !props.outbox().enabled()) {
            return;
        }
        loop = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("drishti-outbox").unstarted(r));
        long ms = Math.max(50, props.outbox().tick().toMillis());
        loop.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (RuntimeException e) {
                LOG.warn("outbox tick failed: {}", e.toString());
            }
        }, ms, ms, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (loop != null) {
            loop.shutdownNow();
            loop = null;
        }
    }

    /** One pass: claims what is due and handles each. Returns how many rows it handled. */
    public int tick() {
        Instant now = clock.instant();
        List<OutboxItem> batch = store.claim(now, props.outbox().batch(), now.plus(props.outbox().lease()), serverId);
        Map<String, List<OutboxItem>> mail = new java.util.LinkedHashMap<>();
        for (OutboxItem item : batch) {
            if (channels.containsKey(item.channel()) || !props.email().coalesces()) {
                handle(item);
            } else {
                mail.computeIfAbsent(item.recipient(), r -> new java.util.ArrayList<>()).add(item);
            }
        }
        mail.values().forEach(this::handleMail);
        refreshGauges();
        if (Duration.between(lastPurge, now).compareTo(Duration.ofHours(1)) >= 0) {
            lastPurge = now;
            store.purgeSent(now.minus(Duration.ofDays(props.outbox().keepSentDays())));
        }
        return batch.size();
    }

    private void refreshGauges() {
        Map<String, Long> c = store.counts();
        pendingGauge.set(c.getOrDefault(OutboxItem.PENDING, 0L) + c.getOrDefault(OutboxItem.SENDING, 0L));
        deadGauge.set(c.getOrDefault(OutboxItem.DEAD, 0L));
    }

    /** Mail due together for one recipient: one email when there is one notice, a digest when there are several. */
    private void handleMail(List<OutboxItem> items) {
        if (items.size() == 1) {
            handle(items.get(0));
            return;
        }
        OutboxItem first = items.get(0);
        try {
            User user = principals.user(first.recipient()).filter(User::enabled).orElse(null);
            if (user == null || user.email() == null || !ADDRESS.matcher(user.email().strip()).matches()) {
                items.forEach(i -> skip(i, user == null ? "the recipient is unknown or disabled" : "the recipient has no valid address"));
                return;
            }
            Principal who = principals.of(first.recipient());
            List<OutboxItem> ready = new java.util.ArrayList<>();
            List<MailRenderer.Content> contents = new java.util.ArrayList<>();
            for (OutboxItem i : items) {
                ItemRenderer r = renderers.get(i.template());
                if (r == null) {
                    giveUp(i, "no renderer for template '" + i.template() + "'");
                    continue;
                }
                try {
                    contents.add(r.content(i, who));          // opt-outs, rights and masking are decided per notice, for this recipient
                    ready.add(i);
                } catch (ItemRenderer.Skip s) {
                    skip(i, s.getMessage());
                } catch (RuntimeException e) {
                    fail(i, e);
                }
            }
            if (ready.isEmpty()) {
                return;
            }
            OutboxItem lead = ready.get(0);
            try {
                RenderedMail mail = ready.size() == 1 ? renderer.render(contents.get(0), user.email().strip(), lead.seq(), lead.refId())
                        : renderer.renderDigest(contents, user.email().strip(), lead.seq(), lead.refId());
                transport.send(mail, props.email().from());
            } catch (RuntimeException e) {
                ready.forEach(i -> fail(i, e));
                return;
            }
            Instant at = clock.instant();
            for (OutboxItem i : ready) {
                store.sent(i.seq(), at);
                sent.increment();
            }
        } catch (RuntimeException e) {
            items.forEach(i -> fail(i, e));
        }
    }

    private void handle(OutboxItem item) {
        OutboxChannel channel = channels.get(item.channel());
        if (channel != null) {
            handleChannel(channel, item);
            return;
        }
        try {
            ItemRenderer r = renderers.get(item.template());
            if (r == null) {
                giveUp(item, "no renderer for template '" + item.template() + "'");
                return;
            }
            User user = principals.user(item.recipient()).filter(User::enabled).orElse(null);
            if (user == null) {
                skip(item, "the recipient is unknown or disabled");
                return;
            }
            if (user.email() == null || !ADDRESS.matcher(user.email().strip()).matches()) {
                skip(item, "the recipient has no valid address");
                return;
            }
            Principal who = principals.of(item.recipient());
            MailRenderer.Content content = r.content(item, who);
            transport.send(renderer.render(content, user.email().strip(), item.seq(), item.refId()), props.email().from());
            store.sent(item.seq(), clock.instant());
            sent.increment();
        } catch (ItemRenderer.Skip s) {
            skip(item, s.getMessage());
        } catch (RuntimeException e) {
            fail(item, e);
        }
    }

    private void handleChannel(OutboxChannel channel, OutboxItem item) {
        try {
            channel.deliver(item);
            store.sent(item.seq(), clock.instant());
            sent.increment();
            channel.delivered(item);
        } catch (ItemRenderer.Skip s) {
            skip(item, s.getMessage());
        } catch (OutboxChannel.Permanent p) {
            giveUp(item, p.getMessage());
            channel.gaveUp(item, p.getMessage());
        } catch (OutboxChannel.Deferred d) {
            store.retry(item.seq(), item.attempts(), clock.instant().plus(d.delay()), d.getMessage());
        } catch (RuntimeException e) {
            int attempts = item.attempts() + 1;
            String why = MailFailures.describe(e);
            if (attempts >= props.outbox().maxAttempts()) {
                giveUp(item, attempts, why);
                channel.gaveUp(item, why);
                return;
            }
            store.retry(item.seq(), attempts, clock.instant().plus(backoff(attempts)), why);
            retried.increment();
            LOG.info("{} {} to {} will be tried again (attempt {}): {}", item.channel(), item.seq(), item.recipient(), attempts, why);
        }
    }

    private void skip(OutboxItem item, String reason) {
        store.cancel(item.seq(), reason);
        cancelled.increment();
    }

    private void giveUp(OutboxItem item, String reason) {
        giveUp(item, item.attempts() + 1, reason);
    }

    private void giveUp(OutboxItem item, int attempts, String reason) {
        store.dead(item.seq(), attempts, reason);
        dead.increment();
        LOG.warn("mail {} to {} is a dead letter: {}", item.seq(), item.recipient(), reason);
    }

    private void fail(OutboxItem item, RuntimeException e) {
        int attempts = item.attempts() + 1;
        String why = MailFailures.describe(e);
        if (MailFailures.permanent(e) || attempts >= props.outbox().maxAttempts()) {
            giveUp(item, attempts, why);
            return;
        }
        store.retry(item.seq(), attempts, clock.instant().plus(backoff(attempts)), why);
        retried.increment();
        LOG.info("mail {} to {} will be tried again (attempt {}): {}", item.seq(), item.recipient(), attempts, why);
    }

    /** {@code backoff * 2^(attempt-1)}, at most {@code max-backoff}. */
    Duration backoff(int attempt) {
        Duration base = props.outbox().backoff();
        Duration max = props.outbox().maxBackoff();
        long factor = 1L << Math.min(30, Math.max(0, attempt - 1));
        Duration d = base.multipliedBy(factor);
        return d.compareTo(max) > 0 || d.isNegative() ? max : d;
    }

    /** Sends the test message at once (not through the outbox), so the administrator sees the answer; 503 DRS-7012 when it fails. */
    public void sendTest(String address, String product, String link, String requestedBy) {
        if (address == null || !ADDRESS.matcher(address.strip()).matches()) {
            throw new DrishtiException(ErrorCode.MAIL_UNAVAILABLE, "the account has no valid email address to send the test to");
        }
        MailRenderer.Content c = new MailRenderer.Content("test", "Test message from " + product, null, null, null, null, null, link);
        try {
            transport.send(renderer.render(c, address.strip(), 0, "test"), props.email().from());
        } catch (RuntimeException e) {
            throw new DrishtiException(ErrorCode.MAIL_UNAVAILABLE, "the test message could not be sent: " + MailFailures.describe(e));
        }
        LOG.info("mail test sent for {}", requestedBy);
    }

    /** The server's id in leases. */
    public String serverId() {
        return serverId;
    }
}
