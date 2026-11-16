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
package com.ash.drishti.server.governance;

import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GovernanceProperties.class)
public class GovernanceConfiguration {

    @Bean
    public ProposalStore proposalStore(GovernanceProperties props, com.ash.drishti.identity.content.ContentStore content,
            org.springframework.core.env.Environment env) {
        boolean files = "file".equalsIgnoreCase(env.getProperty("drishti.governance.store", "content").trim());
        return new ProposalStore(Path.of(props.dir()), files ? null : content);
    }
}
