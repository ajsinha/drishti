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
package com.ash.drishti.server.alerts.measure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.board.Board;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What an alert's JSON must say, how it is written back, and how a board tile is turned into a level number. */
class AlertSpecTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final MeasureAlertProperties PROPS = MeasureAlertProperties.defaults();
    static final String SOURCE = "\"source\":{\"type\":\"visual\",\"report\":\"daily-risk\",\"page\":\"p1\",\"visual\":\"kpi\",\"measure\":\"Exposure\",\"reduce\":\"sum\"}";

    static AlertSpec parse(String json) {
        try {
            JsonNode n = JSON.readTree(json);
            return AlertSpec.parse("alice", "Net", n, PROPS);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aVisualThresholdReadsAndWritesBackTheSame() {
        AlertSpec s = parse("{" + SOURCE + ",\"condition\":{\"type\":\"threshold\",\"op\":\"between\",\"value\":10,\"high\":20},\"trigger\":{\"mode\":\"live\",\"debounce\":\"5s\"},"
                + "\"hysteresis\":2,\"cooldown\":\"1h\",\"severity\":\"critical\",\"delivery\":{\"inbox\":true,\"email\":true}}");
        assertThat(s.source().reduce()).isEqualTo("sum");
        assertThat(s.trigger().debounce()).isEqualTo(Duration.ofSeconds(5));
        assertThat(s.cooldown()).isEqualTo(Duration.ofHours(1));
        assertThat(s.delivery().email()).isTrue();
        assertThat(s.describe()).isEqualTo("between 10 and 20");
        assertThat(s.title()).contains("daily-risk").contains("between 10 and 20");
        AlertSpec back = AlertSpec.parse("alice", "Net", s.toJson(), PROPS);
        assertThat(back).isEqualTo(s);
    }

    @Test
    void theOtherSourcesConditionsAndTriggersRead() {
        AlertSpec d = parse("{\"source\":{\"type\":\"dataset\",\"dataset\":\"trading\",\"measure\":\"mtm\",\"filters\":[{\"field\":\"desk\",\"op\":\"eq\",\"values\":[\"FX\"]}]},"
                + "\"condition\":{\"type\":\"change\",\"mode\":\"pct\",\"op\":\"falls\",\"value\":5,\"against\":\"previous-day\"},\"trigger\":{\"mode\":\"after-load\",\"kind\":\"TRD\"}}");
        assertThat(d.condition().against()).isEqualTo("previous-day");
        assertThat(d.trigger().loadKind()).isEqualTo("TRD");
        assertThat(d.describe()).isEqualTo("falls by 5% against the previous day");
        assertThat(AlertSpec.parse("alice", "Net", d.toJson(), PROPS)).isEqualTo(d);
        AlertSpec f = parse("{\"source\":{\"type\":\"field\",\"kind\":\"TRD\",\"id\":\"T-1001\",\"field\":\"mtm\"},\"condition\":{\"type\":\"nodata\",\"by\":\"9:30\"},"
                + "\"trigger\":{\"mode\":\"schedule\",\"schedule\":\"business-days 09:35\"}}");
        assertThat(f.condition().by()).isEqualTo(LocalTime.of(9, 30));
        assertThat(f.source().label()).isEqualTo("TRD/T-1001 · mtm");
        assertThat(AlertSpec.parse("alice", "Net", f.toJson(), PROPS)).isEqualTo(f);
    }

    @Test
    void whatIsWrongIsNamed() {
        String ok = "\"condition\":{\"type\":\"threshold\",\"op\":\"above\",\"value\":1},\"trigger\":{\"mode\":\"schedule\",\"schedule\":\"hourly\"}";
        assertInvalid("{\"source\":{\"type\":\"cube\"}," + ok + "}", "source.type");
        assertInvalid("{\"source\":{\"type\":\"visual\",\"report\":\"r\"}," + ok + "}", "source.visual");
        assertInvalid("{\"source\":{\"type\":\"visual\",\"report\":\"r\",\"visual\":\"v\",\"reduce\":\"mode\"}," + ok + "}", "source.reduce");
        assertInvalid("{\"source\":{\"type\":\"dataset\",\"dataset\":\"d\",\"measure\":\"m\",\"filters\":[{\"field\":\"x\",\"op\":\"like\"}]}," + ok + "}", "source.filters");
        assertInvalid("{" + SOURCE + ",\"condition\":{\"type\":\"threshold\",\"op\":\"over\",\"value\":1},\"trigger\":{}}", "condition.op");
        assertInvalid("{" + SOURCE + ",\"condition\":{\"type\":\"threshold\",\"op\":\"above\"},\"trigger\":{}}", "condition.value");
        assertInvalid("{" + SOURCE + ",\"condition\":{\"type\":\"threshold\",\"op\":\"between\",\"value\":5,\"high\":3},\"trigger\":{}}", "condition.high");
        assertInvalid("{" + SOURCE + ",\"condition\":{\"type\":\"change\",\"value\":0},\"trigger\":{}}", "above zero");
        assertInvalid("{" + SOURCE + ",\"condition\":{\"type\":\"change\",\"value\":3,\"against\":\"last-year\"},\"trigger\":{}}", "condition.against");
        assertInvalid("{" + SOURCE + ",\"condition\":{\"type\":\"nodata\",\"by\":\"soon\"},\"trigger\":{}}", "HH:MM");
        assertInvalid("{" + SOURCE + ",\"condition\":{\"type\":\"nodata\"},\"trigger\":{}}", "condition.by");
        assertInvalid("{" + SOURCE + "," + ok.replace("\"hourly\"", "\"whenever\"") + "}", "trigger.schedule");
        assertInvalid("{" + SOURCE + "," + ok.replace("\"hourly\"", "\"after-load\"") + "}", "after-load is its own mode");
        assertInvalid("{" + SOURCE + "," + ok.replace("\"mode\":\"schedule\",\"schedule\":\"hourly\"", "\"mode\":\"live\",\"debounce\":\"100ms\"") + "}", "trigger.debounce");
        assertInvalid("{" + SOURCE + "," + ok + ",\"severity\":\"loud\"}", "severity");
        assertInvalid("{" + SOURCE + "," + ok + ",\"hysteresis\":-1}", "hysteresis");
        assertInvalid("{" + SOURCE + "," + ok + ",\"cooldown\":\"a while\"}", "cooldown");
        assertInvalid("{" + SOURCE + "," + ok + ",\"delivery\":{\"inbox\":false}}", "at least one");
        assertThatThrownBy(() -> AlertSpec.parse("alice", "bad/name", JSON.createObjectNode(), PROPS)).hasMessageContaining("name is 1-64");
    }

    static void assertInvalid(String json, String contains) {
        assertThatThrownBy(() -> parse(json)).isInstanceOf(DrishtiException.class).hasMessageContaining(contains).satisfies(e -> assertThat(((DrishtiException) e).errorCode().code()).isEqualTo("DRS-9062"));
    }

    @Test
    void defaultsAreThoseOfTheSettings() {
        AlertSpec s = parse("{" + SOURCE + ",\"condition\":{\"type\":\"threshold\",\"op\":\"above\",\"value\":1}}");
        assertThat(s.severity()).isEqualTo("warn");
        assertThat(s.trigger().mode()).isEqualTo("schedule");
        assertThat(s.trigger().schedule()).isEqualTo("hourly");
        assertThat(s.cooldown()).isEqualTo(Duration.ofMinutes(30));
        assertThat(s.delivery().inbox()).isTrue();
        assertThat(s.delivery().email()).isFalse();
        assertThat(s.enabled()).isTrue();
        assertThat(s.key()).isEqualTo("alice/Net");
    }

    @Test
    void aTileTakesTheWorstLevelOfItsRowsLikeTheViewer() {
        Board.Alert a = new Board.Alert("", new Board.Rule("lt", 0), new Board.Rule("lt", -5));
        assertThat(TileLevel.rank(a, List.of(10.0, 3.0))).isEqualTo(0);
        assertThat(TileLevel.rank(a, List.of(10.0, -1.0))).isEqualTo(1);
        assertThat(TileLevel.rank(a, List.of(-1.0, -6.0, 4.0))).isEqualTo(2);         // red wins over amber, whatever the order
        assertThat(TileLevel.rank(null, List.of(-9.0))).isEqualTo(0);
        assertThat(TileLevel.word(2)).isEqualTo("red");
        assertThat(TileLevel.word(1)).isEqualTo("amber");
        for (String op : List.of("lt", "le", "gt", "ge", "eq", "ne")) {
            assertThat(TileLevel.test(new Board.Rule(op, 5), 5)).isEqualTo(List.of("le", "ge", "eq").contains(op));
        }
    }
}
