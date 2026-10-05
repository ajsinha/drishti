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

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.OutboxStore;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.identity.collab.ThreadStore;
import com.ash.drishti.server.collab.LinkBuilder;
import com.ash.drishti.server.collab.PanelTitles;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.collab.RateLimits;
import com.ash.drishti.server.collab.mail.MailContentPolicy;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import java.time.Clock;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The bridges (COLLABORATION.md, build step 9): the registry, the outbox channel and the notifier. */
@Configuration(proxyBeanMethods = false)
public class BridgeConfiguration {

    /** Where bridge URLs and secrets are read from: the process environment, unless a test supplies another source. */
    @FunctionalInterface
    public interface Env extends Function<String, String> {}

    @Bean
    public BridgeRegistry bridgeRegistry(CollabProperties props, ObjectProvider<Env> env) {
        Env e = env.getIfAvailable(() -> System::getenv);
        return new BridgeRegistry(props, e);
    }

    @Bean
    public BridgeNotifier bridgeNotifier(CollabProperties props, BridgeRegistry registry, OutboxStore outbox, PackAccess packs) {
        return new BridgeNotifier(props, registry, outbox, packs, Clock.systemUTC());
    }

    @Bean
    public BridgeItemRenderer bridgeItemRenderer(ShareStore shares, ThreadStore threads, Principals principals, Entitlements entitlements,
            MailContentPolicy policy, PanelTitles titles, LinkBuilder links, CollabProperties props,
            @Value("${drishti.branding.product:Drishti}") String product) {
        return new BridgeItemRenderer(shares, threads, principals, entitlements, policy, titles, links, props, product, Clock.systemUTC());
    }

    @Bean
    public BridgeSender bridgeSender(BridgeRegistry registry, BridgeItemRenderer renderer, RateLimits limits, CollabProperties props, AuditLog audit,
            @Value("${drishti.branding.product:Drishti}") String product) {
        return new BridgeSender(registry, renderer, new BridgeClient(props.bridges().timeout()), limits, props, audit, Clock.systemUTC(), product);
    }
}
