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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.OutboxItem;
import com.ash.drishti.server.collab.Notifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Bridges end to end, in process: events become outbox rows by the routing rules, the dispatcher posts them to a fake endpoint in the
 * format of the bridge, and what is posted holds words and a link and never a value. Retries, dead letters, the rate limit, redirects and
 * the secrets are checked against the same fake endpoint.
 */
class BridgeDeliveryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<BridgeHarness> open = new java.util.ArrayList<>();

    private BridgeHarness harness(String format, List<CollabProperties.Route> routes) throws Exception {
        BridgeHarness h = new BridgeHarness().configure(format, routes, 30, null);
        open.add(h);
        return h;
    }

    @AfterEach
    void close() {
        open.forEach(BridgeHarness::close);
    }

    private static List<CollabProperties.Route> everything() {
        return List.of(BridgeHarness.route(List.of(), List.of(), "share", "comment", "mention"));
    }

    private static long run(BridgeHarness h) {
        long n = 0;
        for (int i = 0; i < 5; i++) {
            n += h.dispatcher.tick();
        }
        return n;
    }

    // ---- payload shapes ---------------------------------------------------------------------------------------------------

    @Test
    void aShareToATeamsBridgeIsAnAdaptiveCardWithTheNoteAndAButtonAndNoValue() throws Exception {
        BridgeHarness h = harness("teams", everything());
        h.notifier.onShare(new Notifier.ShareEvent(h.share("sh_1"), List.of()));
        assertThat(run(h)).isEqualTo(1);
        assertThat(h.hits).hasSize(1);
        BridgeHarness.Hit hit = h.hits.get(0);
        assertThat(hit.path()).isEqualTo(BridgeHarness.SECRET_PATH);
        assertThat(hit.header("Content-Type")).isEqualTo("application/json");
        JsonNode m = JSON.readTree(hit.body());
        assertThat(m.path("type").asText()).isEqualTo("message");
        JsonNode att = m.path("attachments").get(0);
        assertThat(att.path("contentType").asText()).isEqualTo("application/vnd.microsoft.card.adaptive");
        JsonNode card = att.path("content");
        assertThat(card.path("type").asText()).isEqualTo("AdaptiveCard");
        assertThat(card.path("body").get(0).path("text").asText()).isEqualTo("Ann Shah shared a view");
        assertThat(card.path("body").get(1).path("text").asText()).isEqualTo("Trade IRS-48213 · Panel: Cashflows · As of: 2026-09-30");
        assertThat(card.path("body").get(2).path("text").asText()).isEqualTo("price ••• looks off, see •••");
        assertThat(card.path("actions").get(0).path("type").asText()).isEqualTo("Action.OpenUrl");
        assertThat(card.path("actions").get(0).path("url").asText()).isEqualTo("https://drishti.example/share/sh_1");
        assertNoValue(h);
    }

    @Test
    void aShareToASlackBridgeIsBlocksWithEscapedTextAndAButton() throws Exception {
        BridgeHarness h = harness("slack", everything());
        h.notifier.onShare(new Notifier.ShareEvent(h.share("sh_1"), List.of()));
        run(h);
        JsonNode m = JSON.readTree(h.hits.get(0).body());
        assertThat(m.path("text").asText()).isEqualTo("Ann Shah shared a view https://drishti.example/share/sh_1");
        JsonNode blocks = m.path("blocks");
        assertThat(blocks.get(0).path("text").path("type").asText()).isEqualTo("mrkdwn");
        assertThat(blocks.get(0).path("text").path("text").asText()).isEqualTo("*Ann Shah shared a view*\nTrade IRS-48213 · Panel: Cashflows · As of: 2026-09-30");
        assertThat(blocks.get(1).path("text").path("text").asText()).isEqualTo("> price ••• looks off, see •••");
        assertThat(blocks.get(2).path("type").asText()).isEqualTo("actions");
        assertThat(blocks.get(2).path("elements").get(0).path("url").asText()).isEqualTo("https://drishti.example/share/sh_1");
        assertNoValue(h);
    }

    @Test
    void slackTextIsEscapedSoANoteCannotInjectALinkOrAMention() {
        assertThat(BridgeFormat.Slack.escape("<!channel> & <https://evil|click>")).isEqualTo("&lt;!channel&gt; &amp; &lt;https://evil|click&gt;");
    }

    @Test
    void aCommentToAJsonBridgeCarriesTheSchemaTheEventAndASignatureOverTheBody() throws Exception {
        BridgeHarness h = harness("json", everything());
        var c = h.comment("cm_1", "live");
        h.notifier.onCommentPosted(new Notifier.CommentPosted(h.thread(), c, false));
        assertThat(run(h)).isEqualTo(1);
        BridgeHarness.Hit hit = h.hits.get(0);
        JsonNode m = JSON.readTree(hit.body());
        assertThat(m.path("schema").asText()).isEqualTo("drishti.bridge/1");
        assertThat(m.path("event").asText()).isEqualTo("comment");
        assertThat(m.path("id").asText()).isEqualTo("cm_1");
        assertThat(m.path("headline").asText()).isEqualTo("Ann Shah commented on a view");
        assertThat(m.path("kind").asText()).isEqualTo("Trade");
        assertThat(m.path("entityId").asText()).isEqualTo("IRS-48213");
        assertThat(m.path("panel").asText()).isEqualTo("Cashflows");
        assertThat(m.path("note").asText()).isEqualTo("check ••• against 5000");
        assertThat(m.path("link").asText()).startsWith("https://drishti.example/v/trade/IRS-48213").endsWith("#p-cashflows");
        assertThat(hit.header("X-Drishti-Event")).isEqualTo("comment");
        assertThat(hit.header("X-Drishti-Delivery")).isNotBlank();
        String ts = hit.header("X-Drishti-Timestamp");
        assertThat(hit.header("X-Drishti-Signature")).isEqualTo("sha256=" + hmac(BridgeHarness.SIGNING_SECRET, ts + "." + hit.body()));
        assertNoValue(h);
    }

    @Test
    void theSignatureChangesWithTheBodyAndTheSecret() throws Exception {
        BridgeHarness h = harness("json", everything());
        h.notifier.onShare(new Notifier.ShareEvent(h.share("sh_1"), List.of()));
        run(h);
        BridgeHarness.Hit hit = h.hits.get(0);
        String ts = hit.header("X-Drishti-Timestamp");
        assertThat(hit.header("X-Drishti-Signature")).isNotEqualTo("sha256=" + hmac(BridgeHarness.SIGNING_SECRET, ts + "." + hit.body() + " "));
        assertThat(hit.header("X-Drishti-Signature")).isNotEqualTo("sha256=" + hmac("another", ts + "." + hit.body()));
        assertThat(hit.header("X-Drishti-Signature")).isNotEqualTo("sha256=" + hmac(BridgeHarness.SIGNING_SECRET, "1." + hit.body()));
    }

    private static String hmac(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- never a value ----------------------------------------------------------------------------------------------------

    private static void assertNoValue(BridgeHarness h) {
        assertThat(h.everythingReceived()).doesNotContain(BridgeHarness.MASKED_VALUE).doesNotContain(BridgeHarness.SIGNING_SECRET);
    }

    @Test
    void theNoteIsReadAsTheRenderAsRoleReadsItSoMaskedValuesAreNeverSent() throws Exception {
        BridgeHarness h = harness("json", everything());
        h.notifier.onShare(new Notifier.ShareEvent(h.share("sh_1"), List.of()));
        run(h);
        assertThat(h.hits.get(0).body()).contains("price ••• looks off").contains("see •••");
        assertThat(h.readers).isNotEmpty().allSatisfy(p -> {
            assertThat(p.roles()).containsExactly("viewer");
            assertThat(p.user()).isEqualTo("bridge:desk");
        });
    }

    @Test
    void anAdministratorWhoNamesARawRoleAsRenderAsGetsValuesAndQuotesFilled() throws Exception {
        BridgeHarness h = new BridgeHarness().configure("json", everything(), 30, "raw-reader");
        open.add(h);
        h.notifier.onShare(new Notifier.ShareEvent(h.share("sh_1"), List.of()));
        run(h);
        assertThat(h.hits.get(0).body()).contains(BridgeHarness.MASKED_VALUE).contains("1234567").doesNotContain("{$.mtm}");
    }

    @Test
    void linkOnlyPacksSendNoIdNoPanelAndNoNote() throws Exception {
        BridgeHarness h = harness("json", everything());
        h.props = new CollabProperties(true, null, null, "https://drishti.example", null, null, null, null, null, null, null, null,
                new CollabProperties.Email(false, "link-only", null, null, java.time.Duration.ZERO), null, null, null, null, h.props.bridges(), null);
        BridgeItemRenderer r = new BridgeItemRenderer(h.shares, h.threads, h.principals, h.entitlements,
                new com.ash.drishti.server.collab.mail.MailContentPolicy(h.props, h.packs),
                new com.ash.drishti.server.collab.PanelTitles((k, i, w) -> java.util.Map.of(), h.entitlements),
                new com.ash.drishti.server.collab.LinkBuilder("https://drishti.example"), h.props, new com.ash.drishti.server.collab.thread.CommentRenderer(h.entitlements, h.docs), "Drishti", h.clock);
        h.share("sh_1");
        BridgeMessage m = r.render(OutboxItem.pending("bridge", "desk", "share", "sh_1", h.clock.instant()));
        assertThat(m.kind()).isNull();
        assertThat(m.entityId()).isNull();
        assertThat(m.panel()).isNull();
        assertThat(m.note()).isNull();
        assertThat(m.link()).isEqualTo("https://drishti.example/share/sh_1");
    }

    @Test
    void aRetractedOrHiddenCommentIsNotPostedAndAGoneShareIsCancelled() throws Exception {
        BridgeHarness h = harness("json", everything());
        h.comment("cm_1", "retracted");
        h.outbox.add(OutboxItem.pending("bridge", "desk", "comment", "cm_1", h.clock.instant()));
        h.outbox.add(OutboxItem.pending("bridge", "desk", "share", "sh_gone", h.clock.instant()));
        run(h);
        assertThat(h.hits).isEmpty();
        assertThat(h.outbox.list(OutboxItem.CANCELLED, 10)).hasSize(2);
    }

    // ---- routing ----------------------------------------------------------------------------------------------------------

    @Test
    void routesChooseByEventPackAndKind() throws Exception {
        BridgeHarness h = harness("json", List.of(
                BridgeHarness.route(List.of("market-risk"), List.of(), "share"),
                BridgeHarness.route(List.of(), List.of("trade"), "mention")));
        // share of a market-risk kind: yes; share of another pack: no
        h.notifier.onShare(new Notifier.ShareEvent(h.share("sh_1"), List.of()));
        assertThat(h.outbox.list(null, 10)).hasSize(1);
        var other = new com.ash.drishti.identity.collab.Share("sh_2", "ann", h.clock.instant(), "sample", "S1", null, null, null, "x", List.of(), "in-app",
                null, null);
        h.notifier.onShare(new Notifier.ShareEvent(other, List.of()));
        assertThat(h.outbox.list(null, 10)).hasSize(1);
        // a plain comment on a trade: the mention route does not take it; with a mention it does, as a mention
        var c = h.comment("cm_1", "live");
        h.notifier.onCommentPosted(new Notifier.CommentPosted(h.thread(), c, false));
        assertThat(h.outbox.list(null, 10)).hasSize(1);
        h.notifier.onCommentPosted(new Notifier.CommentPosted(h.thread(), c, true));
        List<OutboxItem> rows = h.outbox.list(null, 10);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(OutboxItem::template).contains("mention", "share");
        assertThat(rows).allSatisfy(i -> {
            assertThat(i.channel()).isEqualTo("bridge");
            assertThat(i.recipient()).isEqualTo("desk");
        });
    }

    @Test
    void aBridgeWithNoRoutesPostsNothingAndOneCommentWithTwoMatchingRoutesIsOneRow() throws Exception {
        BridgeHarness quiet = harness("json", List.of());
        quiet.notifier.onShare(new Notifier.ShareEvent(quiet.share("sh_1"), List.of()));
        assertThat(quiet.outbox.list(null, 10)).isEmpty();
        BridgeHarness both = harness("json", List.of(BridgeHarness.route(List.of(), List.of(), "comment", "mention")));
        var c = both.comment("cm_1", "live");
        both.notifier.onCommentPosted(new Notifier.CommentPosted(both.thread(), c, true));
        assertThat(both.outbox.list(null, 10)).hasSize(1).first().extracting(OutboxItem::template).isEqualTo("mention");
    }

    @Test
    void theNotifierIsAvailableOnlyWhenBridgesAreOnAndConfigured() throws Exception {
        BridgeHarness h = harness("json", everything());
        assertThat(h.notifier.available()).isTrue();
        BridgeRegistry none = new BridgeRegistry(new CollabProperties(true, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null), k -> null);
        assertThat(new BridgeNotifier(h.props, none, h.outbox, h.packs, h.clock).available()).isFalse();
    }

    // ---- retries, dead letters, limits ------------------------------------------------------------------------------------

    @Test
    void aServerErrorIsRetriedWithBackoffThenIsADeadLetterAndAuditedWithoutTheUrl() throws Exception {
        BridgeHarness h = harness("teams", everything());
        h.status.set(503);
        h.notifier.onShare(new Notifier.ShareEvent(h.share("sh_1"), List.of()));
        assertThat(h.dispatcher.tick()).isEqualTo(1);
        OutboxItem a = h.outbox.list(null, 10).get(0);
        assertThat(a.state()).isEqualTo(OutboxItem.PENDING);
        assertThat(a.attempts()).isEqualTo(1);
        assertThat(a.nextAt()).isEqualTo(h.clock.instant().plusSeconds(30));
        assertThat(a.lastError()).isEqualTo("IllegalStateException: the endpoint answered HTTP 503");
        // the clock is fixed, so make it due and run twice more: attempts 2 (backoff 60 s) and 3 = max, dead
        h.outbox.retry(a.seq(), 1, h.clock.instant(), a.lastError());
        h.dispatcher.tick();
        assertThat(h.outbox.find(a.seq()).orElseThrow().nextAt()).isEqualTo(h.clock.instant().plusSeconds(60));
        h.outbox.retry(a.seq(), 2, h.clock.instant(), "x");
        h.dispatcher.tick();
        OutboxItem dead = h.outbox.find(a.seq()).orElseThrow();
        assertThat(dead.state()).isEqualTo(OutboxItem.DEAD);
        assertThat(dead.attempts()).isEqualTo(3);
        assertThat(h.audited).anySatisfy(e -> assertThat(e).contains("collab.bridge.dead", "|desk|", "share sh_1"));
        assertThat(h.audited.toString()).doesNotContain("xyzSECRETtoken");
        assertThat(h.hits).hasSize(3);
        // and an administrator can send it again once the endpoint is back
        h.status.set(200);
        assertThat(h.outbox.requeue(dead.seq(), h.clock.instant())).isTrue();
        h.dispatcher.tick();
        assertThat(h.outbox.find(dead.seq()).orElseThrow().state()).isEqualTo(OutboxItem.SENT);
    }

    @Test
    void aRefusalIsADeadLetterAtOnce() throws Exception {
        BridgeHarness h = harness("slack", everything());
        h.status.set(404);
        h.notifier.onShare(new Notifier.ShareEvent(h.share("sh_1"), List.of()));
        h.dispatcher.tick();
        OutboxItem i = h.outbox.list(null, 10).get(0);
        assertThat(i.state()).isEqualTo(OutboxItem.DEAD);
        assertThat(i.attempts()).isEqualTo(1);
        assertThat(i.lastError()).isEqualTo("the endpoint refused the post: HTTP 404");
        assertThat(h.hits).hasSize(1);
    }

    @Test
    void tooManyRequestsIsRetriedNotDead() throws Exception {
        BridgeHarness h = harness("slack", everything());
        h.status.set(429);
        h.notifier.onShare(new Notifier.ShareEvent(h.share("sh_1"), List.of()));
        h.dispatcher.tick();
        assertThat(h.outbox.list(null, 10).get(0).state()).isEqualTo(OutboxItem.PENDING);
    }

    @Test
    void aRedirectIsNeverFollowedAndIsADeadLetter() throws Exception {
        try (BridgeHarness elsewhere = new BridgeHarness()) {
            BridgeHarness h = harness("teams", everything());
            h.status.set(302);
            h.location.set(elsewhere.base + "/stolen");
            h.notifier.onShare(new Notifier.ShareEvent(h.share("sh_1"), List.of()));
            h.dispatcher.tick();
            assertThat(h.outbox.list(null, 10).get(0).state()).isEqualTo(OutboxItem.DEAD);
            assertThat(h.outbox.list(null, 10).get(0).lastError()).contains("redirect");
            assertThat(elsewhere.hits).isEmpty();
        }
    }

    @Test
    void aBridgeOverItsPerMinuteLimitDefersTheRowWithoutCountingAnAttempt() throws Exception {
        BridgeHarness h = new BridgeHarness().configure("json", everything(), 1, null);
        open.add(h);
        h.notifier.onShare(new Notifier.ShareEvent(h.share("sh_1"), List.of()));
        h.notifier.onShare(new Notifier.ShareEvent(h.share("sh_2"), List.of()));
        assertThat(h.dispatcher.tick()).isEqualTo(2);
        assertThat(h.hits).hasSize(1);
        List<OutboxItem> rows = h.outbox.list(OutboxItem.PENDING, 10);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).attempts()).isZero();
        assertThat(rows.get(0).lastError()).contains("1 posts a minute");
        assertThat(rows.get(0).nextAt()).isAfter(h.clock.instant());
    }

    @Test
    void aBridgeThatCannotPostMakesItsRowsDeadLettersWithTheReasonAndNoUrl() throws Exception {
        BridgeHarness h = harness("json", everything());
        h.env.remove("BRIDGE_URL");
        h.registry = new BridgeRegistry(h.props, h.env::get);
        h.sender = new BridgeSender(h.registry, h.renderer, new BridgeClient(h.props.bridges().timeout()), new com.ash.drishti.server.collab.RateLimits(),
                h.props, org.mockito.Mockito.mock(com.ash.drishti.identity.AuditLog.class), h.clock, "Drishti");
        h.dispatcher = new com.ash.drishti.server.collab.mail.OutboxDispatcher(h.outbox, h.props, h.principals, List.of(), List.of(h.sender),
                new com.ash.drishti.server.collab.mail.MailRenderer(new com.ash.drishti.server.collab.mail.MailTemplates(""), "Drishti", "x"),
                (m, f) -> {}, h.meters, h.clock);
        h.outbox.add(OutboxItem.pending("bridge", "desk", "share", "sh_1", h.clock.instant()));
        h.dispatcher.tick();
        OutboxItem i = h.outbox.list(null, 10).get(0);
        assertThat(i.state()).isEqualTo(OutboxItem.DEAD);
        assertThat(i.lastError()).isEqualTo("the bridge 'desk' cannot post: unconfigured: environment variable BRIDGE_URL is not set");
        assertThat(h.hits).isEmpty();
    }

    @Test
    void aRemovedBridgeMakesItsPendingRowsDeadLetters() throws Exception {
        BridgeHarness h = harness("json", everything());
        h.outbox.add(OutboxItem.pending("bridge", "gone", "share", "sh_1", h.clock.instant()));
        h.dispatcher.tick();
        assertThat(h.outbox.list(null, 10).get(0).state()).isEqualTo(OutboxItem.DEAD);
    }

    // ---- secrets and the admin API ----------------------------------------------------------------------------------------

    @Test
    void neitherTheUrlNorTheSecretIsEverLoggedStoredOrListed() throws Exception {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level was = root.getLevel();
        root.setLevel(Level.TRACE);
        root.addAppender(logs);
        try {
            BridgeHarness h = harness("json", everything());
            h.status.set(500);
            h.notifier.onShare(new Notifier.ShareEvent(h.share("sh_1"), List.of()));
            h.dispatcher.tick();
            h.endpoint.stop(0);                                           // now the connection itself fails
            h.outbox.add(OutboxItem.pending("bridge", "desk", "share", "sh_1", h.clock.instant()));
            h.dispatcher.tick();
            assertThatThrownBy(() -> h.sender.test("desk", "root", "https://drishti.example")).isInstanceOf(DrishtiException.class);
            // the root logger also hears other tests' background threads: read the events under the appender's own lock
            // (AppenderBase.doAppend synchronizes on it), or a concurrent append breaks the iteration
            List<String> messages;
            synchronized (logs) {
                // not the JDK's own HttpServer, which the test's stand-in endpoint uses and which logs the request line it receives
                // (a JUL bridge installed by an earlier test class in the same JVM makes it audible): only Drishti's lines matter
                messages = logs.list.stream().filter(e -> !e.getLoggerName().startsWith("com.sun.net.httpserver"))
                        .map(ILoggingEvent::getFormattedMessage).toList();
            }
            String all = h.outbox.list(null, 50).toString() + h.audited + h.registry.all() + messages;
            assertThat(all).doesNotContain("xyzSECRETtoken", "T0SECRET", BridgeHarness.SIGNING_SECRET, h.base + BridgeHarness.SECRET_PATH);
            assertThat(messages).isNotEmpty();
        } finally {
            root.detachAppender(logs);
            root.setLevel(was);
        }
    }

    @Test
    void theTestPostReportsTheStatusAndFailuresAreDrs7013AndUnknownNamesDrs7014() throws Exception {
        BridgeHarness h = harness("teams", everything());
        assertThat(h.sender.test("desk", "root", "https://drishti.example")).isEqualTo(200);
        JsonNode card = JSON.readTree(h.hits.get(0).body()).path("attachments").get(0).path("content");
        assertThat(card.path("body").get(0).path("text").asText()).isEqualTo("Test message from Drishti");
        assertThat(h.audited).anySatisfy(e -> assertThat(e).contains("root|collab.bridge.test|desk|HTTP 200"));
        h.status.set(500);
        assertThatThrownBy(() -> h.sender.test("desk", "root", "x")).isInstanceOf(DrishtiException.class).satisfies(e -> {
            assertThat(((DrishtiException) e).errorCode().code()).isEqualTo("DRS-7013");
            assertThat(e.getMessage()).contains("HTTP 500").doesNotContain("xyzSECRETtoken");
        });
        assertThatThrownBy(() -> h.sender.test("nobody", "root", "x")).isInstanceOf(DrishtiException.class)
                .satisfies(e -> assertThat(((DrishtiException) e).errorCode().code()).isEqualTo("DRS-7014"));
    }
}
