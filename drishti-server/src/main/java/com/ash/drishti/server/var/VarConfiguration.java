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
package com.ash.drishti.server.var;

import com.ash.drishti.compute.vector.ParallelSums;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.server.security.Entitlements;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The VaR engine's beans: its settings, the bounded pool its sums run on, and the service the API answers from. */
@Configuration
@EnableConfigurationProperties(VarProperties.class)
public class VarConfiguration {

    @Bean(destroyMethod = "close")
    public VarService varService(VarProperties props, SourceRouter router, Entitlements entitlements, ContentStore store,
            ObjectProvider<AuditLog> audit) {
        ParallelSums sums = new ParallelSums(props.threads(), props.parallelRows());
        return new VarService(props, new VarLoader(router, props, sums), new VarAccess(props, entitlements), new VarSaved(store),
                audit.getIfAvailable(), sums);
    }
}
