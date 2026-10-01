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
package com.ash.drishti.plugin.aerospike;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Value;
import com.aerospike.client.cdt.ListOperation;
import com.aerospike.client.cdt.ListOrder;
import com.aerospike.client.cdt.ListPolicy;
import com.aerospike.client.cdt.ListWriteFlags;
import com.aerospike.client.policy.WritePolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * How a data domain lives in Aerospike, sized for millions of entities a day kept for years:
 *
 * <ul>
 *   <li>set {@code <domain>}: one record per entity per business date, key {@code kind/id/yyyyMMdd}, bins {@code kind},
 *       {@code id}, {@code date} (yyyyMMdd as a number), {@code doc} (the JSON document) and one bin per field the pack
 *       promotes ({@code layout.<kind>.columns}), for searches over a day without reading documents;</li>
 *   <li>set {@code <domain>_ix}: one record per entity, key {@code kind/id}, bins {@code kind}, {@code id} and
 *       {@code dates} (the business dates it has, sorted): a read finds the right day with one get;</li>
 *   <li>set {@code <domain>_kinds}: one record per kind, key {@code kind}, bin {@code dates}: the business dates the
 *       kind has.</li>
 * </ul>
 *
 * Aerospike bin names are at most 15 characters: a promoted path is its name with dots as underscores, or, when
 * longer, its first 10 characters, an underscore and 4 hex digits of its SHA-1 ({@code counterparty.name} is
 * {@code counterpar_f5fe}). Writers and the connector share this rule.
 */
public final class AerospikeLayout {

    public static final String KIND = "kind";
    public static final String ID = "id";
    public static final String DATE = "date";
    public static final String DOC = "doc";
    public static final String DATES = "dates";
    private static final ListPolicy SORTED_UNIQUE = new ListPolicy(ListOrder.ORDERED, ListWriteFlags.ADD_UNIQUE | ListWriteFlags.NO_FAIL);

    private AerospikeLayout() {
    }

    public static String indexSet(String domain) {
        return domain + "_ix";
    }

    public static String kindsSet(String domain) {
        return domain + "_kinds";
    }

    public static String docKey(String kind, String id, LocalDate date) {
        return kind + "/" + id + "/" + day(date);
    }

    public static String indexKey(String kind, String id) {
        return kind + "/" + id;
    }

    public static long day(LocalDate date) {
        return date.getYear() * 10_000L + date.getMonthValue() * 100L + date.getDayOfMonth();
    }

    public static LocalDate date(long day) {
        return LocalDate.of((int) (day / 10_000), (int) (day / 100 % 100), (int) (day % 100));
    }

    /** The bin a promoted path is stored in (at most 15 characters, never one of the reserved bins). */
    public static String bin(String path) {
        String name = path.replace('.', '_');
        if (name.length() <= 15 && !List.of(KIND, ID, DATE, DOC, DATES).contains(name)) {
            return name;
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(path.getBytes(StandardCharsets.UTF_8));
            return name.substring(0, Math.min(10, name.length())) + "_" + HexFormat.of().formatHex(h).substring(0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Writes one entity's document for one business date, with its promoted values (path to number, text or null),
     * and records the date on the entity's index record. The kind's dates are recorded separately ({@link #addDates}),
     * once per load, not once per row.
     */
    public static void write(AerospikeClient c, WritePolicy policy, String namespace, String domain, String kind, String id, LocalDate date,
            String json, Map<String, Object> promoted) {
        List<Bin> bins = new ArrayList<>(4 + promoted.size());
        bins.add(new Bin(KIND, kind));
        bins.add(new Bin(ID, id));
        bins.add(new Bin(DATE, day(date)));
        bins.add(new Bin(DOC, json));
        promoted.forEach((path, v) -> {
            if (v instanceof Number n) {
                bins.add(new Bin(bin(path), n.doubleValue()));
            } else if (v != null) {
                bins.add(new Bin(bin(path), String.valueOf(v)));
            }
        });
        c.put(policy, new Key(namespace, domain, docKey(kind, id, date)), bins.toArray(new Bin[0]));
        c.operate(policy, new Key(namespace, indexSet(domain), indexKey(kind, id)),
                com.aerospike.client.Operation.put(new Bin(KIND, kind)), com.aerospike.client.Operation.put(new Bin(ID, id)),
                ListOperation.append(SORTED_UNIQUE, DATES, Value.get(day(date))));
    }

    /** Records business dates a kind has (idempotent). */
    public static void addDates(AerospikeClient c, WritePolicy policy, String namespace, String domain, String kind, java.util.Collection<LocalDate> dates) {
        List<Value> values = dates.stream().map(d -> Value.get(day(d))).toList();
        c.operate(policy, new Key(namespace, kindsSet(domain), kind), com.aerospike.client.Operation.put(new Bin(KIND, kind)),
                ListOperation.appendItems(SORTED_UNIQUE, DATES, values));
    }
}
