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
package com.ash.drishti.server.loads;

import com.ash.drishti.identity.collab.OutboxItem;
import com.ash.drishti.server.collab.mail.ItemRenderer;
import com.ash.drishti.server.collab.mail.MailRenderer;
import com.ash.drishti.server.collab.mail.NotifyPrefs;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;

/**
 * The email for a data load or for late data ({@code load} outbox rows): the one-line summary, and a link to the pack's Data loads page.
 * Built when the mail is sent, for the recipient's rights then: it is cancelled when they can no longer open the kind or turned the
 * event off. The reference is {@code load:<pack>:<load id>} or {@code late:<pack>:<kind>:<date>}; no data value is ever in it.
 */
public final class LoadItemRenderer implements ItemRenderer {

    private final LoadStore store;
    private final Entitlements entitlements;
    private final NotifyPrefs prefs;
    private final String consoleUrl;

    public LoadItemRenderer(LoadStore store, Entitlements entitlements, NotifyPrefs prefs, String consoleUrl) {
        this.store = store;
        this.entitlements = entitlements;
        this.prefs = prefs;
        this.consoleUrl = consoleUrl == null ? "" : consoleUrl.replaceAll("/+$", "");
    }

    @Override
    public String template() {
        return LoadNotifier.TYPE;
    }

    @Override
    public MailRenderer.Content content(OutboxItem item, Principal who) {
        String[] ref = item.refId().split(":", 4);
        if (ref.length < 3) {
            throw new Skip("not a data-load reference");
        }
        String pack = ref[1];
        String kind;
        String date;
        String line;
        if ("load".equals(ref[0])) {
            LoadRecord r = store.find(pack, ref[2]).orElseThrow(() -> new Skip("the load is no longer in the history"));
            kind = r.kind();
            date = r.businessDate().toString();
            line = LoadService.summary(r);
        } else if (ref.length == 4) {
            kind = ref[2];
            date = ref[3];
            line = kind + " for " + date + " has not landed in time";
        } else {
            throw new Skip("not a data-load reference");
        }
        if (!entitlements.mayOpen(who, kind)) {
            throw new Skip("the recipient can no longer open " + kind + " data");
        }
        if (!prefs.emailOn(item.recipient(), LoadNotifier.TYPE)) {
            throw new Skip("the recipient turned load mail off");
        }
        return new MailRenderer.Content(LoadNotifier.TYPE, line, pack + " / " + kind, date, null, date, null, consoleUrl + "/admin/packs/" + pack + "/loads");
    }
}
