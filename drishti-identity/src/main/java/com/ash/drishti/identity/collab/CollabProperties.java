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
package com.ash.drishti.identity.collab;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.collab.*}: share with a note, comment threads and the inbox (docs/architecture/COLLABORATION.md). Every key has
 * a default here and in {@code application.yaml}; {@code packs} overrides some of them per pack, by pack name in configuration.
 *
 * @param enabled shares, threads and the inbox are on
 * @param store {@code jpa} (the identity database, default) or {@code file}
 * @param dir the file store's directory, and exports
 * @param consoleUrl the console's address for links in emails; empty turns email off
 * @param directory the people picker
 * @param mentionableRoles roles usable as groups ({@code *} = all; {@code admin} and {@code service} never)
 * @param maxGroupSize largest role a mention or share may expand to
 * @param share sharing limits and policy
 * @param threads comment thread limits (step 5)
 * @param text what may be typed into a note or comment
 * @param limits per-user rate limits
 * @param inbox inbox retention and live delivery
 * @param email the email channel (step 4)
 * @param outbox the outbox dispatcher (step 4)
 * @param retention how long records are kept
 * @param exportKeep how long an export zip is kept
 * @param packs per-pack overrides
 * @param bridges webhook bridges (phase 2)
 * @param snapshots watermarked snapshots (phase 2)
 */
@ConfigurationProperties("drishti.collab")
public record CollabProperties(Boolean enabled, String store, String dir, String consoleUrl, Directory directory, List<String> mentionableRoles,
        Integer maxGroupSize, Share share, Threads threads, Text text, Limits limits, Inbox inbox, Email email, Outbox outbox,
        Retention retention, Duration exportKeep, Map<String, Map<String, Object>> packs, Bridges bridges, Snapshots snapshots) {

    public CollabProperties {
        enabled = enabled == null || enabled;
        store = store == null || store.isBlank() ? "jpa" : store.trim().toLowerCase();
        dir = dir == null || dir.isBlank() ? "./data/collab" : dir;
        consoleUrl = consoleUrl == null ? "" : consoleUrl.trim();
        directory = directory == null ? new Directory(null, null, null) : directory;
        mentionableRoles = mentionableRoles == null ? List.of("*") : List.copyOf(mentionableRoles);
        maxGroupSize = positive(maxGroupSize, 200);
        share = share == null ? new Share(null, null, null, null, null) : share;
        threads = threads == null ? new Threads(null, null, null, null) : threads;
        text = text == null ? new Text(null, null) : text;
        limits = limits == null ? new Limits(null, null, null, null, null) : limits;
        inbox = inbox == null ? new Inbox(null, null, null, null) : inbox;
        email = email == null ? new Email(null, null, null, null) : email;
        outbox = outbox == null ? new Outbox(null, null, null, null, null, null, null, null) : outbox;
        retention = retention == null ? new Retention(null, null, null) : retention;
        exportKeep = exportKeep == null ? Duration.ofHours(24) : exportKeep;
        packs = packs == null ? Map.of() : Map.copyOf(packs);
        bridges = bridges == null ? new Bridges(null, null, null, null, null, null, null) : bridges;
        snapshots = snapshots == null ? new Snapshots(null) : snapshots;
    }

    public boolean jpa() {
        return !"file".equals(store);
    }

    /** Whether {@code role} may be addressed as a group. */
    public boolean mentionable(String role) {
        if ("admin".equals(role) || "service".equals(role)) {
            return false;
        }
        return mentionableRoles.contains("*") || mentionableRoles.contains(role);
    }

    private static int positive(Integer v, int dflt) {
        return v == null || v < 1 ? dflt : v;
    }

    private static Duration duration(Duration v, Duration dflt) {
        return v == null || v.isNegative() || v.isZero() ? dflt : v;
    }

    /**
     * @param scope {@code shared-packs} (default: only users who share at least one assigned pack) or {@code all}
     * @param minQuery shortest query
     * @param limit most entries per answer
     */
    public record Directory(String scope, Integer minQuery, Integer limit) {
        public Directory {
            scope = "all".equals(scope) ? "all" : "shared-packs";
            minQuery = positive(minQuery, 2);
            limit = positive(limit, 10);
        }

        public boolean all() {
            return "all".equals(scope);
        }
    }

    /**
     * @param maxRecipients most addressed names (users and roles) in one share
     * @param maxExpanded most people after roles are expanded
     * @param maxText longest note, in characters
     * @param undeliverable {@code tell} (default: the sender learns who was not notified, and why) or {@code silent}
     * @param postToThread the dialog's default for also posting to the discussion
     */
    public record Share(Integer maxRecipients, Integer maxExpanded, Integer maxText, String undeliverable, Boolean postToThread) {
        public Share {
            maxRecipients = positive(maxRecipients, 25);
            maxExpanded = positive(maxExpanded, 200);
            maxText = positive(maxText, 2000);
            undeliverable = "silent".equals(undeliverable) ? "silent" : "tell";
            postToThread = postToThread != null && postToThread;
        }

        public boolean tell() {
            return "tell".equals(undeliverable);
        }
    }

    /**
     * @param maxText longest comment
     * @param maxPerEntity most threads on one entity
     * @param editWindow how long an author may edit a comment
     * @param pageSize comments per page
     */
    public record Threads(Integer maxText, Integer maxPerEntity, Duration editWindow, Integer pageSize) {
        public Threads {
            maxText = positive(maxText, 4000);
            maxPerEntity = positive(maxPerEntity, 500);
            editWindow = duration(editWindow, Duration.ofMinutes(15));
            pageSize = positive(pageSize, 50);
        }
    }

    /**
     * @param onMaskedCopy {@code warn} (default), {@code reject} or {@code allow}: a masked field's value typed into text
     * @param denyPatterns regular expressions refused in notes and comments
     */
    public record Text(String onMaskedCopy, List<String> denyPatterns) {
        public Text {
            onMaskedCopy = "reject".equals(onMaskedCopy) || "allow".equals(onMaskedCopy) ? onMaskedCopy : "warn";
            denyPatterns = denyPatterns == null ? List.of() : List.copyOf(denyPatterns);
        }
    }

    /**
     * @param sharesPerMinute most shares one user sends a minute
     * @param sharesPerDay most shares one user sends in 24 hours
     * @param commentsPerMinute most comments one user writes a minute
     * @param directoryPerMinute most directory searches one user makes a minute
     * @param mailsPerRecipientPerHour most emails one recipient is sent an hour
     */
    public record Limits(Integer sharesPerMinute, Integer sharesPerDay, Integer commentsPerMinute, Integer directoryPerMinute,
            Integer mailsPerRecipientPerHour) {
        public Limits {
            sharesPerMinute = positive(sharesPerMinute, 5);
            sharesPerDay = positive(sharesPerDay, 100);
            commentsPerMinute = positive(commentsPerMinute, 10);
            directoryPerMinute = positive(directoryPerMinute, 60);
            mailsPerRecipientPerHour = positive(mailsPerRecipientPerHour, 30);
        }
    }

    /**
     * @param keep newest rows kept per user
     * @param keepDays days a row is kept
     * @param poll how often a server looks for rows another server wrote, while it has open streams
     * @param coalesce several notices about one thread within this long become one email
     */
    public record Inbox(Integer keep, Integer keepDays, Duration poll, Duration coalesce) {
        public Inbox {
            keep = positive(keep, 1000);
            keepDays = positive(keepDays, 180);
            poll = duration(poll, Duration.ofSeconds(5));
            coalesce = duration(coalesce, Duration.ofSeconds(60));
        }
    }

    /**
     * @param enabled the email channel is on (also needs mail settings and {@code console-url}, and security on)
     * @param content {@code link-only}, {@code title} or {@code comment} (default)
     * @param from the From address
     * @param templatesDir overrides for the email templates
     */
    public record Email(Boolean enabled, String content, String from, String templatesDir) {
        public Email {
            enabled = enabled != null && enabled;
            content = "link-only".equals(content) || "title".equals(content) ? content : "comment";
            from = from == null || from.isBlank() ? "drishti@localhost" : from;
            templatesDir = templatesDir == null ? "" : templatesDir;
        }
    }

    /**
     * @param enabled this server dispatches the outbox
     * @param tick how often it looks for due rows
     * @param batch most rows per tick
     * @param maxAttempts attempts before a row is dead
     * @param backoff first retry delay (doubles)
     * @param maxBackoff longest retry delay
     * @param lease how long a claimed row is held
     * @param keepSentDays days a sent row is kept
     */
    public record Outbox(Boolean enabled, Duration tick, Integer batch, Integer maxAttempts, Duration backoff, Duration maxBackoff,
            Duration lease, Integer keepSentDays) {
        public Outbox {
            enabled = enabled == null || enabled;
            tick = duration(tick, Duration.ofSeconds(2));
            batch = positive(batch, 50);
            maxAttempts = positive(maxAttempts, 8);
            backoff = duration(backoff, Duration.ofSeconds(30));
            maxBackoff = duration(maxBackoff, Duration.ofHours(1));
            lease = duration(lease, Duration.ofSeconds(60));
            keepSentDays = positive(keepSentDays, 30);
        }
    }

    /**
     * @param keepDays days shares and threads are kept; 0 keeps them forever (the default for everything below)
     * @param kinds days per entity kind, by kind name in configuration; overrides the pack's and the default; 0 keeps that kind forever
     * @param interval how often the purge runs (it does nothing while every retention is 0)
     */
    public record Retention(Integer keepDays, Map<String, Integer> kinds, Duration interval) {
        public Retention {
            keepDays = keepDays == null || keepDays < 0 ? 0 : keepDays;
            kinds = kinds == null ? Map.of() : Map.copyOf(kinds);
            interval = interval == null || interval.isZero() || interval.isNegative() ? Duration.ofHours(24) : interval;
        }
    }

    /**
     * @param enabled bridges to chat tools are on (a bridge also needs its URL in the environment)
     * @param renderAs the role whose view of masked values the posted text follows; the posted text never holds a data value whatever
     *     this role is (default {@code viewer}, which has no {@code raw})
     * @param allow URL prefixes a bridge URL must start with (empty: no bridge may be used, so configuration alone cannot reach any host)
     * @param perMinute most posts per bridge per minute; the rest wait in the outbox
     * @param timeout connect and response time allowed for one post
     * @param maxNote most characters of a note or comment a post carries (cut with an ellipsis)
     * @param webhooks the bridges
     */
    public record Bridges(Boolean enabled, String renderAs, List<String> allow, Integer perMinute, Duration timeout, Integer maxNote,
            List<Webhook> webhooks) {
        public Bridges {
            enabled = enabled != null && enabled;
            renderAs = renderAs == null || renderAs.isBlank() ? "viewer" : renderAs.strip();
            allow = allow == null ? List.of() : List.copyOf(allow);
            perMinute = positive(perMinute, 30);
            timeout = duration(timeout, Duration.ofSeconds(10));
            maxNote = positive(maxNote, 500);
            webhooks = webhooks == null ? List.of() : List.copyOf(webhooks);
        }
    }

    /**
     * One bridge. The URL and the signing secret are read from the environment variables named here, never from configuration.
     *
     * @param name the bridge's name (letters, digits, dash, underscore); it is the outbox recipient and appears in logs
     * @param format {@code json} (signed generic webhook), {@code teams} (incoming webhook or Workflows) or {@code slack}
     * @param urlEnv the environment variable holding the URL
     * @param secretEnv the environment variable holding the HMAC secret ({@code json} only)
     * @param routes which events go to it; none means nothing is posted
     */
    public record Webhook(String name, String format, String urlEnv, String secretEnv, List<Route> routes) {
        public Webhook {
            name = name == null ? "" : name.strip();
            format = format == null ? "json" : format.strip().toLowerCase(java.util.Locale.ROOT);
            urlEnv = urlEnv == null ? "" : urlEnv.strip();
            secretEnv = secretEnv == null ? "" : secretEnv.strip();
            routes = routes == null ? List.of() : List.copyOf(routes);
        }
    }

    /**
     * @param packs the packs (empty: any) whose kinds this route covers
     * @param kinds the entity kinds (empty: any)
     * @param events {@code share}, {@code comment}, {@code mention} (empty: none)
     */
    public record Route(List<String> packs, List<String> kinds, List<String> events) {
        public Route {
            packs = packs == null ? List.of() : List.copyOf(packs);
            kinds = kinds == null ? List.of() : List.copyOf(kinds);
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    /** @param enabled watermarked snapshots (phase 2) */
    public record Snapshots(Boolean enabled) {
        public Snapshots {
            enabled = enabled != null && enabled;
        }
    }
}
