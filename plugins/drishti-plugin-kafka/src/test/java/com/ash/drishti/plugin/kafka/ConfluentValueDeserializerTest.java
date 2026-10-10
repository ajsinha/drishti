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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.tls.TlsContexts;
import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.api.tls.TlsMaterial;
import com.ash.drishti.api.tls.TlsSettings;
import com.ash.drishti.testkit.TestPki;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Confluent wire format: recorded bytes, Avro and JSON Schema payloads, a registry over HTTP and over HTTPS. */
class ConfluentValueDeserializerTest {

    static final String ORDER = """
            {"type":"record","name":"Order","namespace":"x","fields":[
              {"name":"id","type":"long"},{"name":"name","type":"string"},
              {"name":"note","type":["null","string"],"default":null},
              {"name":"side","type":{"type":"enum","name":"Side","symbols":["BUY","SELL"]}},
              {"name":"tags","type":{"type":"array","items":"string"}},
              {"name":"px","type":"double"}]}""";

    HttpServer server;
    final AtomicInteger fetches = new AtomicInteger();
    final List<String> authHeaders = new java.util.concurrent.CopyOnWriteArrayList<>();
    @TempDir
    Path dir;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** A registry holding id 7 as Avro (Order), id 8 as JSON Schema, id 9 as Protobuf. */
    void registry(HttpServer s, String expectedAuth) throws IOException {
        server = s;
        s.createContext("/schemas/ids/", (HttpExchange x) -> {
            fetches.incrementAndGet();
            String auth = x.getRequestHeaders().getFirst("Authorization");
            authHeaders.add(String.valueOf(auth));
            String path = x.getRequestURI().getPath();
            int status = 200;
            String body;
            if (expectedAuth != null && !expectedAuth.equals(auth)) {
                status = 401;
                body = "{\"error_code\":40101}";
            } else if (path.endsWith("/7")) {
                body = "{\"schema\":" + quote(ORDER) + "}";
            } else if (path.endsWith("/8")) {
                body = "{\"schemaType\":\"JSON\",\"schema\":\"{\\\"type\\\":\\\"object\\\"}\"}";
            } else if (path.endsWith("/9")) {
                body = "{\"schemaType\":\"PROTOBUF\",\"schema\":\"syntax = \\\"proto3\\\";\"}";
            } else {
                status = 404;
                body = "{\"error_code\":40403}";
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/vnd.schemaregistry.v1+json");
            x.sendResponseHeaders(status, out.length);
            x.getResponseBody().write(out);
            x.close();
        });
        s.start();
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    SchemaRegistryClient plainRegistry(String... extra) throws IOException {
        registry(HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0), null);
        return new SchemaRegistryClient(settings("schema-registry.url", "http://127.0.0.1:" + server.getAddress().getPort(), extra));
    }

    static Map<String, String> settings(String k, String v, String... extra) {
        Map<String, String> m = new HashMap<>(Map.of(k, v));
        for (int i = 0; i < extra.length; i += 2) {
            m.put(extra[i], extra[i + 1]);
        }
        return m;
    }

    static byte[] frame(int id, byte[] payload) {
        byte[] out = new byte[5 + payload.length];
        out[4] = (byte) id;
        System.arraycopy(payload, 0, out, 5, payload.length);
        return out;
    }

    static byte[] avroOrder() throws IOException {
        Schema schema = new Schema.Parser().parse(ORDER);
        GenericRecord r = new GenericData.Record(schema);
        r.put("id", 42L);
        r.put("name", "ab");
        r.put("note", "hello");
        r.put("side", new GenericData.EnumSymbol(schema.getField("side").schema(), "SELL"));
        r.put("tags", List.of("x", "y"));
        r.put("px", 1.5);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryEncoder enc = EncoderFactory.get().binaryEncoder(out, null);
        new GenericDatumWriter<GenericRecord>(schema).write(r, enc);
        enc.flush();
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- recorded bytes

    @Test
    void recordedBytesOfAnAvroMessageAreDecodedToJson() throws Exception {
        // recorded: magic 0, schema id 7, then the Avro binary of Order{id=42,name="ab",note="hello",side=SELL,tags=[x,y],px=1.5}
        byte[] recorded = HexFormat.of().parseHex("00" + "00000007" + "54" + "0461" + "62" + "02" + "0a68656c6c6f" + "02" + "04" + "0278" + "0279" + "00" + "000000000000f83f");
        assertThat(avroOrder()).isEqualTo(java.util.Arrays.copyOfRange(recorded, 5, recorded.length));
        ConfluentValueDeserializer d = new ConfluentValueDeserializer(plainRegistry());
        String json = d.deserialize("orders", recorded);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).toString())
                .isEqualTo("{\"id\":42,\"name\":\"ab\",\"note\":\"hello\",\"side\":\"SELL\",\"tags\":[\"x\",\"y\"],\"px\":1.5}");
    }

    @Test
    void aNullUnionAndTheSchemaIsFetchedOnlyOnce() throws Exception {
        ConfluentValueDeserializer d = new ConfluentValueDeserializer(plainRegistry());
        for (int i = 0; i < 5; i++) {
            assertThat(d.deserialize("t", frame(7, avroOrder()))).contains("\"id\":42");
        }
        assertThat(fetches.get()).isEqualTo(1);
    }

    @Test
    void jsonSchemaPayloadsAreStrippedAndPlainTextAndTombstonesPassThrough() throws Exception {
        ConfluentValueDeserializer d = new ConfluentValueDeserializer(plainRegistry());
        assertThat(d.deserialize("t", frame(8, "{\"id\":\"A-1\"}".getBytes(StandardCharsets.UTF_8)))).isEqualTo("{\"id\":\"A-1\"}");
        assertThat(d.deserialize("t", "{\"id\":\"A-2\"}".getBytes(StandardCharsets.UTF_8))).isEqualTo("{\"id\":\"A-2\"}");
        assertThat(d.deserialize("t", null)).isNull();
    }

    @Test
    void withoutARegistryOnlyJsonSchemaCanBeStripped() {
        ConfluentValueDeserializer d = new ConfluentValueDeserializer(null);
        assertThat(d.deserialize("t", frame(3, "{\"a\":1}".getBytes(StandardCharsets.UTF_8)))).isEqualTo("{\"a\":1}");
        assertThat(d.deserialize("t", frame(3, new byte[] {0x54, 0x04}))).isEqualTo(ConfluentValueDeserializer.UNREADABLE);
    }

    @Test
    void protobufUnknownIdsAndDamagedPayloadsAreSkippedNotDeleted() throws Exception {
        ConfluentValueDeserializer d = new ConfluentValueDeserializer(plainRegistry());
        assertThat(d.deserialize("t", frame(9, new byte[] {1, 2, 3}))).isEqualTo(ConfluentValueDeserializer.UNREADABLE);
        assertThat(d.deserialize("t", frame(99, new byte[] {1, 2, 3}))).isEqualTo(ConfluentValueDeserializer.UNREADABLE);
        assertThat(d.deserialize("t", frame(7, new byte[] {1}))).isEqualTo(ConfluentValueDeserializer.UNREADABLE);
        assertThat(ConfluentValueDeserializer.UNREADABLE).doesNotStartWith("{");
    }

    // ---------------------------------------------------------------- the registry client

    @Test
    void basicAuthAndBearerAreSentAndRefusalIsExplained() throws Exception {
        registry(HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0),
                "Basic " + Base64.getEncoder().encodeToString("KEY1:SEC/ret".getBytes(StandardCharsets.UTF_8)));
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        SchemaRegistryClient ok = new SchemaRegistryClient(settings("schema-registry.url", url, "schema-registry.basic-auth", "${SR_KEY}:${SR_SECRET}"),
                k -> k.equals("SR_KEY") ? "KEY1" : k.equals("SR_SECRET") ? "SEC/ret" : null);
        assertThat(ok.schema(7).type()).isEqualTo("AVRO");
        assertThat(ok.schema(8).type()).isEqualTo("JSON");
        assertThat(ok.schema(9).type()).isEqualTo("PROTOBUF");
        SchemaRegistryClient wrong = new SchemaRegistryClient(settings("schema-registry.url", url, "schema-registry.basic-auth", "KEY1:bad"));
        assertThatThrownBy(() -> wrong.schema(7)).hasMessageContaining("refused the credentials").hasMessageContaining("HTTP 401");
        SchemaRegistryClient bearer = new SchemaRegistryClient(settings("schema-registry.url", url, "schema-registry.bearer-token", "tok"));
        assertThatThrownBy(() -> bearer.schema(7)).hasMessageContaining("HTTP 401");
        assertThat(authHeaders).contains("Bearer tok");
        assertThatThrownBy(() -> new SchemaRegistryClient(settings("schema-registry.url", url, "schema-registry.basic-auth", "nocolon")))
                .isInstanceOf(TlsException.class).hasMessageContaining("KEY:SECRET");
        assertThatThrownBy(() -> new SchemaRegistryClient(settings("schema-registry.url", url, "schema-registry.basic-auth", "a:b", "schema-registry.bearer-token", "t")))
                .isInstanceOf(TlsException.class).hasMessageContaining("use one");
        assertThatThrownBy(() -> new SchemaRegistryClient(settings("schema-registry.url", url, "schema-registry.tls.ca-file", "x")))
                .isInstanceOf(TlsException.class).hasMessageContaining("use https://");
    }

    @Test
    void aRegistryOverHttpsWithAPrivateCaIsReadThroughTheSharedTlsModule() throws Exception {
        TestPki.Identity ca = TestPki.ca("registry-ca");
        TestPki.Identity srv = TestPki.issue(ca, "registry", "RSA", 30);
        Path cert = dir.resolve("s.pem");
        Path key = dir.resolve("s.key");
        Path caFile = dir.resolve("ca.pem");
        srv.writeCertPem(cert);
        srv.writeKeyPem(key, TestPki.KeyFormat.PKCS8, null);
        ca.writeCertPem(caFile);
        TlsMaterial tls = TlsContexts.build(TlsSettings.from(Map.of("tls.cert-file", cert.toString(), "tls.key-file", key.toString())));
        HttpsServer https = HttpsServer.create(new InetSocketAddress(0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(tls.sslContext()));
        registry(https, null);
        String url = "https://localhost:" + https.getAddress().getPort();

        SchemaRegistryClient trusting = new SchemaRegistryClient(settings("schema-registry.url", url, "schema-registry.tls.ca-file", caFile.toString()));
        assertThat(trusting.schema(7).type()).isEqualTo("AVRO");
        assertThat(trusting.tls().trusted()).hasSize(1);
        // without the private CA the JVM refuses the certificate
        SchemaRegistryClient strangers = new SchemaRegistryClient(settings("schema-registry.url", url));
        assertThatThrownBy(() -> strangers.schema(7)).hasMessageContaining("PKIX path building failed");
        // a name the certificate does not carry is refused, unless verification is switched off
        String wrongName = "https://127.0.0.2:" + https.getAddress().getPort();
        SchemaRegistryClient strict = new SchemaRegistryClient(settings("schema-registry.url", wrongName, "schema-registry.tls.ca-file", caFile.toString()));
        assertThatThrownBy(() -> strict.schema(7)).isInstanceOf(IOException.class);
        SchemaRegistryClient lax = new SchemaRegistryClient(settings("schema-registry.url", wrongName, "schema-registry.tls.ca-file", caFile.toString(),
                "schema-registry.tls.verify-hostname", "false"));
        assertThat(lax.schema(7).type()).isEqualTo("AVRO");
    }
}
