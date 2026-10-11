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
package com.ash.drishti.server.alerts.measure;

import com.ash.drishti.identity.collab.Notice;
import com.ash.drishti.server.collab.InboxHub;
import com.ash.drishti.server.collab.InboxService;
import com.ash.drishti.server.collab.bridge.BridgeNotifier;
import com.ash.drishti.server.collab.mail.MailFailures;
import com.ash.drishti.server.collab.mail.MailRenderer;
import com.ash.drishti.server.collab.mail.MailTransport;
import com.ash.drishti.server.collab.mail.RenderedMail;
import java.util.List;
import java.util.UUID;
import org.springframework.web.util.HtmlUtils;

/**
 * The real channels: a row in the inbox (the bell shows it at once), an email through the same SMTP transport as subscriptions, and a post to the chat bridges
 * through the collaboration outbox. The inbox row holds the one-line message the person's own reading produced, cut to the row's width; the full text and the
 * delivery results are in the alert's history.
 */
public final class ServerAlertChannels implements AlertChannels {

    /** The inbox row's {@code panel_id} column is this wide. */
    static final int INBOX_LINE = 100;

    private final InboxService inbox;
    private final InboxHub hub;
    private final MailRenderer mail;
    private final MailTransport transport;
    private final String from;
    private final String consoleUrl;
    private final BridgeNotifier bridges;

    public ServerAlertChannels(InboxService inbox, InboxHub hub, MailRenderer mail, MailTransport transport, String from, String consoleUrl, BridgeNotifier bridges) {
        this.inbox = inbox;
        this.hub = hub;
        this.mail = mail;
        this.transport = transport;
        this.from = from;
        this.consoleUrl = consoleUrl == null ? "" : consoleUrl.replaceAll("/+$", "");
        this.bridges = bridges;
    }

    @Override
    public String inbox(Fired f) {
        // type alert; kind alert; entity = where it opens; panel = the line (a row holds a pointer and one line, never more)
        Notice stored = inbox.add(new Notice(0, f.person().username(), f.at(), "alert", "alert", f.link(), clip(f.message(), INBOX_LINE), null, null, null,
                f.spec().board() == null ? f.spec().owner() : "board", null));
        hub.publish(stored);
        return "ok";
    }

    @Override
    public String email(Fired f) {
        String to = f.person().email();
        if (to == null || to.isBlank()) {
            return "skipped: no email address on file";
        }
        try {
            String link = consoleUrl + f.link();
            String text = f.message() + "\n\nSeverity: " + f.severity() + "\nWatching: " + f.spec().source().label() + "\nCondition: " + f.spec().describe();
            String html = "<p style=\"font-size:16px;\"><b>" + HtmlUtils.htmlEscape(f.message()) + "</b></p><p style=\"color:#555;\">Severity: " + HtmlUtils.htmlEscape(f.severity())
                    + " &middot; Watching: " + HtmlUtils.htmlEscape(f.spec().source().label()) + " &middot; Condition: " + HtmlUtils.htmlEscape(f.spec().describe()) + "</p>";
            RenderedMail m = mail.renderBody("alert", f.spec().title(), text, html, link, to, "<alert." + UUID.randomUUID().toString().replace("-", "") + "@" + mail.host() + ">",
                    List.of());
            transport.send(m, from);
            return "sent";
        } catch (RuntimeException e) {
            return "failed: " + clip(MailFailures.describe(e), 160);
        }
    }

    @Override
    public String bridge(Fired f) {
        if (bridges == null) {
            return "skipped: no bridges are configured";
        }
        String kind = f.spec().source().type().equals("field") ? f.spec().source().kind() : "bi";
        int n = bridges.onAlert(kind, MeasureAlertService.bridgeRef(f.key(), f.id()));
        return n == 0 ? "skipped: no bridge route lists the alert event" : "queued for " + n + (n == 1 ? " bridge" : " bridges");
    }

    private static String clip(String s, int n) {
        String t = s == null ? "" : s.replaceAll("[\\r\\n]+", " ").strip();
        return t.length() > n ? t.substring(0, n - 1) + "…" : t;
    }
}
