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
package com.ash.drishti.server.caches;

import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.server.disk.DiskBudget;
import com.ash.drishti.server.net.NetService;
import com.ash.drishti.server.net.PeerCalls;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Admin, Caches (2.9.7, O1): the board of every cache, the server's own cache owners, and the calls to the other members (PO-5, PO-6). */
@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(MemoryProperties.class)
public class CachesConfiguration {

    /** The one cache budget (D3): a share of the heap in weighted pools; the connectors' and engines' byte-weighed caches join it. */
    @Bean
    public MemoryBudget memoryBudget(MemoryProperties props) {
        return new MemoryBudget(props.cacheShare(), props.weights(), props.squeezeAt(), Runtime.getRuntime().maxMemory());
    }

    @Bean
    public CacheBoard cacheBoard(SourceRegistry sources, ViewPipeline pipeline, com.ash.drishti.engine.explain.ExplainService explain,
            ObjectProvider<com.ash.drishti.engine.history.HistoryService> history, ObjectProvider<CacheReport.Owner> owners,
            ObjectProvider<DiskBudget> disk, ObjectProvider<MemoryBudget> memory) {
        return new CacheBoard(sources, pipeline, explain, history, owners, disk, () -> {
            MemoryBudget m = memory.getIfAvailable();
            return m != null ? m.describe() : heap();
        });
    }

    /** The heap now, when no memory budget is set up (tests). */
    static Map<String, Object> heap() {
        Runtime r = Runtime.getRuntime();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("heapMaxBytes", r.maxMemory());
        m.put("heapUsedBytes", r.totalMemory() - r.freeMemory());
        return m;
    }

    @Bean
    public ServerCaches serverCaches(ObjectProvider<com.ash.drishti.server.bi.engine.QueryService> bi,
            ObjectProvider<com.ash.drishti.server.security.PackAccess> packs) {
        return new ServerCaches(bi, packs);
    }

    @Bean
    public PeerCalls peerCalls(ObjectProvider<NetService> net) {
        return new PeerCalls(net::getIfAvailable);
    }
}
