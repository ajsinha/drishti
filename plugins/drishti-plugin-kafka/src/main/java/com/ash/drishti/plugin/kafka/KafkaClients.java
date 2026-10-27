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
package com.ash.drishti.plugin.kafka;

import java.util.Map;
import java.util.Properties;

/**
 * The Kafka connector's consumer settings for another consumer of the server (the VaR engine's PV topic, VAR_GUIDE.md section 8.4): the
 * same {@code bootstrap-servers}, security settings (TLS, SASL, the Confluent flavour) and {@code client.<property>} overrides as a
 * {@code kafka} connector, so one way of configuring a cluster serves both (KAFKA_CONNECTOR.md section 11). No consumer group, no
 * commits.
 */
public final class KafkaClients {

    private KafkaClients() {}

    /** The consumer properties a {@code kafka} connector named {@code name} with these settings would use. */
    public static Properties consumer(Map<String, String> settings, String name) {
        return KafkaSourcePlugin.consumerProperties(settings, name, KafkaSecurity.translate(settings));
    }
}
