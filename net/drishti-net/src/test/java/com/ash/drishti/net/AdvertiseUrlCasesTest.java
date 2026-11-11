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
package com.ash.drishti.net;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.net.config.HostAddress;
import com.ash.drishti.net.config.NetFolder;
import com.ash.drishti.net.config.NetSchema;
import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.member.MemberState;
import com.ash.drishti.net.member.MemberTable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Advertised and seed addresses (QA1-202, QA1-206): the table {@code net/advertise-urls.tsv}, which {@code tools/test_cli_net.py} reads
 * too, so the server and {@code drishti.py net} take and refuse the same addresses; through the schema check (node.yaml and a peer file,
 * as {@code POST /net/file/check} and {@code PUT} run it) and the gossip intake.
 */
class AdvertiseUrlCasesTest {

    private static final String KEY = "a-cluster-key-of-at-least-thirty-two-bytes";

    @TempDir
    Path tmp;

    record Case(String url, boolean ok, String reason) {}

    static List<Case> cases() throws IOException {
        try (InputStream in = AdvertiseUrlCasesTest.class.getResourceAsStream("/net/advertise-urls.tsv")) {
            assertThat(in).isNotNull();
            List<Case> out = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                out.add(new Case(f[0], "ok".equals(f[1]), f.length > 2 ? f[2] : ""));
            }
            assertThat(out).hasSizeGreaterThan(30);
            return out;
        }
    }

    @Test
    void theServerTakesAndRefusesTheSharedTable() throws IOException {
        NetFolder f = new NetFolder(tmp.resolve("config/net"), tmp.resolve("data/net"), new NetSchema(Map.of("DRISHTI_NET_SECRET", KEY)::get));
        for (Case c : cases()) {
            String node = f.check("node.yaml", "enabled: true\nadvertise-url: \"" + c.url() + "\"\nsecret: ${DRISHTI_NET_SECRET}\n").stream()
                    .filter(p -> p.error()).map(p -> p.message()).collect(Collectors.joining(" | "));
            String peer = f.check("peers/drishti-a.yaml", "url: \"" + c.url() + "\"\n").stream().filter(p -> p.error())
                    .map(p -> p.message()).collect(Collectors.joining(" | "));
            assertThat(HostAddress.callableUrl(c.url())).as(c.url()).isEqualTo(c.ok());
            if (c.ok()) {
                assertThat(node).as(c.url()).isEmpty();
                assertThat(peer).as(c.url()).isEmpty();
            } else {
                assertThat(node).as(c.url()).contains(c.reason());
                assertThat(peer).as(c.url()).contains(c.reason());
            }
        }
    }

    @Test
    void anAdvertisementWithNoPortToCallIsNeverStored() throws IOException {
        // QA1-202: a member advertising port 0 or 99999 was listed alive while every read through it failed
        MemberTable table = new MemberTable(new Member("self", "http://192.0.2.1:18611", 1, 0, MemberState.ALIVE, "", "", Member.PROTOCOL,
                List.of(), 0, false), () -> 1000);
        int i = 0;
        for (Case c : cases()) {
            table.merge(List.of(new Member("m-" + i++, c.url(), 1, 1, MemberState.ALIVE, "", "", Member.PROTOCOL, List.of(), 0, false)));
            assertThat(table.known("m-" + (i - 1))).as(c.url()).matches(o -> o.isPresent() == c.ok());
        }
    }
}
