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

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local stand-in for Yahoo's chart endpoint, so no test ever leaves the machine. The symbol decides the answer:
 * {@code ACME} a quote with five daily bars (one without a close), {@code NOPE} Yahoo's not-found error (HTTP 404), {@code BAD}
 * text that is not JSON, {@code ODD} JSON of another shape, {@code BIG} a body over the limit, {@code SLOW} a response slower than
 * the timeout, {@code FLAKY} two 503s then a quote, {@code LIMITED} one 429 then a quote, {@code DENIED} a 403.
 */
public final class FakeYahooServer implements AutoCloseable {

    private final HttpServer server;
    private final ConcurrentHashMap<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private volatile String lastQuery = "";

    public FakeYahooServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v8/finance/chart/", ex -> {
            String symbol = ex.getRequestURI().getPath().substring("/v8/finance/chart/".length());
            lastQuery = ex.getRequestURI().getRawQuery();
            int n = calls.computeIfAbsent(symbol, k -> new AtomicInteger()).incrementAndGet();
            int status = 200;
            byte[] body;
            switch (symbol) {
                case "NOPE" -> {
                    status = 404;
                    body = "{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\",\"description\":\"No data found, symbol may be delisted\"}}}".getBytes(StandardCharsets.UTF_8);
                }
                case "BAD" -> body = "<html>not json".getBytes(StandardCharsets.UTF_8);
                case "ODD" -> body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
                case "BIG" -> body = ("{\"chart\":{\"pad\":\"" + "x".repeat(300_000) + "\"}}").getBytes(StandardCharsets.UTF_8);
                case "SLOW" -> {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    body = quote(symbol);
                }
                case "FLAKY" -> {
                    status = n <= 2 ? 503 : 200;
                    body = status == 200 ? quote(symbol) : "busy".getBytes(StandardCharsets.UTF_8);
                }
                case "LIMITED" -> {
                    status = n == 1 ? 429 : 200;
                    body = status == 200 ? quote(symbol) : "Too Many Requests".getBytes(StandardCharsets.UTF_8);
                }
                case "DENIED" -> {
                    status = 403;
                    body = "{}".getBytes(StandardCharsets.UTF_8);
                }
                default -> body = quote(symbol);
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    /** Five daily bars ending at 191.50 after 189.00; 2026-10-07 has no close. Market cap is absent, as it usually is. */
    static byte[] quote(String symbol) {
        long d = 1_790_000_000L;                                  // 2026-09-21 in epoch seconds, near enough
        String json = "{\"chart\":{\"result\":[{\"meta\":{\"currency\":\"USD\",\"symbol\":\"" + symbol + "\",\"exchangeName\":\"NMS\","
                + "\"fullExchangeName\":\"NasdaqGS\",\"instrumentType\":\"EQUITY\",\"regularMarketTime\":" + (d + 4 * 86400) + ",\"gmtoffset\":-14400,"
                + "\"exchangeTimezoneName\":\"America/New_York\",\"regularMarketPrice\":191.5,\"regularMarketDayHigh\":192.3,\"regularMarketDayLow\":188.9,"
                + "\"regularMarketVolume\":51234567,\"fiftyTwoWeekHigh\":199.6,\"fiftyTwoWeekLow\":140.1,\"chartPreviousClose\":150.0,"
                + "\"longName\":\"" + symbol + " Corporation\",\"currentTradingPeriod\":{\"regular\":{\"start\":1,\"end\":2}}},"
                + "\"timestamp\":[" + d + "," + (d + 86400) + "," + (d + 2 * 86400) + "," + (d + 3 * 86400) + "," + (d + 4 * 86400) + "],"
                + "\"indicators\":{\"quote\":[{\"open\":[180.0,182.0,184.0,187.0,189.2],\"high\":[183.0,185.0,188.0,190.0,192.3],"
                + "\"low\":[179.0,181.0,183.0,186.0,188.9],\"close\":[182.0,184.5,null,189.0,191.5],\"volume\":[1000,2000,null,4000,51234567]}]}}],\"error\":null}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v8/finance/chart";
    }

    public int calls(String symbol) {
        AtomicInteger n = calls.get(symbol);
        return n == null ? 0 : n.get();
    }

    public String lastQuery() {
        return lastQuery;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
