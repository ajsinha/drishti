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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.engine.time.BusinessDateProperties;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.FilePreferenceStore;
import com.ash.drishti.server.alerts.measure.AlertLogic.Reading;
import com.ash.drishti.server.bi.board.Board;
import com.ash.drishti.server.dist.SubscriptionService.Person;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The alert service with its sources, channels and clock replaced: who is judged on what, when an alert looks (a schedule, a data load, a live change after a
 * quiet time), what it remembers (active, last fired, the cooldown), what it tells and to whom, and that a number masked for a person is never judged or sent.
 */
class MeasureAlertServiceTest {

    static final ObjectMapper JSON = new ObjectMapper();

    /** A clock the test moves. */
    static final class MovingClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-25T15:00:00Z");            // Wednesday 10:00 in New York

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

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    final MovingClock clock = new MovingClock();
    final Map<String, Reading> readings = new ConcurrentHashMap<>();           // "user" -> what the source gives that person now
    final Map<String, Reading> earlier = new ConcurrentHashMap<>();            // "user" -> what it gave on the previous business day
    final AtomicInteger reads = new AtomicInteger();
    final AtomicLong liveGeneration = new AtomicLong(1);
    final List<String> inbox = new CopyOnWriteArrayList<>();
    final List<String> emails = new CopyOnWriteArrayList<>();
    final List<String> bridge = new CopyOnWriteArrayList<>();
    final AtomicInteger emailTries = new AtomicInteger();
    volatile java.util.function.IntFunction<String> emailRule = n -> "sent";       // what the n-th email attempt answers
    final List<String> hooks = new CopyOnWriteArrayList<>();
    final List<String> texts = new CopyOnWriteArrayList<>();
    final List<String> audit = new CopyOnWriteArrayList<>();
    final List<AlertSpec> boardSpecs = new CopyOnWriteArrayList<>();
    final List<Person> boardAudience = new CopyOnWriteArrayList<>();
    MeasureAlertService service;
    FilePreferenceStore store;

    static Principal who(String user) {
        return new Principal(user, List.of("viewer"));
    }

    final Person alice = new Person("alice", "Alice", "alice@example.org", true, who("alice"));
    final Person bob = new Person("bob", "Bob", "", true, who("bob"));
    final Person carol = new Person("carol", "Carol", "carol@example.org", true, who("carol"));
    final Person dave = new Person("dave", "Dave", "dave@example.org", true, who("dave"));

    static Reading ok(double v) {
        return Reading.ok(v, LocalDate.of(2026, 11, 25));
    }

    static MeasureAlertProperties props() {
        return new MeasureAlertProperties(true, 3, Duration.ofSeconds(15), Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(1),
                Duration.ofMinutes(2), Duration.ZERO, Duration.ofMinutes(30), 20, true, 2, true, true);
    }

    @BeforeEach
    void setUp(@TempDir Path dir) {
        store = new FilePreferenceStore(dir, 65_536, 50);
        service = build(props());
    }

    MeasureAlertService build(MeasureAlertProperties props) {
        List<Person> all = List.of(alice, bob, carol, dave);
        ValueReader reader = (spec, p, date) -> {
            reads.incrementAndGet();
            Map<String, Reading> from = date == null ? readings : earlier;
            return from.getOrDefault(p.user(), Reading.noData("nothing set for " + p.user()));
        };
        AlertEnvironment.People people = name -> all.stream().filter(p -> p.username().equals(name)).findFirst();
        AlertEnvironment.Checker checker = (spec, p) -> {
            if (p.user().equals("dave")) {
                throw new DrishtiException(com.ash.drishti.common.ErrorCode.BI_FORBIDDEN, "no access to the report");
            }
        };
        AlertEnvironment.Live live = (spec, p) -> spec.source().type().equals("dataset") ? liveGeneration.get() : -1;
        AlertEnvironment.Boards boards = new AlertEnvironment.Boards() {
            @Override
            public List<AlertSpec> specs() {
                return boardSpecs;
            }

            @Override
            public List<Person> audience(AlertSpec spec, int max) {
                return boardAudience.size() > max + 1 ? boardAudience.subList(0, max + 1) : boardAudience;
            }
        };
        AlertChannels channels = new AlertChannels() {
            @Override
            public String inbox(Fired f) {
                inbox.add(f.person().username() + ": " + f.message());
                return "ok";
            }

            @Override
            public String email(Fired f) {
                emails.add(f.person().username());
                return f.person().email().isBlank() ? "skipped: no email address on file" : emailRule.apply(emailTries.incrementAndGet());
            }

            @Override
            public List<String> webhookNames() {
                return List.of("ops");
            }

            @Override
            public boolean smsConfigured() {
                return true;
            }

            @Override
            public boolean hasPhone(String username) {
                return username.equals("alice");
            }

            @Override
            public String webhook(Fired f, String name) {
                hooks.add(name + ": " + f.spec().title() + " " + f.severity());
                return "sent";
            }

            @Override
            public String sms(Fired f) {
                texts.add(f.person().username() + ": " + f.spec().title());
                return "sent";
            }

            @Override
            public String bridge(Fired f) {
                bridge.add(f.key());
                return "queued for 1 bridge";
            }
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
        return new MeasureAlertService(store, reader, checker, people, live, boards, channels, log, props,
                new BusinessDates(new BusinessDateProperties("USNY", "America/New_York", null), clock), clock, false);
    }

    static JsonNode body(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String alert(String condition, String trigger, String extra) {
        return "{\"source\":{\"type\":\"dataset\",\"dataset\":\"trading\",\"measure\":\"exposure\"},\"condition\":" + condition + ",\"trigger\":" + trigger
                + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    static final String ABOVE_100 = "{\"type\":\"threshold\",\"op\":\"above\",\"value\":100}";
    static final String HOURLY = "{\"mode\":\"schedule\",\"schedule\":\"hourly\"}";

    List<JsonNode> history(String owner, String name) {
        List<JsonNode> out = new ArrayList<>();
        service.get(owner, name).orElseThrow().path("history").forEach(out::add);
        return out;
    }

    // ---- saving, firing, remembering ---------------------------------------------------------------------------------------------

    @Test
    void aNewAlertThatAlreadyHoldsFiresOnceAndIsRemembered() {
        readings.put("alice", ok(120));
        service.save("alice", "Exposure", body(alert(ABOVE_100, HOURLY, "")));
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0)).startsWith("alice: ").contains("120").contains("above 100");
        List<JsonNode> h = history("alice", "Exposure");
        assertThat(h).hasSize(1);
        assertThat(h.get(0).path("action").asText()).isEqualTo("fired");
        assertThat(h.get(0).path("delivered").path("inbox").asText()).isEqualTo("ok");
        assertThat(h.get(0).path("delivered").path("email").asText()).isEqualTo("off");
        assertThat(audit).contains("alice alert.save alert/alice/Exposure", "alice alert.fire alert/alice/Exposure");
        service.check("alice", "Exposure");                                          // looked at again: still above, it does not repeat
        assertThat(inbox).hasSize(1);
        assertThat(service.get("alice", "Exposure").orElseThrow().path("state").path("alice").path("active").asBoolean()).isTrue();
    }

    @Test
    void hysteresisAndCooldownKeepItFromFlapping() {
        readings.put("alice", ok(90));
        service.save("alice", "Exposure", body(alert(ABOVE_100, HOURLY, "\"hysteresis\":10,\"cooldown\":\"30m\"")));
        assertThat(inbox).isEmpty();
        readings.put("alice", ok(105));
        service.check("alice", "Exposure");
        assertThat(inbox).hasSize(1);
        readings.put("alice", ok(95));                                               // under 100 but not by 10: still active
        service.check("alice", "Exposure");
        readings.put("alice", ok(103));
        service.check("alice", "Exposure");
        assertThat(inbox).hasSize(1);
        readings.put("alice", ok(85));                                               // under 90: armed again
        service.check("alice", "Exposure");
        readings.put("alice", ok(110));                                              // breaches again inside the cooldown: held back
        clock.advance(Duration.ofMinutes(10));
        service.check("alice", "Exposure");
        assertThat(inbox).hasSize(1);
        assertThat(history("alice", "Exposure").get(0).path("action").asText()).isEqualTo("held");
        service.check("alice", "Exposure");                                          // the same hold is not written twice
        assertThat(history("alice", "Exposure")).hasSize(history("alice", "Exposure").size());
        long held = history("alice", "Exposure").stream().filter(e -> e.path("action").asText().equals("held")).count();
        assertThat(held).isEqualTo(1);
        clock.advance(Duration.ofMinutes(25));                                       // the cooldown (30m after the first firing) is over: it fires now
        service.check("alice", "Exposure");
        assertThat(inbox).hasSize(2);
    }

    @Test
    void aNumberMaskedForThePersonIsNeverJudgedOrSent() {
        readings.put("bob", Reading.masked("exposure"));
        service.save("bob", "Exposure", body(alert(ABOVE_100, HOURLY, "")));
        assertThat(inbox).isEmpty();
        List<JsonNode> h = history("bob", "Exposure");
        assertThat(h).hasSize(1);
        assertThat(h.get(0).path("action").asText()).isEqualTo("skipped");
        assertThat(h.get(0).path("message").asText()).contains("masked for you");
        assertThat(h.get(0).has("value")).isFalse();
        service.check("bob", "Exposure");
        assertThat(history("bob", "Exposure")).hasSize(1);                           // the same reason is not written again
    }

    @Test
    void changeComparesWithTheLastLookOrThePreviousBusinessDay() {
        readings.put("alice", ok(100));
        service.save("alice", "Move", body(alert("{\"type\":\"change\",\"mode\":\"pct\",\"op\":\"moves\",\"value\":10,\"against\":\"previous-run\"}", HOURLY, "")));
        assertThat(inbox).isEmpty();                                                 // the first look only sets the baseline
        assertThat(history("alice", "Move").get(0).path("message").asText()).contains("no earlier number");
        readings.put("alice", ok(105));
        service.check("alice", "Move");
        assertThat(inbox).isEmpty();
        readings.put("alice", ok(125));                                              // +19% on the last look
        service.check("alice", "Move");
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0)).contains("up 19.05%").contains("from 105");

        readings.put("alice", ok(200));
        earlier.put("alice", ok(100));
        service.save("alice", "Day", body(alert("{\"type\":\"change\",\"mode\":\"abs\",\"op\":\"rises\",\"value\":50,\"against\":\"previous-day\"}", HOURLY, "")));
        assertThat(inbox).hasSize(2);
        assertThat(inbox.get(1)).contains("up 100").contains("from 100").contains("previous day");
    }

    @Test
    void noDataFiresAtTheDeadlineAndClearsWhenTheDataLands() {
        clock.now = Instant.parse("2026-11-25T13:00:00Z");                           // 08:00 in New York
        readings.put("alice", Reading.noData("no rows"));
        service.save("alice", "Landed", body(alert("{\"type\":\"nodata\",\"by\":\"09:00\"}", HOURLY, "")));
        assertThat(inbox).isEmpty();
        clock.advance(Duration.ofMinutes(90));                                       // 09:30
        service.tick();
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0)).contains("no data for 2026-11-25").contains("by 09:00");
        service.tick();                                                              // not again the same day
        assertThat(inbox).hasSize(1);
        readings.put("alice", ok(5));
        service.onLoad("TRD", LocalDate.of(2026, 11, 25));
        clock.advance(Duration.ofSeconds(6));
        service.tick();
        assertThat(history("alice", "Landed").get(0).path("action").asText()).isEqualTo("cleared");
    }

    @Test
    void aStaleAsOfDateCountsAsNoDataForTheDeadline() {
        clock.now = Instant.parse("2026-11-25T15:00:00Z");                           // 10:00, past 09:00
        readings.put("alice", Reading.ok(7, LocalDate.of(2026, 11, 24)));            // yesterday's data only
        service.save("alice", "Landed", body(alert("{\"type\":\"nodata\",\"by\":\"09:00\"}", HOURLY, "")));
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0)).contains("no data for 2026-11-25");
    }

    // ---- triggers ----------------------------------------------------------------------------------------------------------------

    @Test
    void aScheduledAlertLooksWhenItsTimeComes() {
        readings.put("alice", ok(90));
        service.save("alice", "Hourly", body(alert(ABOVE_100, HOURLY, "")));
        int base = reads.get();
        service.tick();                                                              // first tick only learns the next time
        assertThat(reads.get()).isEqualTo(base);
        readings.put("alice", ok(130));
        clock.advance(Duration.ofMinutes(61));
        service.tick();
        assertThat(reads.get()).isGreaterThan(base);
        assertThat(inbox).hasSize(1);
    }

    @Test
    void anAfterLoadAlertWaitsForTheLoadSignalAndTheDelay() {
        readings.put("alice", ok(90));
        service.save("alice", "Load", body(alert(ABOVE_100, "{\"mode\":\"after-load\"}", "")));
        readings.put("alice", ok(150));
        clock.advance(Duration.ofMinutes(5));
        service.tick();
        assertThat(inbox).isEmpty();                                                 // no load yet: nothing happens
        service.onLoad("TRD", LocalDate.of(2026, 11, 25));
        service.tick();
        assertThat(inbox).isEmpty();                                                 // the BI layers are still rebuilding
        clock.advance(Duration.ofSeconds(6));
        service.tick();
        assertThat(inbox).hasSize(1);
    }

    @Test
    void aLoadKindRestrictsWhichLoadsCount() {
        readings.put("alice", ok(150));
        service.save("alice", "Load", body(alert("{\"type\":\"threshold\",\"op\":\"above\",\"value\":100}", "{\"mode\":\"after-load\",\"kind\":\"POS\"}", "\"cooldown\":\"0\"")));
        inbox.clear();
        service.setEnabled("alice", "Load", true, "alice");                          // re-armed
        inbox.clear();
        readings.put("alice", ok(50));
        service.check("alice", "Load");
        readings.put("alice", ok(150));
        service.onLoad("TRD", LocalDate.of(2026, 11, 25));
        clock.advance(Duration.ofSeconds(6));
        service.tick();
        assertThat(inbox).isEmpty();                                                 // a TRD load is not the one it follows
        service.onLoad("POS", LocalDate.of(2026, 11, 25));
        clock.advance(Duration.ofSeconds(6));
        service.tick();
        assertThat(inbox).hasSize(1);
    }

    @Test
    void aLiveAlertWaitsForQuietBeforeLookingAndLooksAtLeastEveryMaxWait() {
        readings.put("alice", ok(90));
        service.save("alice", "Live", body(alert(ABOVE_100, "{\"mode\":\"live\",\"debounce\":\"10s\"}", "\"cooldown\":\"0\"")));
        service.pollLive();                                                          // the first look establishes the baseline: it reads once
        int afterFirst = reads.get();
        service.pollLive();
        assertThat(reads.get()).isEqualTo(afterFirst);                               // nothing changed: no reads
        liveGeneration.incrementAndGet();
        clock.advance(Duration.ofSeconds(2));
        service.pollLive();
        assertThat(reads.get()).isEqualTo(afterFirst);                               // changed, but the debounce is not over
        liveGeneration.incrementAndGet();                                            // another change: the quiet time starts again
        clock.advance(Duration.ofSeconds(9));
        service.pollLive();
        assertThat(reads.get()).isEqualTo(afterFirst);
        readings.put("alice", ok(140));
        clock.advance(Duration.ofSeconds(11));                                       // quiet for 11 seconds
        service.pollLive();
        assertThat(reads.get()).isEqualTo(afterFirst + 1);
        assertThat(inbox).hasSize(1);
        service.pollLive();
        assertThat(reads.get()).isEqualTo(afterFirst + 1);                           // one look for the burst
        for (int i = 0; i < 20; i++) {                                               // changes that never go quiet still get a look every live-max-wait
            liveGeneration.incrementAndGet();
            clock.advance(Duration.ofSeconds(8));
            service.pollLive();
        }
        assertThat(reads.get()).isBetween(afterFirst + 2, afterFirst + 3);
    }

    @Test
    void liveNeedsASourceWithALiveTableBehindIt() {
        assertThatThrownBy(() -> service.save("alice", "Live", body("{\"source\":{\"type\":\"field\",\"kind\":\"TRD\",\"id\":\"T-1\",\"field\":\"mtm\"},\"condition\":" + ABOVE_100
                + ",\"trigger\":{\"mode\":\"live\"}}"))).isInstanceOf(DrishtiException.class).hasMessageContaining("live table");
    }

    // ---- pause, limits, validation -------------------------------------------------------------------------------------------------

    @Test
    void aPausedAlertDoesNotLookAndAResumedOneIsArmedAfresh() {
        readings.put("alice", ok(90));
        service.save("alice", "Exposure", body(alert(ABOVE_100, HOURLY, "\"cooldown\":\"0\"")));
        service.setEnabled("alice", "Exposure", false, "alice");
        readings.put("alice", ok(150));
        clock.advance(Duration.ofHours(2));
        service.tick();
        service.tick();
        assertThat(inbox).isEmpty();
        service.setEnabled("alice", "Exposure", true, "alice");
        assertThat(inbox).hasSize(1);
        assertThat(audit).contains("alice alert.pause alert/alice/Exposure", "alice alert.resume alert/alice/Exposure");
    }

    @Test
    void savingChecksTheSourceTheDeliveryAndTheLimit() {
        assertThatThrownBy(() -> service.save("dave", "x", body(alert(ABOVE_100, HOURLY, "")))).hasMessageContaining("you cannot use this source yourself");
        assertThatThrownBy(() -> service.save("bob", "x", body(alert(ABOVE_100, HOURLY, "\"delivery\":{\"email\":true}")))).hasMessageContaining("no email address");
        assertThatThrownBy(() -> service.save("nobody", "x", body(alert(ABOVE_100, HOURLY, "")))).hasMessageContaining("no enabled user");
        readings.put("alice", ok(1));
        service.save("alice", "one", body(alert(ABOVE_100, HOURLY, "")));
        service.save("alice", "two", body(alert(ABOVE_100, HOURLY, "")));
        service.save("alice", "three", body(alert(ABOVE_100, HOURLY, "")));
        assertThatThrownBy(() -> service.save("alice", "four", body(alert(ABOVE_100, HOURLY, "")))).hasMessageContaining("at most 3 alerts");
        service.save("alice", "three", body(alert("{\"type\":\"threshold\",\"op\":\"below\",\"value\":5}", HOURLY, "")));       // an edit is not a new one
        assertThat(service.of("alice")).hasSize(3);
        assertThat(service.delete("alice", "one")).isTrue();
        assertThat(service.delete("alice", "one")).isFalse();
        MeasureAlertService off = build(new MeasureAlertProperties(false, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        assertThatThrownBy(() -> off.save("alice", "x", body(alert(ABOVE_100, HOURLY, "")))).hasMessageContaining("turned off");
    }

    @Test
    void emailGoesOnlyToUsersWithAnAddressAndOnlyWhenAsked() {
        readings.put("alice", ok(150));
        service.save("alice", "Mail", body(alert(ABOVE_100, HOURLY, "\"delivery\":{\"inbox\":true,\"email\":true}")));
        assertThat(emails).containsExactly("alice");
        assertThat(history("alice", "Mail").get(0).path("delivered").path("email").asText()).isEqualTo("sent");
        MeasureAlertService noMail = build(new MeasureAlertProperties(true, null, null, null, null, null, null, null, Duration.ZERO, null, null, null, null, false, null));
        assertThatThrownBy(() -> noMail.save("alice", "x", body(alert(ABOVE_100, HOURLY, "\"delivery\":{\"email\":true}")))).hasMessageContaining("email delivery is turned off");
    }

    @Test
    void preview_readsForThePersonWithoutSavingOrSending() {
        readings.put("alice", ok(150));
        Map<String, Object> p = service.preview("alice", body(alert(ABOVE_100, HOURLY, "")));
        assertThat(p).containsEntry("status", "ok").containsEntry("value", 150.0).containsEntry("holds", true).containsEntry("live", true);
        assertThat(service.of("alice")).isEmpty();
        assertThat(inbox).isEmpty();
        readings.put("bob", Reading.masked("exposure"));
        assertThat(service.preview("bob", body(alert(ABOVE_100, HOURLY, "")))).containsEntry("status", "masked").containsEntry("value", null);
    }

    // ---- board tiles ---------------------------------------------------------------------------------------------------------------

    AlertSpec tile(String cooldown, boolean bridge) {
        return new AlertSpec("t1", "alice", "Desk wall · Net exposure", "critical", true, new AlertSpec.Source("tile", "desk", "", "net", null, "", "", "first", null, "", "", "", null),
                new AlertSpec.Condition("level", "", 0, 0, "", "", null, "crit"), new AlertSpec.Trigger("after-load", "", "", Duration.ZERO), 0,
                cooldown.equals("0") ? Duration.ZERO : Duration.ofHours(1), new AlertSpec.Delivery(true, true, bridge), new AlertSpec.BoardRef("file:board:wall", "p1", "t1"));
    }

    @Test
    void aTileThatTurnsRedTellsEachReaderOfTheBoardUnderTheirOwnReading() {
        boardSpecs.add(tile("1h", true));
        boardAudience.addAll(List.of(alice, carol));
        readings.put("alice", ok(2));                                                // red for alice
        readings.put("carol", Reading.masked("net"));                                // masked for carol: she is told nothing
        service.onLoad("TRD", LocalDate.of(2026, 11, 25));
        clock.advance(Duration.ofSeconds(6));
        service.tick();
        assertThat(inbox).containsExactly("alice: Desk wall · Net exposure — is now red");
        assertThat(emails).containsExactly("alice");
        List<JsonNode> board = service.boardAlerts();
        assertThat(board).hasSize(1);
        assertThat(board.get(0).path("activeFor").asLong()).isEqualTo(1);
        List<String> actions = new ArrayList<>();
        board.get(0).path("history").forEach(e -> actions.add(e.path("user").asText() + ":" + e.path("action").asText()));
        assertThat(actions).containsExactlyInAnyOrder("alice:fired", "carol:skipped");
        board.get(0).path("history").forEach(e -> assertThat(e.path("message").asText()).doesNotContain("secret"));
    }

    @Test
    void aBoardIdWithColonsIsKeptUnderANameTheStoreAccepts() {
        assertThat(MeasureAlertService.boardKey("wall")).isEqualTo("wall");
        assertThat(MeasureAlertService.boardKey("file:board:desk-alerts")).startsWith("file_board_desk-alerts-").matches("[A-Za-z0-9 ._-]{1,64}");
        assertThat(MeasureAlertService.boardKey("file:board:" + "x".repeat(100))).hasSizeLessThanOrEqualTo(64);
        assertThat(MeasureAlertService.boardKey("a:b")).isNotEqualTo(MeasureAlertService.boardKey("a_b"));
    }

    @Test
    void aBridgePostIsOneForTheTileNotOnePerReader() {
        boardSpecs.add(tile("1h", true));
        boardAudience.addAll(List.of(alice, carol));
        readings.put("alice", ok(2));
        readings.put("carol", ok(2));
        service.onLoad("TRD", LocalDate.of(2026, 11, 25));
        clock.advance(Duration.ofSeconds(6));
        service.tick();
        assertThat(inbox).hasSize(2);
        assertThat(bridge).hasSize(1);
        assertThat(bridge.get(0)).isEqualTo("board:file:board:wall/p1/t1");
    }

    @Test
    void aBoardTooBigToNotifyTellsNobodyAndSaysSoOnce() {
        boardSpecs.add(tile("0", false));
        boardAudience.addAll(List.of(alice, bob, carol));                            // the limit in these tests is 2
        readings.put("alice", ok(2));
        service.onLoad("TRD", LocalDate.of(2026, 11, 25));
        clock.advance(Duration.ofSeconds(6));
        service.tick();
        service.onLoad("TRD", LocalDate.of(2026, 11, 25));
        clock.advance(Duration.ofSeconds(6));
        service.tick();
        assertThat(inbox).isEmpty();
        JsonNode h = service.boardAlerts().get(0).path("history");
        assertThat(h).hasSize(1);
        assertThat(h.get(0).path("message").asText()).contains("more than 2").contains("max-audience");
    }

    @Test
    void theBridgeFactsNameTheAlertAndHoldNoNumber() {
        readings.put("alice", ok(150));
        service.save("alice", "Facts", body(alert(ABOVE_100, HOURLY, "\"delivery\":{\"bridge\":true}")));
        assertThat(bridge).containsExactly("alice/Facts");
        long id = history("alice", "Facts").get(0).path("id").asLong();
        Map<String, String> facts = service.bridgeFacts(MeasureAlertService.bridgeRef("alice/Facts", id)).orElseThrow();
        assertThat(facts).containsEntry("condition", "above 100").containsEntry("link", "/bi/i/trading");
        assertThat(facts.values().toString()).doesNotContain("150");
        assertThat(service.bridgeFacts("alice/Gone|1")).isEmpty();
    }

    @Test
    void everythingSurvivesARestart(@TempDir Path dir) {
        readings.put("alice", ok(150));
        service.save("alice", "Kept", body(alert(ABOVE_100, HOURLY, "")));
        assertThat(inbox).hasSize(1);
        MeasureAlertService again = build(props());                                  // same store
        assertThat(again.of("alice")).hasSize(1);
        again.check("alice", "Kept");
        assertThat(inbox).hasSize(1);                                                // it remembers it is active: no second alert for the same breach
        assertThat(again.all()).hasSize(1);
    }

    @Test
    void boardTilesAreMadeFromTheBoardNotStored() {
        Board.Alert a = new Board.Alert("exposure", new Board.Rule("lt", 0), new Board.Rule("lt", -5), new Board.Notify("crit", List.of("inbox", "email", "chat"), "1h", "live", null));
        assertThat(a.tell().channels()).contains("chat");
        assertThat(Optional.of(service.all())).isPresent();
    }

    // ---- retries, webhooks and texts (wave D) --------------------------------------------------------------------------------------

    private String awaitEmail(String owner, String name, java.util.function.Predicate<String> done) throws InterruptedException {
        String last = "";
        for (int i = 0; i < 100; i++) {
            last = history(owner, name).get(0).path("delivered").path("email").asText();
            if (done.test(last)) {
                return last;
            }
            Thread.sleep(50);
        }
        return last;
    }

    @Test
    void aFailedEmailIsTriedAgainByTheSharedPolicyAndTheHistoryShowsTheAttempts() throws Exception {
        service.retryWith(new com.ash.drishti.common.RetryPolicy(true, Duration.ofMillis(150), 1.0, Duration.ofMillis(150), 0, 3));
        emailRule = n -> n < 3 ? "failed: connection refused" : "sent";
        readings.put("alice", ok(120));
        service.save("alice", "Retry", body(alert(ABOVE_100, HOURLY, "\"delivery\":{\"inbox\":true,\"email\":true}")));
        assertThat(history("alice", "Retry").get(0).path("delivered").path("email").asText()).startsWith("retrying: attempt 1 of 4 failed (connection refused)");
        String end = awaitEmail("alice", "Retry", t -> t.startsWith("sent"));
        assertThat(end).isEqualTo("sent (attempt 3 of 4)");
        assertThat(history("alice", "Retry").get(0).path("delivered").path("emailAttempts").asInt()).isEqualTo(3);
        assertThat(emailTries.get()).isEqualTo(3);
    }

    @Test
    void aRefusedAddressIsNotRetriedAndAnEmailThatNeverWorksStopsAtMaxAttempts() throws Exception {
        service.retryWith(new com.ash.drishti.common.RetryPolicy(true, Duration.ofMillis(10), 1.0, Duration.ofMillis(20), 0, 3));
        emailRule = n -> "failed (permanent): 550 no such user";
        readings.put("alice", ok(120));
        service.save("alice", "Perm", body(alert(ABOVE_100, HOURLY, "\"delivery\":{\"email\":true}")));
        assertThat(history("alice", "Perm").get(0).path("delivered").path("email").asText()).isEqualTo("failed (permanent): 550 no such user (not retried)");
        Thread.sleep(150);
        assertThat(emailTries.get()).isEqualTo(1);
        emailTries.set(0);
        emailRule = n -> "failed: timeout";
        service.save("alice", "Never", body(alert(ABOVE_100, HOURLY, "\"delivery\":{\"email\":true}")));
        assertThat(awaitEmail("alice", "Never", t -> t.startsWith("failed after"))).isEqualTo("failed after 4 attempts: timeout");
        assertThat(emailTries.get()).isEqualTo(4);
    }

    @Test
    void webhooksAndTextsAreDeliveredAndOnlyDefinedOnesAreAccepted() {
        readings.put("alice", ok(120));
        service.save("alice", "Out", body(alert(ABOVE_100, HOURLY, "\"severity\":\"critical\",\"delivery\":{\"inbox\":true,\"webhooks\":[\"ops\"],\"sms\":true}")));
        assertThat(hooks).containsExactly("ops: Out critical");
        assertThat(texts).containsExactly("alice: Out");
        JsonNode d = history("alice", "Out").get(0).path("delivered");
        assertThat(d.path("webhook:ops").asText()).isEqualTo("sent");
        assertThat(d.path("sms").asText()).isEqualTo("sent");
        assertThatThrownBy(() -> service.save("alice", "Bad", body(alert(ABOVE_100, HOURLY, "\"delivery\":{\"webhooks\":[\"nope\"]}")))).hasMessageContaining("no webhook named 'nope'");
        assertThatThrownBy(() -> service.save("bob", "NoPhone", body(alert(ABOVE_100, HOURLY, "\"delivery\":{\"sms\":true}")))).hasMessageContaining("mobile number");
        MeasureAlertService off = build(new MeasureAlertProperties(true, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 4, false, false));
        assertThatThrownBy(() -> off.save("alice", "x", body(alert(ABOVE_100, HOURLY, "\"delivery\":{\"webhooks\":[\"ops\"]}")))).hasMessageContaining("webhook delivery is turned off");
        assertThatThrownBy(() -> off.save("alice", "y", body(alert(ABOVE_100, HOURLY, "\"delivery\":{\"sms\":true}")))).hasMessageContaining("text messages are not set up");
    }
}
