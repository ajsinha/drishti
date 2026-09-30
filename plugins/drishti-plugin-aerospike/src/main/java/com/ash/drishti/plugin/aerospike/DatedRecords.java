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

import com.aerospike.client.Bin;
import com.aerospike.client.Record;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The record layout: one record per entity (key {@code kind/id}) in the set of its data domain, with bins
 * {@code kind}, {@code id} and one bin per business date, {@code d20260930}, holding that day's JSON document.
 * Bin names stay within Aerospike's 15-character limit.
 */
final class DatedRecords {

    static final String KIND = "kind";
    static final String ID = "id";
    private static final DateTimeFormatter BIN = DateTimeFormatter.ofPattern("'d'yyyyMMdd");

    private DatedRecords() {
    }

    static String key(String kind, String id) {
        return kind + "/" + id;
    }

    static String bin(LocalDate date) {
        return date.format(BIN);
    }

    static Bin[] bins(String kind, String id, LocalDate date, String json) {
        return new Bin[] {new Bin(KIND, kind), new Bin(ID, id), new Bin(bin(date), json)};
    }

    /** The record's documents by business date. */
    static NavigableMap<LocalDate, String> byDate(Record r) {
        NavigableMap<LocalDate, String> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : r.bins.entrySet()) {
            String n = e.getKey();
            if (n.length() == 9 && n.charAt(0) == 'd' && e.getValue() instanceof String json) {
                out.put(LocalDate.parse(n.substring(1), DateTimeFormatter.BASIC_ISO_DATE), json);
            }
        }
        return out;
    }
}
