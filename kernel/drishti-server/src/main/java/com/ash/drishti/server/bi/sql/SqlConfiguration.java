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
package com.ash.drishti.server.bi.sql;

import com.ash.drishti.server.bi.mq.MqService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Drishti SQL's beans: the service that parses, binds and answers statements over models. */
@Configuration
@EnableConfigurationProperties(SqlProperties.class)
public class SqlConfiguration {

    @Bean
    public SqlService sqlService(MqService mq, SqlProperties props, com.ash.drishti.server.bi.mq.ModelCatalog catalog, com.ash.drishti.server.bi.ExpectedValueRunner tests) {
        SqlService sql = new SqlService(mq, props);
        if (props.enabled()) {
            catalog.sqlViews(sql::answer);                                       // SQL view datasets: their statement answered as the reader
            tests.sqlTests((as, model, text) -> sql.answer(catalog.bind(as, model), model, text, null));      // a model's tests: [{ sql, expect }]
            tests.datasetTests((as, body, model, text) -> sql.answer(model == null ? catalog.bindText(as, body) : catalog.bind(as, model),   // a dataset's data tests
                    model == null ? "dataset" : model, text, null));
        }
        return sql;
    }
}
