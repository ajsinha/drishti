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
        SimpleModule m = new SimpleModule("drishti") {
            private static final long serialVersionUID = 1L;

            @Override
            public void setupModule(SetupContext context) {
                super.setupModule(context);
                context.insertAnnotationIntrospector(new RachanaIncludes());   // Rachana's ViewModel records: @Include as @JsonInclude
            }
        };
        m.setSerializerModifier(new RachanaCodes());              // RCH- codes of Rachana's records answered as DRS- (one mapping)
        m.addSerializer(DataNode.class, new JsonSerializer<>() {
            @Override
            public void serialize(DataNode value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                codec.write(value, gen);
            }
        });
        // a node Rachana built itself (a pivot or surface mark) is written as the same tree
        m.addSerializer(com.ash.rachana.api.Node.class, new JsonSerializer<>() {
            @Override
            public void serialize(com.ash.rachana.api.Node value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                codec.write(DataNode.of(value), gen);
            }
        });
        // Imperfect source data yields NaN or infinities; JSON has no such numbers, so they are written as null
        // (a gap in a chart, a blank cell) instead of breaking the whole response.
        JsonSerializer<Double> finite = new JsonSerializer<>() {
            @Override
            public void serialize(Double value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                if (value == null || value.isNaN() || value.isInfinite()) {
                    gen.writeNull();
                } else {
                    gen.writeNumber(value);
                }
            }
        };
        m.addSerializer(Double.class, finite);
        m.addSerializer(double.class, finite);
        return m;
    }

    /**
     * Rachana's ViewModel records say which members a writer leaves out with Rachana's own {@code @Include} (Rachana has no Jackson);
     * this reads it as Jackson's {@code @JsonInclude} with the same rule, so the API writes the same bytes as before the move
     * (SPLIT_PLAN 8.3e).
     */
    public static final class RachanaIncludes extends com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector {
        private static final long serialVersionUID = 1L;

        @Override
        public com.fasterxml.jackson.annotation.JsonInclude.Value findPropertyInclusion(com.fasterxml.jackson.databind.introspect.Annotated a) {
            com.ash.rachana.view.Include inc = a.getAnnotation(com.ash.rachana.view.Include.class);
            if (inc == null) {
                return com.fasterxml.jackson.annotation.JsonInclude.Value.empty();
            }
            com.fasterxml.jackson.annotation.JsonInclude.Include rule = com.fasterxml.jackson.annotation.JsonInclude.Include.valueOf(inc.value().name());
            return com.fasterxml.jackson.annotation.JsonInclude.Value.construct(rule, null);
        }
    }

    /**
     * The API edge's one mapping of Rachana's error codes (SPLIT_PLAN 8.3e, A1): the {@code code} member of any Rachana record the API
     * writes (a Sutra problem, a builder operation's problem, a panel's error) is answered as Drishti's {@code DRS-} code of the same
     * number ({@code RCH-5021} as {@code DRS-5021}); every other member and every Drishti record is written as it is.
     */
    static final class RachanaCodes extends com.fasterxml.jackson.databind.ser.BeanSerializerModifier {
        private static final long serialVersionUID = 1L;

        @Override
        public java.util.List<com.fasterxml.jackson.databind.ser.BeanPropertyWriter> changeProperties(
                com.fasterxml.jackson.databind.SerializationConfig config, com.fasterxml.jackson.databind.BeanDescription bean,
                java.util.List<com.fasterxml.jackson.databind.ser.BeanPropertyWriter> props) {
            if (!bean.getBeanClass().getName().startsWith("com.ash.rachana.")) {
                return props;
            }
            java.util.List<com.fasterxml.jackson.databind.ser.BeanPropertyWriter> out = new java.util.ArrayList<>(props.size());
            for (com.fasterxml.jackson.databind.ser.BeanPropertyWriter w : props) {
                out.add("code".equals(w.getName()) && w.getType().getRawClass() == String.class ? new DrsCode(w) : w);
            }
            return out;
        }
    }

    /** A {@code code} member written with its {@code RCH-} prefix as {@code DRS-}. */
    static final class DrsCode extends com.fasterxml.jackson.databind.ser.BeanPropertyWriter {
        private static final long serialVersionUID = 1L;

        DrsCode(com.fasterxml.jackson.databind.ser.BeanPropertyWriter w) {
            super(w);
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            Object v = get(bean);
            if (!(v instanceof String code)) {
                super.serializeAsField(bean, gen, prov);
                return;
            }
            gen.writeStringField(getName(), com.ash.drishti.rachana.DrishtiRachana.drs(code));
        }
    }

    @Bean(destroyMethod = "close")
    public com.ash.drishti.server.api.CommandMemory commandMemory(com.ash.drishti.identity.PreferenceStore store) {
        return new com.ash.drishti.server.api.CommandMemory(store);
    }
}
