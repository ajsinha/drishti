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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericEnumSymbol;
import org.apache.avro.generic.GenericFixed;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.kafka.common.serialization.Deserializer;

/**
 * Reads a Kafka message value as JSON text, whatever wrote it. A value in the Confluent wire format (a zero byte, a 4-byte
 * schema id, then the payload) is decoded by what the schema registry says its schema is: JSON Schema payloads are JSON
 * already, Avro payloads are decoded with the writer schema and written as JSON; Protobuf is not supported yet. Any other
 * value is plain UTF-8 text, as before.
 *
 * <p>Without a registry ({@code registry == null}) only JSON Schema payloads can be stripped: a zero byte followed by an id and
 * then an opening brace or bracket is taken as JSON Schema.
 *
 * <p>A value that cannot be decoded is returned as text no JSON parser accepts, which the source skips (a null would be read
 * as a tombstone and delete the entity); it is logged, the first ten times and then every thousandth.
 */
final class ConfluentValueDeserializer implements Deserializer<String> {

    private static final System.Logger LOG = System.getLogger("com.ash.drishti.plugin.kafka");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    /** Returned for a value that could not be decoded: not JSON, so the source skips it. */
    static final String UNREADABLE = "\u0000unreadable";

    private final SchemaRegistryClient registry;
    private final AtomicLong failures = new AtomicLong();

    ConfluentValueDeserializer(SchemaRegistryClient registry) {
        this.registry = registry;
    }

    @Override
    public String deserialize(String topic, byte[] data) {
        if (data == null) {
            return null;
        }
        if (data.length > 5 && data[0] == 0) {
            try {
                return decode(data);
            } catch (IOException | RuntimeException e) {
                long n = failures.incrementAndGet();
                if (n <= 10 || n % 1000 == 0) {
                    LOG.log(System.Logger.Level.WARNING, "kafka topic " + topic + ": a message in the Confluent wire format could not be read ("
                            + e.getMessage() + "); skipped (" + n + " so far)");
                }
                return UNREADABLE;
            }
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private String decode(byte[] data) throws IOException {
        int id = ByteBuffer.wrap(data, 1, 4).getInt();
        if (registry == null) {
            int first = data[5] & 0xff;
            if (first == '{' || first == '[') {
                return new String(data, 5, data.length - 5, StandardCharsets.UTF_8);
            }
            throw new IOException("schema id " + id + " needs schema-registry.url (only JSON Schema payloads can be read without it)");
        }
        SchemaRegistryClient.Registered reg = registry.schema(id);
        switch (reg.type()) {
            case SchemaRegistryClient.Registered.JSON:
                return new String(data, 5, data.length - 5, StandardCharsets.UTF_8);
            case SchemaRegistryClient.Registered.AVRO: {
                BinaryDecoder d = DecoderFactory.get().binaryDecoder(data, 5, data.length - 5, null);
                Object value = new GenericDatumReader<Object>(reg.avro()).read(null, d);
                return JSON.writeValueAsString(toJson(value, reg.avro()));
            }
            default:
                throw new IOException("schema id " + id + " is " + reg.type() + ", which is not supported yet (JSON Schema and Avro are)");
        }
    }

    /** An Avro datum as JSON: unions are their value, enums their symbol, bytes and fixed base64. */
    static com.fasterxml.jackson.databind.JsonNode toJson(Object v, Schema schema) {
        if (v == null) {
            return NODES.nullNode();
        }
        if (schema.getType() == Schema.Type.UNION) {
            return toJson(v, unionBranch(schema, v));
        }
        switch (schema.getType()) {
            case RECORD: {
                GenericRecord r = (GenericRecord) v;
                ObjectNode o = NODES.objectNode();
                for (Schema.Field f : schema.getFields()) {
                    o.set(f.name(), toJson(r.get(f.pos()), f.schema()));
                }
                return o;
            }
            case ARRAY: {
                ArrayNode a = NODES.arrayNode();
                for (Object e : (Iterable<?>) v) {
                    a.add(toJson(e, schema.getElementType()));
                }
                return a;
            }
            case MAP: {
                ObjectNode o = NODES.objectNode();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    o.set(String.valueOf(e.getKey()), toJson(e.getValue(), schema.getValueType()));
                }
                return o;
            }
            case ENUM:
                return NODES.textNode(v instanceof GenericEnumSymbol<?> s ? s.toString() : String.valueOf(v));
            case FIXED:
                return NODES.textNode(java.util.Base64.getEncoder().encodeToString(((GenericFixed) v).bytes()));
            case BYTES: {
                ByteBuffer b = ((ByteBuffer) v).duplicate();
                byte[] bytes = new byte[b.remaining()];
                b.get(bytes);
                return NODES.textNode(java.util.Base64.getEncoder().encodeToString(bytes));
            }
            case STRING:
                return NODES.textNode(v.toString());
            case INT:
                return NODES.numberNode((Integer) v);
            case LONG:
                return NODES.numberNode((Long) v);
            case FLOAT:
                return NODES.numberNode((Float) v);
            case DOUBLE:
                return NODES.numberNode((Double) v);
            case BOOLEAN:
                return NODES.booleanNode((Boolean) v);
            default:
                return NODES.nullNode();
        }
    }

    private static Schema unionBranch(Schema union, Object v) {
        for (Schema s : union.getTypes()) {
            boolean match = switch (s.getType()) {
                case NULL -> false;
                case RECORD -> v instanceof GenericRecord r && r.getSchema().getFullName().equals(s.getFullName());
                case ARRAY -> v instanceof Iterable && !(v instanceof GenericRecord);
                case MAP -> v instanceof Map;
                case ENUM -> v instanceof GenericEnumSymbol;
                case FIXED -> v instanceof GenericFixed;
                case BYTES -> v instanceof ByteBuffer;
                case STRING -> v instanceof CharSequence;
                case INT -> v instanceof Integer;
                case LONG -> v instanceof Long;
                case FLOAT -> v instanceof Float;
                case DOUBLE -> v instanceof Double;
                case BOOLEAN -> v instanceof Boolean;
                default -> false;
            };
            if (match) {
                return s;
            }
        }
        return union.getTypes().get(0);
    }
}
