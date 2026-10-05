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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ash.drishti.identity.AccessLog;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.OutboxItem;
import com.ash.drishti.server.collab.RateLimits;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** A bridge post writes an access-log row (who, what, where) beside its audit row; with access logging off, only the audit row. */
class BridgeAccessLogTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-05T09:00:00Z"), ZoneOffset.UTC);
    private final OutboxItem item = OutboxItem.pending("bridge", "ops-chat", "share", "sh_42", clock.instant()).withSeq(7);

    private BridgeSender sender(AuditLog audit, AccessLog access) {
        CollabProperties props = new CollabProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
        return new BridgeSender(mock(BridgeRegistry.class), mock(BridgeItemRenderer.class), mock(BridgeClient.class), new RateLimits(), props, audit,
                access, clock, "Drishti");
    }

    @Test
    void aPostIsInTheAccessLogAndTheAuditLog() {
        AuditLog audit = mock(AuditLog.class);
        AccessLog access = mock(AccessLog.class);
        sender(audit, access).delivered(item);
        ArgumentCaptor<AccessLog.Event> e = ArgumentCaptor.forClass(AccessLog.Event.class);
        verify(access).record(e.capture());
        assertThat(e.getValue().action()).isEqualTo("bridge-post");
        assertThat(e.getValue().kind()).isEqualTo("share");
        assertThat(e.getValue().entityId()).isEqualTo("sh_42");
        assertThat(e.getValue().detail()).contains("ops-chat").contains("delivery 7");
        assertThat(e.getValue().at()).isEqualTo(clock.instant());
        verify(audit).record(any(), any(), any(), any());
    }

    @Test
    void withAccessLoggingOffOnlyTheAuditRowIsWritten() {
        AuditLog audit = mock(AuditLog.class);
        sender(audit, null).delivered(item);
        verify(audit).record(any(), any(), any(), any());
    }
}
