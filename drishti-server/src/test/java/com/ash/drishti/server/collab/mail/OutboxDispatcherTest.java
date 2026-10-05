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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ash.drishti.identity.User;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.FileOutboxStore;
import com.ash.drishti.identity.collab.OutboxItem;
import com.ash.drishti.identity.collab.OutboxStore;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.security.Principal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

/** The worker: sent, retried with doubling backoff, dead after the last attempt or at once on a 5xx, cancelled when it should not go. */
class OutboxDispatcherTest {

    /** A clock the test moves. */
    static final class Tick extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-01T09:00:00Z"));

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }
    }

    private final Tick clock = new Tick();
    private final AtomicInteger sends = new AtomicInteger();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final AtomicReference<RenderedMail> last = new AtomicReference<>();
    private OutboxStore store;
    private OutboxDispatcher dispatcher;
    private Principals principals;
    private User ravi;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() throws Exception {
        store = new FileOutboxStore(Files.createTempDirectory("dispatch"));
        principals = mock(Principals.class);
        ravi = mock(User.class);
        when(ravi.enabled()).thenReturn(true);
        when(ravi.email()).thenReturn("ravi@desk.test");
        when(principals.user("ravi")).thenReturn(Optional.of(ravi));
        when(principals.of("ravi")).thenReturn(new Principal("ravi", List.of("trader")));
        ItemRenderer renderer = new ItemRenderer() {
            @Override
            public String template() {
                return "share";
            }

            @Override
            public MailRenderer.Content content(OutboxItem item, Principal who) {
                if (item.refId().equals("sh_gone")) {
                    throw new Skip("the share no longer exists");
                }
                return new MailRenderer.Content("share", "Ann shared a view with you", null, null, null, null, null, "https://x/share/" + item.refId());
            }
        };
        MailTransport transport = (m, from) -> {
            sends.incrementAndGet();
            last.set(m);
            if (failure.get() != null) {
                throw failure.get();
            }
        };
        meters = new SimpleMeterRegistry();
        CollabProperties props = new CollabProperties(null, null, null, "https://x", null, null, null, null, null, null, null, null, null,
                new CollabProperties.Outbox(true, Duration.ofSeconds(1), 10, 3, Duration.ofSeconds(30), Duration.ofSeconds(100), Duration.ofSeconds(60), 30),
                null, null, null, null, null);
        dispatcher = new OutboxDispatcher(store, props, principals, List.of(renderer),
                new MailRenderer(new MailTemplates(""), "Drishti", "x"), transport, meters, clock);
    }

    private OutboxItem enqueue(String ref) {
        return store.add(OutboxItem.pending("email", "ravi", "share", ref, clock.instant()));
    }

    @Test
    void aDueRowIsSentOnceAndRecorded() {
        OutboxItem i = enqueue("sh_1");
        assertThat(dispatcher.tick()).isEqualTo(1);
        assertThat(store.find(i.seq()).orElseThrow().state()).isEqualTo(OutboxItem.SENT);
        assertThat(last.get().to()).isEqualTo("ravi@desk.test");
        assertThat(dispatcher.tick()).isZero();
        assertThat(sends).hasValue(1);
        assertThat(meters.counter("drishti.collab.mail", "outcome", "sent").count()).isEqualTo(1);
    }

    @Test
    void aTransientFailureRetriesWithDoublingBackoffThenIsADeadLetter() {
        failure.set(new MailSendException("Could not connect to SMTP host: mail.invalid, port: 25"));
        OutboxItem i = enqueue("sh_1");
        dispatcher.tick();
        OutboxItem a = store.find(i.seq()).orElseThrow();
        assertThat(a.state()).isEqualTo(OutboxItem.PENDING);
        assertThat(a.attempts()).isEqualTo(1);
        assertThat(a.nextAt()).isEqualTo(clock.instant().plusSeconds(30));
        assertThat(a.lastError()).contains("Could not connect");
        assertThat(dispatcher.tick()).as("not due yet").isZero();

        clock.advance(Duration.ofSeconds(30));
        dispatcher.tick();
        assertThat(store.find(i.seq()).orElseThrow().nextAt()).isEqualTo(clock.instant().plusSeconds(60));

        clock.advance(Duration.ofSeconds(60));
        dispatcher.tick();
        OutboxItem dead = store.find(i.seq()).orElseThrow();
        assertThat(dead.state()).as("max-attempts is 3").isEqualTo(OutboxItem.DEAD);
        assertThat(dead.attempts()).isEqualTo(3);
        assertThat(sends).hasValue(3);
        assertThat(meters.counter("drishti.collab.mail", "outcome", "dead").count()).isEqualTo(1);
        assertThat(meters.get("drishti.collab.outbox").tag("state", "dead").gauge().value()).isEqualTo(1.0);

        // an administrator sends it again once the server is back
        failure.set(null);
        assertThat(store.requeue(i.seq(), clock.instant())).isTrue();
        dispatcher.tick();
        assertThat(store.find(i.seq()).orElseThrow().state()).isEqualTo(OutboxItem.SENT);
    }

    @Test
    void backoffIsCappedAtMaxBackoff() {
        assertThat(dispatcher.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(dispatcher.backoff(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(dispatcher.backoff(3)).isEqualTo(Duration.ofSeconds(100));
        assertThat(dispatcher.backoff(40)).isEqualTo(Duration.ofSeconds(100));
    }

    @Test
    void anSmtp5xxIsADeadLetterAtOnce() {
        failure.set(new MailSendException("failed", new SMTPSendFailedException("RCPT TO", 550, "550 5.1.1 no such user", null, null, null, null)));
        OutboxItem i = enqueue("sh_1");
        dispatcher.tick();
        OutboxItem d = store.find(i.seq()).orElseThrow();
        assertThat(d.state()).isEqualTo(OutboxItem.DEAD);
        assertThat(d.attempts()).isEqualTo(1);
        assertThat(d.lastError()).contains("550");
    }

    @Test
    void anSmtp4xxIsRetried() {
        failure.set(new MailSendException("failed", new SMTPSendFailedException("RCPT TO", 451, "451 try later", null, null, null, null)));
        OutboxItem i = enqueue("sh_1");
        dispatcher.tick();
        assertThat(store.find(i.seq()).orElseThrow().state()).isEqualTo(OutboxItem.PENDING);
    }

    @Test
    void aRowThatShouldNotGoIsCancelledNotSent() {
        OutboxItem gone = enqueue("sh_gone");
        dispatcher.tick();
        when(ravi.email()).thenReturn("not an address");
        OutboxItem bad = enqueue("sh_2");
        dispatcher.tick();
        assertThat(store.find(gone.seq()).orElseThrow().state()).isEqualTo(OutboxItem.CANCELLED);
        assertThat(store.find(gone.seq()).orElseThrow().lastError()).contains("no longer exists");
        assertThat(store.find(bad.seq()).orElseThrow().state()).isEqualTo(OutboxItem.CANCELLED);
        assertThat(store.find(bad.seq()).orElseThrow().lastError()).contains("no valid address");

        when(ravi.email()).thenReturn("ravi@desk.test");
        when(ravi.enabled()).thenReturn(false);
        OutboxItem off = enqueue("sh_3");
        dispatcher.tick();
        assertThat(store.find(off.seq()).orElseThrow().state()).isEqualTo(OutboxItem.CANCELLED);
        assertThat(sends).hasValue(0);
    }

    @Test
    void aRowOfAnUnknownTemplateIsADeadLetter() {
        OutboxItem i = store.add(OutboxItem.pending("email", "ravi", "mention", "cm_1", clock.instant()));
        dispatcher.tick();
        assertThat(store.find(i.seq()).orElseThrow().state()).isEqualTo(OutboxItem.DEAD);
    }

    @Test
    void anExpiredLeaseIsTakenOverAfterACrash() {
        OutboxItem i = enqueue("sh_1");
        store.claim(clock.instant(), 10, clock.instant().plusSeconds(60), "srv-dead");
        assertThat(dispatcher.tick()).as("leased by another server").isZero();
        clock.advance(Duration.ofSeconds(61));
        assertThat(dispatcher.tick()).isEqualTo(1);
        assertThat(store.find(i.seq()).orElseThrow().state()).isEqualTo(OutboxItem.SENT);
    }

    private OutboxDispatcher withWindow(Duration window) {
        ItemRenderer renderer = new ItemRenderer() {
            @Override
            public String template() {
                return "share";
            }

            @Override
            public MailRenderer.Content content(OutboxItem item, Principal who) {
                if (item.refId().equals("sh_gone")) {
                    throw new Skip("the share no longer exists");
                }
                return new MailRenderer.Content("share", "Ann shared " + item.refId(), "trade", item.refId(), null, null, null, "https://x/share/" + item.refId());
            }
        };
        CollabProperties props = new CollabProperties(null, null, null, "https://x", null, null, null, null, null, null, null, null,
                new CollabProperties.Email(true, null, null, null, window),
                new CollabProperties.Outbox(true, Duration.ofSeconds(1), 10, 3, Duration.ofSeconds(30), Duration.ofSeconds(100), Duration.ofSeconds(60), 30),
                null, null, null, null, null);
        return new OutboxDispatcher(store, props, principals, List.of(renderer), new MailRenderer(new MailTemplates(""), "Drishti", "x"),
                (m, from) -> {
                    sends.incrementAndGet();
                    last.set(m);
                }, meters, clock);
    }

    @Test
    void severalNoticesDueTogetherForOnePersonAreOneDigestThatSkipsWhatShouldNotGo() {
        OutboxItem a = enqueue("sh_1");
        OutboxItem gone = enqueue("sh_gone");
        OutboxItem b = enqueue("sh_2");
        withWindow(Duration.ofMinutes(2)).tick();
        assertThat(sends).hasValue(1);
        assertThat(last.get().text()).contains("sh_1").contains("sh_2").doesNotContain("sh_gone");
        assertThat(last.get().subject()).contains("2 new notifications");
        assertThat(store.find(a.seq()).orElseThrow().state()).isEqualTo(OutboxItem.SENT);
        assertThat(store.find(b.seq()).orElseThrow().state()).isEqualTo(OutboxItem.SENT);
        assertThat(store.find(gone.seq()).orElseThrow().state()).isEqualTo(OutboxItem.CANCELLED);
    }

    @Test
    void aWindowOfZeroSendsEachNoticeOnItsOwn() {
        enqueue("sh_1");
        enqueue("sh_2");
        withWindow(Duration.ZERO).tick();
        assertThat(sends).hasValue(2);
    }

    @Test
    void theNotifierGivesNoticesInsideTheWindowOneSendTime() {
        User ann = mock(User.class);
        when(ann.email()).thenReturn("ann@desk.test");
        Principals ps = mock(Principals.class);
        when(ps.user("ann")).thenReturn(Optional.of(ann));
        NotifyPrefs prefs = mock(NotifyPrefs.class);
        when(prefs.emailOn("ann", "share")).thenReturn(true);
        CollabProperties props = new CollabProperties(null, null, null, "https://x", null, null, null, null, null, null, null, null,
                new CollabProperties.Email(true, null, null, null, Duration.ofMinutes(2)), null, null, null, null, null, null);
        EmailNotifier n = new EmailNotifier(props, store, ps, prefs, true);
        java.lang.reflect.Method m;
        try {
            m = EmailNotifier.class.getDeclaredMethod("queue", String.class, String.class, String.class);
            m.setAccessible(true);
            m.invoke(n, "ann", "share", "sh_1");
            m.invoke(n, "ann", "share", "sh_2");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        List<OutboxItem> rows = store.list("pending", 10);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).nextAt()).isEqualTo(rows.get(1).nextAt()).isAfter(Instant.now().plusSeconds(60));
    }
}
