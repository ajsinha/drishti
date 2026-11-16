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
package com.ash.drishti.compute;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.compute.sched.FairQueue;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fair scheduling between users and classes, and back-pressure (docs/architecture/DATA_ARCHITECTURE.md › Reactive connectors, section 10.3). */
class FairQueueTest {

    record J(RequestClass cls, String user, int n) implements FairQueue.Item {}

    @Test
    void interactiveWorkGoesBeforeEventsAndBatch() {
        FairQueue<J> q = new FairQueue<>(10, 10, 10, 10, 10);
        q.offer(new J(RequestClass.BATCH, "a", 1));
        q.offer(new J(RequestClass.EVENT, "a", 2));
        q.offer(new J(RequestClass.INTERACTIVE, "a", 3));
        assertThat(List.of(q.next().n(), q.next().n(), q.next().n())).containsExactly(3, 2, 1);
    }

    @Test
    void usersTakeTurnsSoOneUsersFloodWaitsBehindAnothersOneRequest() {
        FairQueue<J> q = new FairQueue<>(100, 10, 10, 100, 10);
        for (int i = 0; i < 10; i++) {
            q.offer(new J(RequestClass.INTERACTIVE, "flood", i));
        }
        q.offer(new J(RequestClass.INTERACTIVE, "ben", 99));
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            order.add(q.next().user());
        }
        assertThat(order.subList(0, 2)).containsExactlyInAnyOrder("flood", "ben");
        assertThat(order.indexOf("ben")).isLessThanOrEqualTo(1);
    }

    @Test
    void noUserRunsMoreThanTheirInflightLimit() {
        FairQueue<J> q = new FairQueue<>(100, 10, 10, 2, 10);
        for (int i = 0; i < 5; i++) {
            q.offer(new J(RequestClass.INTERACTIVE, "a", i));
        }
        J first = q.next();
        q.next();
        assertThat(q.next()).isNull();                                         // a holds two already
        q.finished(first);
        assertThat(q.next()).isNotNull();
    }

    @Test
    void batchHoldsOnlyItsShareOfTheWorkers() {
        FairQueue<J> q = new FairQueue<>(100, 10, 100, 100, 2);
        for (int i = 0; i < 5; i++) {
            q.offer(new J(RequestClass.BATCH, "nightly", i));
        }
        J b1 = q.next();
        q.next();
        assertThat(q.next()).isNull();                                         // the batch share is 2
        q.offer(new J(RequestClass.INTERACTIVE, "asha", 7));
        assertThat(q.next().user()).isEqualTo("asha");                         // people still get a worker
        q.finished(b1);
        assertThat(q.next().cls()).isEqualTo(RequestClass.BATCH);
    }

    @Test
    void aFullQueueRefusesAtOnce() {
        FairQueue<J> q = new FairQueue<>(2, 10, 10, 10, 10);
        assertThat(q.offer(new J(RequestClass.INTERACTIVE, "a", 1))).isTrue();
        assertThat(q.offer(new J(RequestClass.INTERACTIVE, "b", 2))).isTrue();
        assertThat(q.offer(new J(RequestClass.INTERACTIVE, "c", 3))).isFalse();
        assertThat(q.offer(new J(RequestClass.EVENT, "c", 4))).isTrue();      // each class has its own bound
        J w = new J(RequestClass.INTERACTIVE, "z", 5);
        assertThat(q.remove(w)).isFalse();
        assertThat(q.size()).isEqualTo(3);
    }
}
