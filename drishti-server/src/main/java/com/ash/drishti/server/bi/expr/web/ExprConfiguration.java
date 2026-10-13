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
package com.ash.drishti.server.bi.expr.web;

import com.ash.drishti.server.bi.expr.Expressions;
import com.ash.drishti.server.bi.expr.FunctionCatalog;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Applies {@code drishti.bi.expr.*} to the expression reader at start-up and loads the function catalogue once (a broken catalogue stops the server). */
@Configuration
@EnableConfigurationProperties(ExprProperties.class)
public class ExprConfiguration {

    @Bean
    public InitializingBean expressionSettings(ExprProperties props) {
        return () -> {
            Expressions.configure(props.mode(), props.parseCache());
            FunctionCatalog.get();
        };
    }
}
