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
package com.ash.drishti.plugin.news;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local stand-in for Google News RSS, so no test ever leaves the machine. The query decides what it answers:
 * {@code Acme} a normal feed (duplicates, a publisher to deny, items that do not name the entity), {@code Evil} an XXE
 * attempt, {@code Huge} a body over the limit, {@code Slow} a response slower than the timeout, {@code Flaky} two 503s and
 * then a feed, {@code Limited} one 429 and then a feed, {@code Forbidden} a 403, {@code Broken} text that is not XML.
 * It counts the calls per query so tests can see the cache work.
 */
public final class FakeNewsServer implements AutoCloseable {

    private final HttpServer server;
    private final ConcurrentHashMap<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private volatile String lastQuery = "";

    public FakeNewsServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/rss/search", ex -> {
            String raw = ex.getRequestURI().getRawQuery();
            lastQuery = raw;
            String q = "";
            for (String p : raw.split("&")) {
                if (p.startsWith("q=")) {
                    q = URLDecoder.decode(p.substring(2), StandardCharsets.UTF_8);
                }
            }
            int n = calls.computeIfAbsent(q, k -> new AtomicInteger()).incrementAndGet();
            int status = 200;
            byte[] body;
            switch (q) {
                case "Evil" -> body = ("<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>"
                        + "<rss><channel><item><title>&xxe;</title><link>https://e.example/1</link></item></channel></rss>").getBytes(StandardCharsets.UTF_8);
                case "Huge" -> body = ("<rss><channel>" + "<!-- " + "x".repeat(300_000) + " --></channel></rss>").getBytes(StandardCharsets.UTF_8);
                case "Slow" -> {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    body = feed(q);
                }
                case "Flaky" -> {
                    status = n <= 2 ? 503 : 200;
                    body = status == 200 ? feed(q) : "busy".getBytes(StandardCharsets.UTF_8);
                }
                case "Limited" -> {
                    status = n == 1 ? 429 : 200;
                    body = status == 200 ? feed(q) : "slow down".getBytes(StandardCharsets.UTF_8);
                }
                case "Forbidden" -> {
                    status = 403;
                    body = "no".getBytes(StandardCharsets.UTF_8);
                }
                case "Broken" -> body = "this is not xml <<<".getBytes(StandardCharsets.UTF_8);
                default -> body = feed(q);
            }
            ex.getResponseHeaders().add("Content-Type", "application/rss+xml");
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    private static byte[] feed(String q) {
        String name = q;
        StringBuilder s = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel><title>" + name + " - Google News</title>");
        s.append(item(name + " opens new plant", "https://n.example/a1", "Mon, 05 Oct 2026 09:00:00 GMT", "Reuters", name + " opens new plant - Reuters"));
        s.append(item(name + " opens new plant", "https://n.example/a1", "Mon, 05 Oct 2026 09:00:00 GMT", "Reuters", "same story, same address"));
        s.append(item(name + " opens new plant!", "https://n.example/a2", "Mon, 05 Oct 2026 10:00:00 GMT", "Reuters", "same headline from the same publisher, a new address"));
        s.append(item("Markets rally on Friday", "https://n.example/b1", "Tue, 06 Oct 2026 08:30:00 GMT", "Bloomberg", "mentions nobody we look for"));
        s.append(item("Why " + name + " shares fell", "https://n.example/c1", "Wed, 07 Oct 2026 12:00:00 GMT", "Blog Spam", "a publisher on the deny list"));
        s.append(item(name.toUpperCase() + " names a new chief executive", "https://n.example/d1", "Thu, 08 Oct 2026 07:15:00 GMT", "Financial Times",
                "<a href=\"x\">" + name + " names a new chief</a>&nbsp;&nbsp;<font color=\"#6f6f6f\">Financial Times</font>"));
        s.append(item("The wider " + name + " story: profits", "https://n.example/e1", "Fri, 09 Oct 2026 18:45:00 GMT", "The Guardian", "profits up"));
        s.append(item("Item without a safe link " + name, "javascript:alert(1)", "Fri, 09 Oct 2026 18:45:00 GMT", "Nobody", "x"));
        s.append("</channel></rss>");
        return s.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String item(String title, String link, String date, String source, String description) {
        return "<item><title>" + esc(title) + " - " + esc(source) + "</title><link>" + esc(link) + "</link><pubDate>" + date + "</pubDate><description>"
                + esc(description) + "</description><source url=\"https://" + source.toLowerCase().replace(' ', '-') + ".example\">" + esc(source) + "</source></item>";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/rss/search";
    }

    public int calls(String query) {
        AtomicInteger n = calls.get(query);
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
