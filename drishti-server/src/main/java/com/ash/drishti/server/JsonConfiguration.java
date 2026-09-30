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
package com.ash.drishti.server;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.JsonCodec;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Teaches the HTTP layer's Jackson to stream {@link DataNode} trees as plain JSON. */
@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {

    @Bean
    public com.ash.drishti.packs.PackRegistry packRegistry(org.springframework.core.env.Environment env) {
        return new com.ash.drishti.packs.PackRegistry(env);
    }

    @Bean
    public SimpleModule drishtiJsonModule(JsonCodec codec) {
        SimpleModule m = new SimpleModule("drishti");
        m.addSerializer(DataNode.class, new JsonSerializer<>() {
            @Override
            public void serialize(DataNode value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                codec.write(value, gen);
            }
        });
        return m;
    }
}
