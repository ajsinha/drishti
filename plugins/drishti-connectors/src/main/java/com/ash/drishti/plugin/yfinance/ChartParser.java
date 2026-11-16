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
package com.ash.drishti.plugin.yfinance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Yahoo's chart answer into a quote and a price history, defensively: every field is optional, a number that is not a
 * number is a missing number, and an answer that is not the expected shape is an error, never a half-empty quote. The size
 * is limited before bytes arrive here.
 */
final class ChartParser {

    /** The symbol is unknown to Yahoo. */
    static final class UnknownSymbol extends IOException {
        UnknownSymbol(String message) {
            super(message);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ChartParser() {}

    static Map<String, Object> parse(byte[] body, String interval, Instant now) throws IOException {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw new IOException("the response is not readable JSON: " + e.getClass().getSimpleName(), e);
        }
        JsonNode chart = root == null ? null : root.path("chart");
        if (chart == null || chart.isMissingNode() || !chart.isObject()) {
            throw new IOException("the response is not a Yahoo chart answer");
        }
        JsonNode err = chart.path("error");
        if (err.isObject()) {
            String code = err.path("code").asText("");
            String desc = err.path("description").asText(code);
            if (code.equalsIgnoreCase("Not Found")) {
                throw new UnknownSymbol(desc);
            }
            throw new IOException("Yahoo says: " + desc);
        }
        JsonNode result = chart.path("result");
        if (!result.isArray() || result.isEmpty() || !result.get(0).path("meta").isObject()) {
            throw new IOException("the response holds no chart result");
        }
        JsonNode r = result.get(0);
        JsonNode meta = r.get("meta");
        int offset = meta.path("gmtoffset").asInt(0);
        List<Map<String, Object>> history = history(r, interval, offset);

        Double price = num(meta, "regularMarketPrice");
        if (price == null && !history.isEmpty()) {
            price = (Double) history.get(history.size() - 1).get("close");
        }
        if (price == null) {
            throw new IOException("the response holds no price");
        }
        Double previous = num(meta, "previousClose");
        if (previous == null && interval.equals("1d") && history.size() >= 2) {
            previous = (Double) history.get(history.size() - 2).get("close");
        }
        if (previous == null) {
            previous = num(meta, "chartPreviousClose");
        }
        Double low = num(meta, "regularMarketDayLow");
        Double high = num(meta, "regularMarketDayHigh");
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("symbol", meta.path("symbol").asText(""));
        String name = meta.path("longName").asText(meta.path("shortName").asText(""));
        q.put("name", name);
        q.put("exchange", meta.path("fullExchangeName").asText(meta.path("exchangeName").asText("")));
        q.put("currency", meta.path("currency").asText(""));
        q.put("instrumentType", meta.path("instrumentType").asText(""));
        q.put("price", price);
        q.put("previousClose", previous);
        q.put("change", previous == null ? null : price - previous);
        q.put("changePct", previous == null || previous == 0 ? null : (price - previous) / previous * 100);
        q.put("dayLow", low);
        q.put("dayHigh", high);
        q.put("fiftyTwoWeekLow", num(meta, "fiftyTwoWeekLow"));
        q.put("fiftyTwoWeekHigh", num(meta, "fiftyTwoWeekHigh"));
        q.put("volume", num(meta, "regularMarketVolume"));
        q.put("marketCap", num(meta, "marketCap"));
        q.put("marketState", marketState(meta, now));
        long t = meta.path("regularMarketTime").asLong(0);
        q.put("asOf", t == 0 ? "" : MINUTE.format(Instant.ofEpochSecond(t).atOffset(ZoneOffset.ofTotalSeconds(offset))) + " " + meta.path("exchangeTimezoneName").asText(""));
        q.put("history", history);
        return q;
    }

    private static List<Map<String, Object>> history(JsonNode r, String interval, int offset) {
        JsonNode ts = r.path("timestamp");
        JsonNode quote = r.path("indicators").path("quote").path(0);
        List<Map<String, Object>> out = new ArrayList<>();
        if (!ts.isArray()) {
            return out;
        }
        boolean daily = interval.endsWith("d") || interval.endsWith("wk") || interval.endsWith("mo");
        for (int i = 0; i < ts.size(); i++) {
            Double close = at(quote.path("close"), i);
            if (close == null || !ts.get(i).canConvertToLong()) {
                continue;                                              // a bar without a close (a holiday, a gap) is skipped
            }
            var local = Instant.ofEpochSecond(ts.get(i).asLong()).atOffset(ZoneOffset.ofTotalSeconds(offset));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", (daily ? DAY : MINUTE).format(local));
            row.put("open", at(quote.path("open"), i));
            row.put("high", at(quote.path("high"), i));
            row.put("low", at(quote.path("low"), i));
            row.put("close", close);
            row.put("volume", at(quote.path("volume"), i));
            out.add(row);
        }
        return out;
    }

    private static String marketState(JsonNode meta, Instant now) {
        String given = meta.path("marketState").asText("");
        if (!given.isEmpty()) {
            return given;
        }
        JsonNode reg = meta.path("currentTradingPeriod").path("regular");
        long start = reg.path("start").asLong(0);
        long end = reg.path("end").asLong(0);
        if (start == 0 || end == 0) {
            return "UNKNOWN";
        }
        long n = now.getEpochSecond();
        return n >= start && n < end ? "REGULAR" : "CLOSED";
    }

    private static Double num(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNumber() && Double.isFinite(v.asDouble()) ? v.asDouble() : null;
    }

    private static Double at(JsonNode arr, int i) {
        JsonNode v = arr.isArray() && i < arr.size() ? arr.get(i) : null;
        return v != null && v.isNumber() && Double.isFinite(v.asDouble()) ? v.asDouble() : null;
    }
}
