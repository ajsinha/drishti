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
package com.ash.drishti.server.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Webhook and text-message sending against fake HTTP servers: the signature, the host allow-list, retryable and permanent answers, the gateway templates and the length limit. */
class NotifyChannelsTest {

    record Seen(String method, String path, String query, Map<String, String> headers, String body) {}

    final List<Seen> seen = new CopyOnWriteArrayList<>();
    final AtomicInteger status = new AtomicInteger(200);
    HttpServer server;
    int port;
    final Clock clock = Clock.fixed(Instant.parse("2026-11-25T22:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Map<String, String> h = new java.util.HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
            seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery(), h, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            ex.sendResponseHeaders(status.get(), -1);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    NotifyChannels channels(String... kv) {
        java.util.Map<String, String> flat = new java.util.LinkedHashMap<>();
        flat.put("webhook.allowed-hosts", "127.0.0.1, *.corp.example");
        flat.put("webhook.allow-http", "true");
        flat.put("webhooks.ops.url", "http://127.0.0.1:" + port + "/hook?token=abc");
        flat.put("webhooks.ops.secret", "${DRISHTI_TEST_NO_SUCH_VARIABLE:s3cret}");
        flat.put("webhooks.ops.header.X-Team", "risk");
        flat.put("webhooks.other.url", "http://localhost:" + port + "/hook");
        flat.put("sms.url", "http://127.0.0.1:" + port + "/sms?to={to}&text={text}");
        flat.put("sms.method", "POST");
        flat.put("sms.body", "{\"to\":\"{to}\",\"message\":\"{text}\",\"from\":\"{sender}\"}");
        flat.put("sms.sender", "Drishti");
        flat.put("sms.header.Authorization", "Bearer ${DRISHTI_TEST_NO_SUCH_VARIABLE:tok}");
        flat.put("sms.max-chars", "40");
        for (int i = 0; i < kv.length; i += 2) {
            flat.put(kv[i], kv[i + 1]);
        }
        return new NotifyChannels(NotifySettings.of(flat), clock);
    }

    @Test
    void aWebhookIsPostedAsSignedJsonToTheDefinedUrlOnly() throws Exception {
        ObjectNode body = new ObjectMapper().createObjectNode().put("event", "alert").put("alert", "Exposure");
        assertThat(channels().webhook("ops", "alert", body)).isEqualTo(200);
        Seen s = seen.get(0);
        assertThat(s.method()).isEqualTo("POST");
        assertThat(s.path()).isEqualTo("/hook");
        assertThat(s.query()).isEqualTo("token=abc");
        assertThat(s.headers()).containsEntry("content-type", "application/json").containsEntry("x-drishti-event", "alert").containsEntry("x-team", "risk")
                .containsEntry("x-drishti-timestamp", String.valueOf(clock.instant().getEpochSecond()));
        assertThat(s.body()).isEqualTo("{\"event\":\"alert\",\"alert\":\"Exposure\"}");
        assertThat(s.headers().get("x-drishti-signature")).isEqualTo("sha256=" + NotifyChannels.sign("s3cret", String.valueOf(clock.instant().getEpochSecond()), s.body().getBytes(StandardCharsets.UTF_8)));
        // a person cannot pick a URL: only a defined name
        assertThatThrownBy(() -> channels().webhook("http://evil.example/x", "alert", body)).isInstanceOf(NotifyException.class).hasMessageContaining("no webhook named");
    }

    @Test
    void aHostOffTheAllowListIsRefusedBeforeAnythingIsSent() {
        ObjectNode body = new ObjectMapper().createObjectNode().put("event", "alert");
        assertThatThrownBy(() -> channels().webhook("other", "alert", body)).isInstanceOf(NotifyException.class).hasMessageContaining("allowed-hosts")
                .satisfies(e -> assertThat(((NotifyException) e).permanent()).isTrue());
        assertThat(seen).isEmpty();
        assertThatThrownBy(() -> channels("webhook.allowed-hosts", "").webhook("ops", "alert", body)).hasMessageContaining("allowed-hosts");
        assertThatThrownBy(() -> channels("webhook.allow-http", "false").webhook("ops", "alert", body)).hasMessageContaining("not https");
        NotifySettings s = channels().settings();
        assertThat(s.hostAllowed("hooks.corp.example")).isTrue();
        assertThat(s.hostAllowed("corp.example")).isFalse();
        assertThat(s.hostAllowed("evilcorp.example")).isFalse();
    }

    @Test
    void aServerFailureCanBeRetriedAndAClientRefusalCannot() {
        ObjectNode body = new ObjectMapper().createObjectNode().put("event", "alert");
        status.set(503);
        assertThatThrownBy(() -> channels().webhook("ops", "alert", body)).isInstanceOf(NotifyException.class).hasMessage("the webhook answered HTTP 503")
                .satisfies(e -> assertThat(((NotifyException) e).permanent()).isFalse());
        status.set(429);
        assertThatThrownBy(() -> channels().webhook("ops", "alert", body)).satisfies(e -> assertThat(((NotifyException) e).permanent()).isFalse());
        status.set(403);
        assertThatThrownBy(() -> channels().webhook("ops", "alert", body)).hasMessage("the webhook answered HTTP 403").satisfies(e -> assertThat(((NotifyException) e).permanent()).isTrue());
        status.set(302);
        assertThatThrownBy(() -> channels().webhook("ops", "alert", body)).hasMessageContaining("redirect");
        // the message never carries the URL (it may hold a token)
        assertThatThrownBy(() -> channels().webhook("ops", "alert", body)).satisfies(e -> assertThat(e.getMessage()).doesNotContain("token=abc").doesNotContain("127.0.0.1"));
    }

    @Test
    void aTextGoesThroughTheGatewayTemplatesCutToTheLimit() {
        NotifyChannels c = channels();
        assertThat(c.sms("+44 20 7946 0000", "Exposure is ready: http://console/bi/r/x and a lot more words after that")).isEqualTo(200);
        Seen s = seen.get(0);
        assertThat(s.path()).isEqualTo("/sms");
        assertThat(s.query()).startsWith("to=%2B442079460000&text=Exposure+is+ready");
        assertThat(s.headers()).containsEntry("authorization", "Bearer tok").containsEntry("content-type", "application/json");
        com.fasterxml.jackson.databind.JsonNode sent;
        try {
            sent = new ObjectMapper().readTree(s.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        assertThat(sent.path("to").asText()).isEqualTo("+442079460000");
        assertThat(sent.path("from").asText()).isEqualTo("Drishti");
        assertThat(sent.path("message").asText()).hasSize(40).endsWith("…");
        assertThat(c.fit("short")).isEqualTo("short");
        assertThat(c.fit("a  b\n c")).isEqualTo("a b c");
    }

    @Test
    void quotesInATextDoNotBreakTheJsonBodyAndABadNumberOrNoGatewayIsRefused() throws Exception {
        NotifyChannels c = channels("sms.max-chars", "200");
        c.sms("+15551234567", "He said \"hi\" and left");
        assertThat(new ObjectMapper().readTree(seen.get(0).body()).path("message").asText()).isEqualTo("He said \"hi\" and left");
        assertThatThrownBy(() -> c.sms("not a number", "x")).hasMessageContaining("phone number");
        NotifyChannels none = new NotifyChannels(NotifySettings.of(Map.of()), clock);
        assertThat(none.smsConfigured()).isFalse();
        assertThatThrownBy(() -> none.sms("+15551234567", "x")).hasMessageContaining("no SMS gateway");
        status.set(401);
        assertThatThrownBy(() -> c.sms("+15551234567", "x")).hasMessage("the SMS gateway answered HTTP 401");
    }

    @Test
    void getGatewaysAreSupportedAndAMissingSecretVariableIsAPermanentFailure() {
        NotifyChannels c = channels("sms.method", "GET");
        c.sms("+15551234567", "ready");
        assertThat(seen.get(0).method()).isEqualTo("GET");
        assertThat(seen.get(0).query()).isEqualTo("to=%2B15551234567&text=ready");
        NotifyChannels missing = channels("webhooks.ops.secret", "${DRISHTI_TEST_NO_SUCH_VARIABLE_AT_ALL}");
        assertThatThrownBy(() -> missing.webhook("ops", "alert", new ObjectMapper().createObjectNode())).isInstanceOf(NotifyException.class).hasMessageContaining("is not set")
                .satisfies(e -> assertThat(((NotifyException) e).permanent()).isTrue());
    }
}
