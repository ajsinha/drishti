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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.net.config.NetConfig;
import com.ash.drishti.net.config.NetFolder;
import com.ash.drishti.net.config.NetSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The config/net/ folder: defaults without it, schema checks, refused files keeping the previous settings, history (section 14). */
class NetFolderTest {

    private static final String KEY = "a-cluster-key-of-at-least-thirty-two-bytes";

    @TempDir
    Path tmp;

    private NetFolder folder(Map<String, String> env) {
        return new NetFolder(tmp.resolve("config/net"), tmp.resolve("data/net"), new NetSchema(env::get));
    }

    @Test
    void noFolderIsASingleServerAsToday() {
        NetFolder.Loaded l = folder(Map.of()).load(null);
        assertThat(l.config()).isEqualTo(NetConfig.OFF);
        assertThat(l.config().enabled()).isFalse();
        assertThat(l.all()).isEmpty();
        assertThat(Files.exists(tmp.resolve("config/net"))).isFalse();     // reading never creates the folder
    }

    @Test
    void anEnabledNodeNeedsTheKeyFromTheEnvironmentAndAnAdvertisedUrl() {
        NetFolder f = folder(Map.of("DRISHTI_NET_SECRET", KEY));
        f.write("node.yaml", "enabled: true\nadvertise-url: http://127.0.0.1:18783\ntags: [region:emea]\nsecret: ${DRISHTI_NET_SECRET}\n", null);
        NetFolder.Loaded l = f.load(null);
        assertThat(l.all()).isEmpty();
        assertThat(l.config().enabled()).isTrue();
        assertThat(l.config().node().secret()).isEqualTo(KEY);
        assertThat(l.config().node().toString()).doesNotContain(KEY);
        assertThat(l.config().node().tags()).containsExactly("region:emea");

        // without the variable, or without the url: refused, the previous settings stay
        NetFolder unset = folder(Map.of());
        NetFolder.Loaded refused = unset.load(l.config());
        assertThat(refused.refused("node.yaml")).isTrue();
        assertThat(refused.problems().get("node.yaml")).anySatisfy(p -> assertThat(p.field()).isEqualTo("secret"));
        assertThat(refused.config().node()).isEqualTo(l.config().node());
        assertThat(f.check("node.yaml", "enabled: true\nsecret: ${DRISHTI_NET_SECRET}\n")).anySatisfy(p -> assertThat(p.field()).isEqualTo("advertise-url"));
    }

    @Test
    void theKeyIsNeverWrittenInClear() {
        List<NetSchema.Problem> ps = folder(Map.of()).check("node.yaml", "secret: " + KEY + "\n");
        assertThat(ps).anySatisfy(p -> {
            assertThat(p.error()).isTrue();
            assertThat(p.message()).contains("environment reference");
            assertThat(p.line()).isEqualTo(1);
        });
        assertThat(folder(Map.of()).check("node.yaml", "secret: ${X:" + KEY + "}\n")).anySatisfy(p -> assertThat(p.error()).isTrue());
    }

    @Test
    void everyFileIsCheckedWithItsLineAndABadFileKeepsThePreviousSettings() {
        NetFolder f = folder(Map.of());
        f.write("gossip.yaml", "interval: 2s\nfanout: 4\n", null);
        NetConfig first = f.load(null).config();
        assertThat(first.gossip().interval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(first.gossip().fanout()).isEqualTo(4);
        f.write("gossip.yaml", "interval: 2s\nfanout: lots\nsurprise: 1\n", null);
        NetFolder.Loaded l = f.load(first);
        assertThat(l.refused("gossip.yaml")).isTrue();
        assertThat(l.problems().get("gossip.yaml")).anySatisfy(p -> {
            assertThat(p.field()).isEqualTo("fanout");
            assertThat(p.line()).isEqualTo(2);
        }).anySatisfy(p -> assertThat(p.level()).isEqualTo("warning"));
        assertThat(l.config().gossip()).isEqualTo(first.gossip());
        f.write("routing.yaml", "max-hops: 2\n", null);
        assertThat(f.load(first).refused("routing.yaml")).isTrue();
        f.write("access.yaml", "roles:\n  risk:\n    nodes: [n-a, region:emea]\n  trader: {}\n", null);
        assertThat(f.load(first).config().access().roles()).containsEntry("risk", List.of("n-a", "region:emea")).containsEntry("trader", List.of());
        f.write("routing.yaml", "this: [is not\n", null);
        assertThat(f.load(first).problems().get("routing.yaml")).first().satisfies(p -> assertThat(p.message()).startsWith("is not YAML"));
    }

    @Test
    void peerFilesAreSeedsAndHistoryKeepsEveryEarlierText() throws Exception {
        NetFolder f = folder(Map.of());
        f.write("peers/drishti-a.yaml", "url: http://127.0.0.1:18783/\ntags: [region:emea]\n", null);
        f.write("peers/drishti-b.yaml", "url: ftp://nowhere\n", null);
        NetFolder.Loaded l = f.load(null);
        assertThat(l.config().seedUrls()).containsExactly("http://127.0.0.1:18783");
        assertThat(l.refused("peers/drishti-b.yaml")).isTrue();
        assertThat(f.files()).extracting(NetFolder.FileInfo::id).containsExactly("node.yaml", "gossip.yaml", "routing.yaml", "access.yaml",
                "peers/drishti-a.yaml", "peers/drishti-b.yaml");
        String etag = NetFolder.etag(f.text("peers/drishti-a.yaml").orElseThrow());
        f.write("peers/drishti-a.yaml", "url: http://127.0.0.1:18784\n", etag);
        assertThatThrownBy(() -> f.write("peers/drishti-a.yaml", "url: http://x:1\n", etag)).isInstanceOf(IllegalStateException.class);
        assertThat(f.history("peers/drishti-a.yaml")).hasSize(1);
        String rev = f.history("peers/drishti-a.yaml").get(0).id();
        assertThat(f.revisionText("peers/drishti-a.yaml", rev).orElseThrow()).contains("18783");
        assertThat(f.delete("peers/drishti-b.yaml", null)).isTrue();
        assertThat(f.load(null).config().peers()).hasSize(1);
        assertThatThrownBy(() -> f.text("../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        try (var s = Files.list(tmp.resolve("data/net/history"))) {
            assertThat(s.map(p -> p.getFileName().toString()).allMatch(n -> !n.startsWith("."))).isTrue();
        }
    }

    @Test
    void theReadyMadeExamplesAreTakenAsTheyAre() throws Exception {
        Path examples = Path.of("..", "config", "net.examples").toAbsolutePath().normalize();
        NetFolder f = new NetFolder(examples, tmp.resolve("data/net"), new NetSchema(Map.of("DRISHTI_NET_SECRET", KEY)::get));
        NetFolder.Loaded l = f.load(null);
        assertThat(l.all()).isEmpty();
        assertThat(l.config().enabled()).isTrue();
        assertThat(l.config().seedUrls()).containsExactly("https://drishti-a.internal:8480");
        assertThat(l.config().gossip()).isEqualTo(com.ash.drishti.net.config.GossipSettings.DEFAULTS);
        assertThat(l.config().routing()).isEqualTo(com.ash.drishti.net.config.RoutingSettings.DEFAULTS);
        assertThat(l.config().access().roles()).containsKeys("trader", "risk");
    }

    @Test
    void oneSettingIsChangedAsTheCommandLineAndAdminDo() {
        NetFolder f = folder(Map.of("DRISHTI_NET_SECRET", KEY));
        String text = f.withSetting("node.yaml", "tags", "region:emea, tier:risk");
        f.write("node.yaml", text, null);
        f.write("node.yaml", f.withSetting("node.yaml", "secret", "${DRISHTI_NET_SECRET}"), null);
        f.write("node.yaml", f.withSetting("node.yaml", "advertise-url", "http://127.0.0.1:18783"), null);
        f.write("node.yaml", f.withSetting("node.yaml", "enabled", "true"), null);
        NetFolder.Loaded l = f.load(null);
        assertThat(l.all()).isEmpty();
        assertThat(l.config().node().tags()).containsExactly("region:emea", "tier:risk");
        assertThat(f.text("node.yaml").orElseThrow()).contains("${DRISHTI_NET_SECRET}").doesNotContain(KEY);
        f.write("access.yaml", f.withSetting("access.yaml", "roles.risk.nodes", "n-a,n-b"), null);
        assertThat(f.load(null).config().access().roles().get("risk")).containsExactly("n-a", "n-b");
    }
}
