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

import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.Notice;
import com.ash.drishti.identity.collab.OutboxItem;
import com.ash.drishti.identity.collab.OutboxStore;
import com.ash.drishti.server.collab.Notifier;
import com.ash.drishti.server.security.PackAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bridge channel: for every share, comment and mention it writes one outbox row per bridge whose routes want it, inside the
 * share's or comment's transaction (so a rolled-back post is never announced). It writes no inbox rows and sends nothing itself:
 * {@link BridgeSender} does, through the dispatcher. Which pack owns a kind is asked of {@link PackAccess}; no pack name is in code.
 */
public final class BridgeNotifier implements Notifier {

    private final CollabProperties props;
    private final BridgeRegistry registry;
    private final OutboxStore outbox;
    private final PackAccess packs;
    private final Clock clock;

    public BridgeNotifier(CollabProperties props, BridgeRegistry registry, OutboxStore outbox, PackAccess packs, Clock clock) {
        this.props = props;
        this.registry = registry;
        this.outbox = outbox;
        this.packs = packs;
        this.clock = clock;
    }

    @Override
    public String channel() {
        return BridgeSender.CHANNEL;
    }

    @Override
    public boolean available() {
        return props.bridges().enabled() && !registry.all().isEmpty();
    }

    @Override
    public List<Notice> onShare(ShareEvent e) {
        String kind = e.share().kind();
        for (BridgeRegistry.Bridge b : registry.matching(BridgeRegistry.SHARE, kind, packs.ownerOf(kind))) {
            outbox.add(OutboxItem.pending(BridgeSender.CHANNEL, b.name(), BridgeRegistry.SHARE, e.share().id(), Instant.now(clock)));
        }
        return List.of();
    }

    @Override
    public void onCommentPosted(CommentPosted e) {
        String kind = e.thread().kind();
        String pack = packs.ownerOf(kind);
        Map<String, String> templates = new LinkedHashMap<>();
        registry.matching(BridgeRegistry.COMMENT, kind, pack).forEach(b -> templates.put(b.name(), BridgeRegistry.COMMENT));
        if (e.mentions()) {                                             // the more specific event wins when a bridge wants both: one post
            registry.matching(BridgeRegistry.MENTION, kind, pack).forEach(b -> templates.put(b.name(), BridgeRegistry.MENTION));
        }
        templates.forEach((name, template) -> outbox.add(OutboxItem.pending(BridgeSender.CHANNEL, name, template, e.comment().id(), Instant.now(clock))));
    }
}
