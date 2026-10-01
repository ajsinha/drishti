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
package com.ash.drishti.plugin.mongodb;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.RawBsonDocument;

/**
 * How a data domain lives in MongoDB, sized for a million entities a day kept for years: a collection per domain
 * ({@code trading}) with one document per entity per business date, and beside it {@code trading_columns} with the same
 * keys and only the promoted fields.
 *
 * <pre>
 * trading
 * { _id: "trade/MX-20000017/20260930",      the key: kind, id and the day; a point read is one _id lookup
 *   kind: "trade", id: "MX-20000017",
 *   date: 20260930,                         the business day as a number (yyyyMMdd)
 *   doc:  "{\"tradeId\":…}",                the entity's JSON document, as text (or an embedded document: {@link DocFormat})
 *   c:    { tradeId: "MX-20000017", mtm: 1875863.0, counterparty__id: "CP-MERIDIAN-RE", … },   the promoted fields
 *   expireAt: ISODate(…) }                  only when loaded with a TTL
 *
 * trading_columns (only for entities with promoted fields)
 * { _id: "trade/MX-20000017/20260930", kind: "trade", id: "MX-20000017", date: 20260930, c: { … } [, expireAt] }
 * </pre>
 *
 * Two indexes serve every read: the {@code _id} index (a snapshot read is an exact key; an effective read is the last
 * key of the range {@code kind/id/} to {@code kind/id/yyyyMMdd}, so no index on {@code kind, id, date} is needed), and
 * {@code day_ids} on {@code {kind: 1, date: 1, id: 1}}: a kind's business dates (a distinct scan), a day's ids (a
 * covered query) and a day's promoted fields in id order, split into id ranges read at once. A promoted path is a field
 * of {@code c} with its dots as two underscores ({@code counterparty.id} is {@code c.counterparty__id}). A day's columns
 * are read from the narrow {@code _columns} collection (about 600 bytes a document instead of 7 KB: MongoDB reads whole
 * documents, so a day read from the documents takes three to four times longer); the documents keep {@code c} too, so
 * the domain's collection alone answers queries an operator writes in MongoDB. Both collections are created with zstd
 * block compression and the {@code day_ids} index. Writers (the loader, a Kafka consumer, a batch job) and the connector share
 * this class.
 */
public final class MongoLayout {

    public static final String KEY = "_id";
    public static final String KIND = "kind";
    public static final String ID = "id";
    public static final String DATE = "date";
    public static final String DOC = "doc";
    public static final String COLUMNS = "c";
    public static final String EXPIRE_AT = "expireAt";
    /** {@code {kind: 1, date: 1, id: 1}}: dates, a day's ids and a day's columns. */
    public static final String DAY_INDEX = "day_ids";
    /** {@code {expireAt: 1}}, expiring at the time stored: only for TTL retention. */
    public static final String TTL_INDEX = "expire_at";
    /** The suffix of the collection holding only the promoted fields. */
    public static final String COLUMNS_SUFFIX = "_columns";

    /** How the entity's document is stored. */
    public enum DocFormat {
        /** The JSON text: served as it is stored, no conversion (the default, see MONGODB_CONNECTOR.md). */
        STRING,
        /** An embedded BSON document: queryable in MongoDB, converted to JSON on every read. */
        BSON
    }

    private MongoLayout() {
    }

    public static String docKey(String kind, String id, LocalDate date) {
        return kind + "/" + id + "/" + day(date);
    }

    /** The first key of an entity's days (every one of its keys sorts at or after it). */
    public static String entityPrefix(String kind, String id) {
        return kind + "/" + id + "/";
    }

    public static int day(LocalDate date) {
        return date.getYear() * 10_000 + date.getMonthValue() * 100 + date.getDayOfMonth();
    }

    public static LocalDate date(long day) {
        return LocalDate.of((int) (day / 10_000), (int) (day / 100 % 100), (int) (day % 100));
    }

    /** The field of {@code c} a promoted path is stored in: dots become two underscores. */
    public static String field(String path) {
        return path.replace(".", "__");
    }

    /** The promoted path a field of {@code c} holds. */
    public static String path(String field) {
        return field.replace("__", ".");
    }

    /**
     * One entity's document for one business date with its promoted values (path to number, text or null), ready for a
     * replace-or-insert by {@code _id}. Numbers are stored as doubles, everything else as text.
     */
    public static BsonDocument record(String kind, String id, LocalDate date, String json, Map<String, Object> promoted, DocFormat format,
            Date expireAt) {
        BsonDocument c = new BsonDocument();
        promoted.forEach((p, v) -> c.append(field(p), v instanceof Number n ? new BsonDouble(n.doubleValue())
                : v == null ? BsonNull.VALUE : new BsonString(String.valueOf(v))));
        BsonValue doc = format == DocFormat.BSON ? RawBsonDocument.parse(json) : new BsonString(json);
        BsonDocument d = new BsonDocument(KEY, new BsonString(docKey(kind, id, date)))
                .append(KIND, new BsonString(kind)).append(ID, new BsonString(id)).append(DATE, new BsonInt32(day(date)))
                .append(DOC, doc).append(COLUMNS, c);
        if (expireAt != null) {
            d.append(EXPIRE_AT, new org.bson.BsonDateTime(expireAt.getTime()));
        }
        return d;
    }

    /** The collection holding only the promoted fields of {@code collection}. */
    public static String columnsCollection(String collection) {
        return collection + COLUMNS_SUFFIX;
    }

    /** The narrow copy of a {@link #record}: its key, kind, id, date, promoted fields and expiry, without the document. */
    public static BsonDocument columnsRecord(BsonDocument record) {
        BsonDocument d = new BsonDocument(KEY, record.get(KEY)).append(KIND, record.get(KIND)).append(ID, record.get(ID))
                .append(DATE, record.get(DATE)).append(COLUMNS, record.get(COLUMNS));
        if (record.containsKey(EXPIRE_AT)) {
            d.append(EXPIRE_AT, record.get(EXPIRE_AT));
        }
        return d;
    }

    /** When a day's documents expire with TTL retention: {@code days} after the business date (midnight UTC). */
    public static Date expireAt(LocalDate date, int days) {
        return Date.from(date.plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    /**
     * Creates the domain's collection and its {@code _columns} collection (zstd block compression) when they do not
     * exist, and their indexes (idempotent; an index that exists is left as it is). {@code ttl} adds the TTL index on
     * {@code expireAt}.
     */
    public static void prepare(MongoDatabase db, String collection, boolean ttl) {
        create(db, collection, ttl);
        create(db, columnsCollection(collection), ttl);
    }

    private static void create(MongoDatabase db, String collection, boolean ttl) {
        boolean exists = db.listCollectionNames().into(new ArrayList<>()).contains(collection);
        if (!exists) {
            try {
                db.createCollection(collection, new CreateCollectionOptions().storageEngineOptions(
                        new Document("wiredTiger", new Document("configString", "block_compressor=zstd"))));
            } catch (MongoCommandException e) {
                if (e.getErrorCode() != 48) {                      // NamespaceExists: another loader created it first
                    throw e;
                }
            }
        }
        MongoCollection<Document> c = db.getCollection(collection);
        List<IndexModel> indexes = new ArrayList<>();
        indexes.add(new IndexModel(Indexes.ascending(KIND, DATE, ID), new IndexOptions().name(DAY_INDEX)));
        if (ttl) {
            indexes.add(new IndexModel(Indexes.ascending(EXPIRE_AT), new IndexOptions().name(TTL_INDEX).expireAfter(0L, TimeUnit.SECONDS)));
        }
        c.createIndexes(indexes);
    }

    /** The indexes the connector needs that the collection lacks (empty when laid out). */
    public static List<String> missingIndexes(MongoCollection<?> c) {
        List<String> names = new ArrayList<>();
        c.listIndexes().forEach(ix -> names.add(ix.getString("name")));
        return names.contains(DAY_INDEX) ? List.of() : List.of(DAY_INDEX + " {kind: 1, date: 1, id: 1}");
    }
}
