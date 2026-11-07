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
package com.ash.drishti.server.approvals;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.identity.collab.Notice;
import com.ash.drishti.server.collab.InboxHub;
import com.ash.drishti.server.collab.InboxService;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one Approvals queue: every {@link ApprovalSource} bean of the server, the question threads and the notices. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApprovalProperties.class)
public class ApprovalConfiguration {

    @Bean
    public ApprovalQueue approvalQueue(List<ApprovalSource> sources, PreferenceStore store, AuditLog audit, ApprovalProperties props, InboxService inbox,
            InboxHub hub) {
        return new ApprovalQueue(sources, store, audit, props, Clock.systemUTC(), (Notice n) -> hub.publish(inbox.add(n)));
    }
}
