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
package com.ash.drishti.server.dist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.RetryPolicy;
import com.ash.drishti.engine.time.BusinessDateProperties;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.FilePreferenceStore;
import com.ash.drishti.server.collab.mail.MailRenderer;
import com.ash.drishti.server.collab.mail.MailTemplates;
import com.ash.drishti.server.collab.mail.RenderedMail;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;

class SubscriptionServiceTest {

    static final ObjectMapper JSON = new ObjectMapper();

    /** A clock the test moves. */
    static final class MovingClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-25T22:00:00Z");           // Wednesday 17:00 in New York

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final MovingClock clock = new MovingClock();
    final List<RenderedMail> sent = new CopyOnWriteArrayList<>();
    final List<String> failWith = new CopyOnWriteArrayList<>();          // recipients whose next send fails, with a message ("!" prefix: permanently)
    final List<String> audit = new CopyOnWriteArrayList<>();
    final List<ContentExporter.Target> targets = new CopyOnWriteArrayList<>();
    final List<String> renderedFor = new CopyOnWriteArrayList<>();
    SubscriptionService service;
    FilePreferenceStore store;

    static Principal who(String user, String... roles) {
        return new Principal(user, List.of(roles));
    }

    final SubscriptionService.Person alice = new SubscriptionService.Person("alice", "Alice", "alice@example.org", true, who("alice", "risk", "author"));
    final SubscriptionService.Person bob = new SubscriptionService.Person("bob", "Bob", "bob@example.org", true, who("bob", "risk", "masked"));
    final SubscriptionService.Person carol = new SubscriptionService.Person("carol", "Carol", "", true, who("carol", "risk"));
    final SubscriptionService.Person dave = new SubscriptionService.Person("dave", "Dave", "dave@example.org", true, who("dave", "nobody"));
    final SubscriptionService.Person erin = new SubscriptionService.Person("erin", "Erin", "erin@example.org", false, who("erin", "risk"));

    @BeforeEach
    void setUp(@TempDir Path dir) {
        service = service(dir, new DistProperties.Subscriptions(true, 3, 10, Duration.ofSeconds(30), 5, 3, 5, Duration.ofSeconds(1)));
    }

    SubscriptionService service(Path dir, DistProperties.Subscriptions props) {
        List<SubscriptionService.Person> all = List.of(alice, bob, carol, dave, erin);
        SubscriptionService.Directory directory = new SubscriptionService.Directory() {
            @Override
            public Optional<SubscriptionService.Person> person(String username) {
                return all.stream().filter(p -> p.username().equals(username)).findFirst();
            }

            @Override
            public List<SubscriptionService.Person> members(String role) {
                return all.stream().filter(p -> p.enabled() && p.principal().roles().contains(role)).toList();
            }

            @Override
            public boolean mayAddOthers(String username) {
                return username.equals("alice");
            }
        };
        // the renderer makes what the engine would: the same report, as the reader; bob has a mask, dave cannot read it
        SubscriptionService.Renderer renderer = (target, format, p) -> {
            targets.add(target);
            renderedFor.add(p.user() + ":" + format);
            if (p.roles().contains("nobody")) {
                throw new DrishtiException(ErrorCode.BI_NOT_FOUND, "no report 'daily-risk'");
            }
            String value = p.roles().contains("masked") ? "****" : "secret-" + p.user();
            ExportDoc doc = new ExportDoc("Daily risk", "Data as of " + (target.date() == null ? "2026-11-25" : target.date()), List.of("Slicer desk: FX"),
                    List.of(new ExportDoc.Section("v", "Exposure", ExportDoc.TABLE, List.of("Desk", "Value"), List.of(List.of("FX", value)), null, List.of())));
            return new ContentExporter.Output(("file-for-" + p.user()).getBytes(), format.contentType(), "Daily risk." + format.extension(), 1, doc);
        };
        AuditLog log = new AuditLog() {
            @Override
            public void record(String actor, String action, String subject, String detail) {
                audit.add(actor + " " + action + " " + subject);
            }

            @Override
            public List<Event> recent(int limit, String subject) {
                return List.of();
            }
        };
        com.ash.drishti.server.collab.mail.MailTransport transport = (mail, from) -> {
            String fail = failWith.stream().filter(f -> f.replace("!", "").equals(mail.to())).findFirst().orElse(null);
            if (fail != null) {
                failWith.remove(fail);
                if (fail.startsWith("!")) {
                    throw new MailPreparationException(new jakarta.mail.internet.AddressException("refused " + mail.to()));
                }
                throw new MailSendException("connection reset by peer");
            }
            sent.add(mail);
        };
        store = new FilePreferenceStore(dir, 65_536, 50);
        return new SubscriptionService(store, renderer, (t, p) -> {
            if (p.roles().contains("nobody")) {
                throw new DrishtiException(ErrorCode.BI_NOT_FOUND, "no report");
            }
        }, directory, log, new MailRenderer(new MailTemplates(null), "Drishti", "example.org"), transport, "drishti@example.org", "http://console", props,
                RetryPolicy.defaults(), new BusinessDates(new BusinessDateProperties("USNY", "America/New_York", null), clock), clock, false);
    }

    static JsonNode body(String json) {
        try {
            return JSON.readTree(json);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static final String TARGET = "\"target\":{\"type\":\"report\",\"id\":\"daily-risk\",\"page\":\"overview\","
            + "\"state\":{\"slicers\":{\"desk\":[\"FX\"]}},\"view\":\"ctl.desk=FX\"}";

    JsonNode save(String name, String extra) {
        return service.save("alice", name, body("{" + TARGET + ",\"schedule\":\"business-days 07:30\",\"format\":\"html\"," + extra + "}"));
    }

    @Test
    void everyRecipientGetsTheirOwnRenderWithTheirMasksNeverTheSenders() {
        save("morning", "\"recipients\":[\"alice\",\"role:risk\"]");
        JsonNode run = service.run("alice", "morning", "by hand");
        assertThat(run.path("sent").asInt()).isEqualTo(2);                          // alice and bob; carol has no address, erin is disabled
        assertThat(run.path("skipped").asInt()).isEqualTo(1);                       // carol
        assertThat(sent).extracting(RenderedMail::to).containsExactlyInAnyOrder("alice@example.org", "bob@example.org");
        assertThat(renderedFor).containsExactlyInAnyOrder("alice:HTML", "bob:HTML");           // carol has no address, so nothing is rendered for her
        RenderedMail toAlice = sent.stream().filter(m -> m.to().startsWith("alice")).findFirst().orElseThrow();
        RenderedMail toBob = sent.stream().filter(m -> m.to().startsWith("bob")).findFirst().orElseThrow();
        assertThat(toAlice.html()).contains("secret-alice").doesNotContain("****");
        assertThat(toBob.html()).contains("****").doesNotContain("secret-alice").doesNotContain("secret-bob");
        assertThat(toBob.text()).contains("****").doesNotContain("secret");
        assertThat(toAlice.html()).contains("Slicer desk: FX").contains("http://console/bi/r/daily-risk/overview");
        assertThat(toAlice.subject()).contains("Daily risk").contains("2026-11-25");
        assertThat(toAlice.messageId()).isNotEqualTo(toBob.messageId());
    }

    @Test
    void theViewStateAndTheBusinessDateRuleReachTheRender() {
        save("prev", "\"recipients\":[\"alice\"],\"date\":\"previous\"");
        service.run("alice", "prev", "by hand");
        ContentExporter.Target t = targets.get(0);
        assertThat(t.state().path("slicers").path("desk").get(0).asText()).isEqualTo("FX");
        assertThat(t.view()).isEqualTo("ctl.desk=FX");
        assertThat(t.page()).isEqualTo("overview");
        assertThat(t.date()).isEqualTo("2026-11-24");                                // Tuesday: the business day before Wednesday 25th
        assertThat(sent.get(0).html()).contains("2026-11-24");
    }

    @Test
    void aFileFormatIsAttachedAndTheBodyOnlySummarises() {
        service.save("alice", "xl", body("{" + TARGET + ",\"schedule\":\"daily 07:00\",\"format\":\"xlsx\",\"recipients\":[\"alice\",\"bob\"]}"));
        service.run("alice", "xl", "by hand");
        assertThat(renderedFor).containsExactlyInAnyOrder("alice:XLSX", "bob:XLSX");
        RenderedMail bob = sent.stream().filter(m -> m.to().startsWith("bob")).findFirst().orElseThrow();
        assertThat(bob.files()).hasSize(1);
        assertThat(new String(bob.files().get(0).bytes())).isEqualTo("file-for-bob");          // the attachment is bob's render
        assertThat(bob.files().get(0).name()).isEqualTo("Daily risk.xlsx");
        assertThat(bob.html()).contains("attached").doesNotContain("secret");
    }

    @Test
    void someoneWhoCannotOpenTheTargetIsSkippedWithTheReasonNotMailedARefusal() {
        service.save("alice", "wide", body("{" + TARGET + ",\"schedule\":\"daily 07:00\",\"format\":\"html\",\"recipients\":[\"alice\",\"dave\"]}"));
        JsonNode run = service.run("alice", "wide", "by hand");
        assertThat(run.path("sent").asInt()).isEqualTo(1);
        assertThat(run.path("skipped").asInt()).isEqualTo(1);
        JsonNode d = run.path("deliveries");
        assertThat(d.get(1).path("status").asText()).isEqualTo("skipped");
        assertThat(d.get(1).path("error").asText()).contains("no report");
        assertThat(sent).extracting(RenderedMail::to).containsExactly("alice@example.org");
    }

    @Test
    void aFailedSendIsRetriedWithBackoffAndThenDelivered() {
        save("retry", "\"recipients\":[\"alice\",\"bob\"]");
        failWith.add("bob@example.org");
        JsonNode run = service.run("alice", "retry", "by hand");
        assertThat(run.path("status").asText()).isEqualTo("retrying");
        assertThat(run.path("sent").asInt()).isEqualTo(1);
        JsonNode bob = run.path("deliveries").get(1);
        assertThat(bob.path("status").asText()).isEqualTo("retry");
        assertThat(bob.path("attempts").asInt()).isEqualTo(1);
        assertThat(bob.path("error").asText()).contains("connection reset");
        Instant next = Instant.parse(bob.path("nextAttempt").asText());
        assertThat(next).isAfter(clock.now).isBeforeOrEqualTo(clock.now.plus(Duration.ofSeconds(7)));       // the shared policy: 5 s, 20 percent jitter
        // not yet: nothing happens before the wait is over
        service.retryDeliveries("alice", "retry");
        assertThat(sent).hasSize(1);
        clock.now = next.plusSeconds(1);
        service.retryDeliveries("alice", "retry");
        assertThat(sent).extracting(RenderedMail::to).containsExactly("alice@example.org", "bob@example.org");
        JsonNode after = service.get("alice", "retry").orElseThrow().path("runs").get(0);
        assertThat(after.path("status").asText()).isEqualTo("ok");
        assertThat(after.path("deliveries").get(1).path("attempts").asInt()).isEqualTo(2);
        assertThat(service.get("alice", "retry").orElseThrow().path("runs")).hasSize(1);                  // a retry continues its run, it is not a new one
    }

    @Test
    void aDeliveryGivesUpAfterMaxAttemptsAndARefusedAddressIsNeverRetried() {
        save("giveup", "\"recipients\":[\"alice\",\"bob\"]");
        failWith.addAll(List.of("bob@example.org", "bob@example.org", "bob@example.org"));
        service.run("alice", "giveup", "by hand");
        for (int i = 0; i < 2; i++) {
            clock.now = clock.now.plus(Duration.ofMinutes(10));
            service.retryDeliveries("alice", "giveup");
        }
        JsonNode run = service.get("alice", "giveup").orElseThrow().path("runs").get(0);
        assertThat(run.path("deliveries").get(1).path("status").asText()).isEqualTo("failed");
        assertThat(run.path("deliveries").get(1).path("attempts").asInt()).isEqualTo(3);
        assertThat(run.path("status").asText()).isEqualTo("partial");
        // a refusal is permanent at once
        failWith.add("!bob@example.org");
        JsonNode second = service.run("alice", "giveup", "by hand");
        assertThat(second.path("deliveries").get(1).path("status").asText()).isEqualTo("failed");
        assertThat(second.path("deliveries").get(1).path("attempts").asInt()).isEqualTo(1);
    }

    @Test
    void scheduleIsBusinessDayAwareAndPauseAndResumeMoveIt() {
        JsonNode s = save("sched", "\"recipients\":[\"alice\"]");
        // Wednesday 25 Nov 17:00 NY: the next business day 07:30 is Friday 27th (Thursday is Thanksgiving)
        assertThat(Instant.parse(s.path("nextRun").asText())).isEqualTo(Instant.parse("2026-11-27T12:30:00Z"));
        JsonNode paused = service.setEnabled("alice", "sched", false, "alice");
        assertThat(paused.path("enabled").asBoolean()).isFalse();
        assertThat(paused.path("nextRun").isNull()).isTrue();
        clock.now = Instant.parse("2026-12-01T22:00:00Z");
        JsonNode resumed = service.setEnabled("alice", "sched", true, "alice");
        assertThat(Instant.parse(resumed.path("nextRun").asText())).isEqualTo(Instant.parse("2026-12-02T12:30:00Z"));      // from now, no catching up
        assertThat(audit).contains("alice subscription.pause subscription/alice/sched", "alice subscription.resume subscription/alice/sched");
    }

    @Test
    void theTickRunsWhatIsDueAndAfterLoadWaitsForTheSignal() throws Exception {
        save("clock", "\"recipients\":[\"alice\"]");
        service.save("alice", "loaded", body("{" + TARGET + ",\"schedule\":\"after-load TRD\",\"format\":\"html\",\"recipients\":[\"alice\"],\"date\":\"load\"}"));
        assertThat(service.get("alice", "loaded").orElseThrow().path("nextRun").isNull()).isTrue();
        service.tick();
        Thread.sleep(150);
        assertThat(sent).isEmpty();                                                  // nothing is due yet
        service.onLoad("FX", LocalDate.of(2026, 11, 25));                            // another kind: not ours
        assertThat(service.get("alice", "loaded").orElseThrow().path("nextRun").isNull()).isTrue();
        service.onLoad("TRD", LocalDate.of(2026, 11, 25));
        assertThat(Instant.parse(service.get("alice", "loaded").orElseThrow().path("nextRun").asText())).isEqualTo(clock.now.plusSeconds(1));
        clock.now = Instant.parse("2026-11-27T12:31:00Z");                           // the "clock" subscription is due too
        service.tick();
        for (int i = 0; i < 100 && sent.size() < 2; i++) {
            Thread.sleep(50);
        }
        assertThat(sent).hasSize(2);
        assertThat(targets).extracting(ContentExporter.Target::date).containsExactlyInAnyOrder(null, "2026-11-25");   // load date used by the after-load one
        assertThat(service.get("alice", "loaded").orElseThrow().path("nextRun").isNull()).isTrue();
    }

    @Test
    void rulesForWhoMayBeAddedAndWhatIsAccepted() {
        assertThatThrownBy(() -> service.save("bob", "x", body("{" + TARGET + ",\"schedule\":\"daily 07:00\",\"format\":\"html\",\"recipients\":[\"alice\"]}")))
                .hasMessageContaining("only a BI author or an administrator may subscribe other people");
        assertThatThrownBy(() -> service.save("bob", "x", body("{" + TARGET + ",\"schedule\":\"daily 07:00\",\"format\":\"html\",\"recipients\":[\"role:risk\"]}")))
                .hasMessageContaining("may subscribe a role");
        assertThatThrownBy(() -> save("x", "\"recipients\":[\"nobody@elsewhere.com\"]")).hasMessageContaining("is an email address, not a user");
        assertThatThrownBy(() -> save("x", "\"recipients\":[\"nosuchuser\"]")).hasMessageContaining("no user 'nosuchuser'");
        assertThatThrownBy(() -> save("x", "\"recipients\":[]")).hasMessageContaining("at least one recipient");
        assertThatThrownBy(() -> service.save("alice", "x", body("{" + TARGET + ",\"schedule\":\"daily 07:00\",\"format\":\"docx\",\"recipients\":[\"alice\"]}")))
                .hasMessageContaining("format is html");
        assertThatThrownBy(() -> service.save("alice", "x", body("{\"target\":{\"type\":\"view\",\"id\":\"TRD\"},\"schedule\":\"daily 07:00\",\"format\":\"html\",\"recipients\":[\"alice\"]}")))
                .hasMessageContaining("kind/id");
        assertThatThrownBy(() -> service.save("alice", "x", body("{" + TARGET + ",\"schedule\":\"whenever\",\"format\":\"html\",\"recipients\":[\"alice\"]}")))
                .hasMessageContaining("not a schedule");
        assertThatThrownBy(() -> service.save("dave", "x", body("{" + TARGET + ",\"schedule\":\"daily 07:00\",\"format\":\"html\",\"recipients\":[\"dave\"]}")))
                .hasMessageContaining("cannot use this target yourself");
        assertThatThrownBy(() -> service.save("alice", "bad/name", body("{" + TARGET + ",\"schedule\":\"daily 07:00\",\"format\":\"html\",\"recipients\":[\"alice\"]}")))
                .hasMessageContaining("name is 1-64");
        save("one", "\"recipients\":[\"alice\"]");
        save("two", "\"recipients\":[\"alice\"]");
        save("three", "\"recipients\":[\"alice\"]");
        assertThatThrownBy(() -> save("four", "\"recipients\":[\"alice\"]")).hasMessageContaining("at most 3 subscriptions");
        assertThat(service.of("alice")).hasSize(3);
        assertThat(service.delete("alice", "one")).isTrue();
        assertThat(service.delete("alice", "one")).isFalse();
    }

    @Test
    void outsideAddressesAreRefusedAndOldOnesAreSkippedAtSend() {
        String base = "{" + TARGET + ",\"schedule\":\"daily 07:00\",\"format\":\"html\",\"recipients\":[";
        assertThatThrownBy(() -> service.save("alice", "x", body(base + "\"cfo@partner.com\"]}"))).hasMessageContaining("is an email address, not a user");
        assertThatThrownBy(() -> service.save("alice", "x", body(base + "\"alice\",\"cfo@partner.com\"]}"))).hasMessageContaining("users and roles only");
        // a subscription saved before the rule keeps its address; the address is skipped at send, with the reason
        save("old", "\"recipients\":[\"alice\"]");
        com.fasterxml.jackson.databind.node.ObjectNode stored = service.get("alice", "old").orElseThrow().deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) stored.path("recipients")).add("cfo@partner.com");
        store.put("alice", SubscriptionService.NS, "old", stored);
        var run = service.run("alice", "old", "by hand");
        assertThat(sent).extracting(RenderedMail::to).containsExactly("alice@example.org");
        assertThat(run.path("skipped").asInt()).isEqualTo(1);
        assertThat(run.toString()).contains("outside address").contains("cfo@partner.com");
    }

    @Test
    void historyKeepsTheLatestRunsAndTooManyRecipientsFailTheRun() {
        save("hist", "\"recipients\":[\"alice\"]");
        for (int i = 0; i < 7; i++) {
            service.run("alice", "hist", "by hand");
        }
        JsonNode runs = service.get("alice", "hist").orElseThrow().path("runs");
        assertThat(runs).hasSize(5);                                                         // keep-runs
        assertThat(runs.get(0).has("deliveries")).isTrue();
        assertThat(runs.get(4).has("deliveries")).isFalse();                                 // old runs keep their counts, not their detail
        assertThat(runs.get(4).path("sent").asInt()).isEqualTo(1);
        assertThat(service.all()).hasSize(1);
    }
}
