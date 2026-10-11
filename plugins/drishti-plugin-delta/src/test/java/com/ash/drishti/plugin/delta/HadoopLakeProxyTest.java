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
package com.ash.drishti.plugin.delta;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.proxy.SiteProxy;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The Hadoop engine's S3A settings carry the outbound proxy of the connector (or the site default), honouring no-proxy and {@code proxy: none}. */
class HadoopLakeProxyTest {

    @AfterEach
    void reset() {
        SiteProxy.reset();
    }

    private static Configuration conf(Map<String, String> settings) {
        Configuration conf = new Configuration(false);
        HadoopLake.s3(conf, settings);
        return conf;
    }

    private static Map<String, String> store(String... more) {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("s3.endpoint", "https://minio.example.com:9000");
        for (int i = 0; i < more.length; i += 2) {
            s.put(more[i], more[i + 1]);
        }
        return s;
    }

    @Test
    void theConnectorsProxyBecomesTheS3aProxy() {
        Configuration c = conf(store("proxy.url", "http://proxy.corp:3128", "proxy.username", "svc", "proxy.password", "pw"));
        assertThat(c.get("fs.s3a.proxy.host")).isEqualTo("proxy.corp");
        assertThat(c.get("fs.s3a.proxy.port")).isEqualTo("3128");
        assertThat(c.get("fs.s3a.proxy.ssl.enabled")).isEqualTo("false");
        assertThat(c.get("fs.s3a.proxy.username")).isEqualTo("svc");
        assertThat(c.get("fs.s3a.proxy.password")).isEqualTo("pw");
    }

    @Test
    void noProxyAndProxyNoneLeaveItOut() {
        assertThat(conf(store("proxy.url", "http://proxy.corp:3128", "proxy.no-proxy", ".example.com")).get("fs.s3a.proxy.host")).isNull();
        SiteProxy.configure(Map.of("proxy.url", "http://site-proxy:8080"), false);
        assertThat(conf(store()).get("fs.s3a.proxy.host")).isEqualTo("site-proxy");
        assertThat(conf(store("proxy", "none")).get("fs.s3a.proxy.host")).isNull();
    }

    @Test
    void withoutAnyProxyNothingIsSet() {
        assertThat(conf(store()).get("fs.s3a.proxy.host")).isNull();
    }
}
