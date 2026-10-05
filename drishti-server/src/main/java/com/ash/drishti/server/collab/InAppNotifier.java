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
package com.ash.drishti.server.collab;

import com.ash.drishti.identity.collab.Notice;
import com.ash.drishti.identity.collab.Share;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** The in-app channel: one inbox row per person reached, always on (it needs no mail server). */
public final class InAppNotifier implements Notifier {

    private final InboxService inbox;

    public InAppNotifier(InboxService inbox) {
        this.inbox = inbox;
    }

    @Override
    public String channel() {
        return "in-app";
    }

    @Override
    public List<Notice> onShare(ShareEvent e) {
        Share s = e.share();
        List<Notice> rows = new ArrayList<>();
        Instant now = Instant.now();
        e.reached().forEach(r -> rows.add(inbox.add(new Notice(0, r.username(), now, Notice.SHARE, s.kind(), s.entityId(), s.panelId(),
                s.id(), s.threadId(), null, s.sender(), null))));
        return rows;
    }
}
