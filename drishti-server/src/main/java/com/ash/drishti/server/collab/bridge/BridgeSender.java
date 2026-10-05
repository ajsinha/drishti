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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.OutboxItem;
import com.ash.drishti.server.collab.RateLimitedException;
import com.ash.drishti.server.collab.RateLimits;
import com.ash.drishti.server.collab.mail.OutboxChannel;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The outbox channel for bridges: renders the row for its bridge, encodes it for the bridge's format, posts it, and says what
 * happened in the audit log. The dispatcher supplies the lease, the retries with backoff and the dead letters. A bridge over its
 * {@code per-minute} limit defers the row (no attempt is counted). Neither the URL nor the secret is ever logged or stored in a row.
 */
public final class BridgeSender implements OutboxChannel {

    private static final Logger LOG = LoggerFactory.getLogger(BridgeSender.class);
    public static final String CHANNEL = "bridge";

    private final BridgeRegistry registry;
    private final BridgeItemRenderer renderer;
    private final BridgeClient client;
    private final RateLimits limits;
    private final CollabProperties props;
    private final AuditLog audit;
    private final Clock clock;
    private final String product;

    @SuppressWarnings("java:S107")
    public BridgeSender(BridgeRegistry registry, BridgeItemRenderer renderer, BridgeClient client, RateLimits limits, CollabProperties props,
            AuditLog audit, Clock clock, String product) {
        this.registry = registry;
        this.renderer = renderer;
        this.client = client;
        this.limits = limits;
        this.props = props;
        this.audit = audit;
        this.clock = clock;
        this.product = product == null || product.isBlank() ? "Drishti" : product;
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public void deliver(OutboxItem item) {
        BridgeRegistry.Bridge bridge = registry.find(item.recipient())
                .orElseThrow(() -> new Permanent("the bridge '" + item.recipient() + "' is not in drishti.collab.bridges.webhooks any more"));
        if (!props.bridges().enabled()) {
            throw new Permanent("bridges are switched off (drishti.collab.bridges.enabled)");
        }
        if (!bridge.usable()) {
            throw new Permanent("the bridge '" + bridge.name() + "' cannot post: " + bridge.status());
        }
        BridgeMessage message = renderer.render(item);                   // may Skip: the share or comment is gone
        try {
            limits.hit("bridge", bridge.name(), props.bridges().perMinute(), Duration.ofMinutes(1), "posts to " + bridge.name());
        } catch (RateLimitedException e) {
            throw new Deferred("over " + props.bridges().perMinute() + " posts a minute to this bridge", Duration.ofSeconds(Math.max(1, e.retryAfterSeconds())));
        }
        client.post(bridge, BridgeFormat.of(bridge.format()).encode(message, bridge, item.seq(), clock.instant().getEpochSecond()));
    }

    @Override
    public void delivered(OutboxItem item) {
        audit.record("system", "collab.bridge.post", item.recipient(), item.template() + " " + item.refId() + " (delivery " + item.seq() + ")");
    }

    @Override
    public void gaveUp(OutboxItem item, String reason) {
        audit.record("system", "collab.bridge.dead", item.recipient(), item.template() + " " + item.refId() + " (delivery " + item.seq() + "): " + reason);
    }

    /**
     * Posts a test message now (not through the outbox), so the administrator sees the endpoint's answer. The message holds no data.
     *
     * @return the endpoint's HTTP status
     * @throws DrishtiException {@code 404 DRS-7014} for an unknown bridge, {@code 503 DRS-7013} when it cannot post
     */
    public int test(String name, String actor, String consoleUrl) {
        BridgeRegistry.Bridge bridge = registry.find(name)
                .orElseThrow(() -> new DrishtiException(ErrorCode.BRIDGE_NOT_FOUND, "no bridge named '" + name + "' in drishti.collab.bridges.webhooks"));
        if (!props.bridges().enabled()) {
            throw new DrishtiException(ErrorCode.BRIDGE_UNAVAILABLE, "bridges are switched off: set drishti.collab.bridges.enabled");
        }
        if (!bridge.usable()) {
            throw new DrishtiException(ErrorCode.BRIDGE_UNAVAILABLE, "the bridge '" + name + "' cannot post: " + bridge.status());
        }
        BridgeMessage m = new BridgeMessage("test", "test", product, "Test message from " + product, null, null, null, null,
                "If you can read this, the bridge works. It carries no data.", consoleUrl, clock.instant());
        try {
            int status = client.post(bridge, BridgeFormat.of(bridge.format()).encode(m, bridge, 0, clock.instant().getEpochSecond()));
            audit.record(actor, "collab.bridge.test", name, "HTTP " + status);
            return status;
        } catch (RuntimeException e) {
            audit.record(actor, "collab.bridge.test", name, "failed: " + e.getMessage());
            LOG.info("bridge test of {} failed: {}", name, e.getMessage());
            throw new DrishtiException(ErrorCode.BRIDGE_UNAVAILABLE, "the test post to '" + name + "' failed: " + e.getMessage());
        }
    }
}
