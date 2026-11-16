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
package com.ash.drishti.plugin.feeds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** The built-in public feeds: NY Fed SOFR, ECB €STR, ECB euro reference FX rates, US Treasury par yields, FRED series. */
final class Feeds {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Feeds() {
    }

    static Feed named(String name) {
        return switch (name) {
            case "nyfed-sofr" -> new Fixing("FIX-SOFR-NYFED", "Secured Overnight Financing Rate (NY Fed)", "Federal Reserve Bank of New York", "USD",
                    "https://markets.newyorkfed.org/api/rates/secured/sofr/last/60.json") {
                @Override
                NavigableMap<LocalDate, Map<String, Object>> read(String body) throws Exception {
                    NavigableMap<LocalDate, Map<String, Object>> out = new TreeMap<>();
                    for (JsonNode r : JSON.readTree(body).path("refRates")) {
                        out.put(LocalDate.parse(r.path("effectiveDate").asText()), Map.of("ratePct", r.path("percentRate").asDouble(),
                                "volumeBn", r.path("volumeInBillions").asDouble(), "p1", r.path("percentPercentile1").asDouble(),
                                "p99", r.path("percentPercentile99").asDouble()));
                    }
                    return out;
                }
            };
            case "ecb-estr" -> new Fixing("FIX-ESTR-ECB", "Euro short-term rate (ECB)", "European Central Bank", "EUR",
                    "https://data-api.ecb.europa.eu/service/data/EST/B.EU000A2X2A25.WT?lastNObservations=60&format=csvdata") {
                @Override
                NavigableMap<LocalDate, Map<String, Object>> read(String body) {
                    NavigableMap<LocalDate, Map<String, Object>> out = new TreeMap<>();
                    List<List<String>> rows = csv(body);
                    List<String> head = rows.get(0);
                    int t = head.indexOf("TIME_PERIOD");
                    int v = head.indexOf("OBS_VALUE");
                    for (List<String> r : rows.subList(1, rows.size())) {
                        if (r.size() > Math.max(t, v) && !r.get(v).isBlank()) {
                            out.put(LocalDate.parse(r.get(t)), Map.of("ratePct", Double.parseDouble(r.get(v))));
                        }
                    }
                    return out;
                }
            };
            case "fred" -> new FredSeries();
            case "ecb-fx" -> new EcbFx();
            case "us-treasury" -> new UsTreasury();
            default -> throw new IllegalArgumentException("unknown feed '" + name + "' (nyfed-sofr, ecb-estr, ecb-fx, us-treasury, fred)");
        };
    }

    /** An overnight rate fixing, as a {@code rate-fixing} document. */
    abstract static class Fixing implements Feed {
        private final String id;
        private final String name;
        private final String administrator;
        private final String currency;
        private final String url;

        Fixing(String id, String name, String administrator, String currency, String url) {
            this.id = id;
            this.name = name;
            this.administrator = administrator;
            this.currency = currency;
            this.url = url;
        }

        abstract NavigableMap<LocalDate, Map<String, Object>> read(String body) throws Exception;

        @Override
        public List<String> urls(Map<String, String> settings, LocalDate today) {
            return List.of(settings.getOrDefault("url", url));
        }

        @Override
        public List<Series> parse(List<String> bodies, Map<String, String> settings) throws Exception {
            return List.of(new Series("rate-fixing", id, read(bodies.get(0))));
        }

        @Override
        public Map<String, Object> document(Series s, LocalDate date) {
            return fixingDocument(s, date, name, administrator, currency, "Overnight");
        }

        @Override
        public List<String> kinds() {
            return List.of("rate-fixing");
        }
    }

    static Map<String, Object> fixingDocument(Feed.Series s, LocalDate date, String name, String administrator, String currency, String tenor) {
        NavigableMap<LocalDate, Map<String, Object>> upTo = s.observations().headMap(date, true).descendingMap();
        List<Map<String, Object>> fixings = new ArrayList<>();
        upTo.entrySet().stream().limit(20).forEach(e -> {
            double pct = ((Number) e.getValue().get("ratePct")).doubleValue();
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("date", e.getKey().toString());
            f.put("rate", Math.round(pct * 1e4) / 1e6);
            f.put("ratePct", pct);
            if (e.getValue().get("volumeBn") != null) {
                f.put("volumeBn", e.getValue().get("volumeBn"));
            }
            fixings.add(f);
        });
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("indexId", s.id());
        doc.put("name", name);
        doc.put("latest", fixings.isEmpty() ? null : fixings.get(0).get("rate"));
        doc.put("administrator", administrator);
        doc.put("tenor", tenor);
        doc.put("currency", currency);
        doc.put("fixings", fixings);
        return doc;
    }

    static List<List<String>> csv(String body) {
        List<List<String>> out = new ArrayList<>();
        for (String line : body.split("\\r?\\n")) {
            if (line.isBlank()) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    quoted = !quoted;
                } else if (c == ',' && !quoted) {
                    cells.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
            cells.add(cur.toString());
            out.add(cells);
        }
        return out;
    }

    static Document xml(String body) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);   // no XXE
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new InputSource(new StringReader(body)));
    }

    /** FRED series (an API key from the environment), each as a {@code rate-fixing} document. */
    static final class FredSeries implements Feed {
        @Override
        public List<String> urls(Map<String, String> s, LocalDate today) {
            if (s.containsKey("url")) {
                return List.of(s.get("url").split(","));
            }
            List<String> out = new ArrayList<>();
            for (String series : s.getOrDefault("series", "DGS10,DFF").split(",")) {
                out.add("https://api.stlouisfed.org/fred/series/observations?series_id=" + series.trim() + "&api_key=" + s.getOrDefault("api-key", "")
                        + "&file_type=json&sort_order=desc&limit=60");
            }
            return out;
        }

        @Override
        public List<Series> parse(List<String> bodies, Map<String, String> s) throws Exception {
            String[] names = s.getOrDefault("series", "DGS10,DFF").split(",");
            List<Series> out = new ArrayList<>();
            for (int i = 0; i < bodies.size(); i++) {
                NavigableMap<LocalDate, Map<String, Object>> obs = new TreeMap<>();
                for (JsonNode o : JSON.readTree(bodies.get(i)).path("observations")) {
                    String v = o.path("value").asText(".");
                    if (!".".equals(v)) {
                        obs.put(LocalDate.parse(o.path("date").asText()), Map.of("ratePct", Double.parseDouble(v)));
                    }
                }
                out.add(new Series("rate-fixing", "FIX-FRED-" + names[Math.min(i, names.length - 1)].trim(), obs));
            }
            return out;
        }

        @Override
        public Map<String, Object> document(Series s, LocalDate date) {
            return fixingDocument(s, date, "FRED series " + s.id().substring(9), "Federal Reserve Bank of St. Louis (FRED)", "USD", "Daily");
        }

        @Override
        public List<String> kinds() {
            return List.of("rate-fixing");
        }
    }

    /** ECB euro foreign exchange reference rates (the last 90 days), as {@code fx-spot} documents including USD crosses. */
    static final class EcbFx implements Feed {
        private static final List<String> PAIRS = List.of("EURUSD", "EURGBP", "EURJPY", "EURCHF", "GBPUSD", "USDJPY", "USDCHF", "AUDUSD", "USDCAD", "USDCNH");

        @Override
        public List<String> urls(Map<String, String> s, LocalDate today) {
            return List.of(s.getOrDefault("url", "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist-90d.xml"));
        }

        @Override
        public List<Series> parse(List<String> bodies, Map<String, String> s) throws Exception {
            Map<String, NavigableMap<LocalDate, Map<String, Object>>> byPair = new LinkedHashMap<>();
            NodeList days = xml(bodies.get(0)).getElementsByTagNameNS("*", "Cube");
            for (int i = 0; i < days.getLength(); i++) {
                Element day = (Element) days.item(i);
                if (!day.hasAttribute("time")) {
                    continue;
                }
                LocalDate d = LocalDate.parse(day.getAttribute("time"));
                Map<String, Double> perEur = new LinkedHashMap<>();
                perEur.put("EUR", 1.0);
                NodeList rates = day.getElementsByTagNameNS("*", "Cube");
                for (int k = 0; k < rates.getLength(); k++) {
                    Element r = (Element) rates.item(k);
                    perEur.put(r.getAttribute("currency").equals("CNY") ? "CNH" : r.getAttribute("currency"), Double.parseDouble(r.getAttribute("rate")));
                }
                for (String p : PAIRS) {
                    Double base = perEur.get(p.substring(0, 3));
                    Double quote = perEur.get(p.substring(3));
                    if (base != null && quote != null) {
                        byPair.computeIfAbsent(p, x -> new TreeMap<>()).put(d, Map.of("mid", quote / base));
                    }
                }
            }
            List<Series> out = new ArrayList<>();
            byPair.forEach((p, obs) -> out.add(new Series("fx-spot", "FX-" + p + "-ECB", obs)));
            return out;
        }

        @Override
        public Map<String, Object> document(Series s, LocalDate date) {
            NavigableMap<LocalDate, Map<String, Object>> upTo = s.observations().headMap(date, true);
            if (upTo.isEmpty()) {
                return null;
            }
            String pair = s.id().substring(3, 9);
            double mid = round5((Double) upTo.lastEntry().getValue().get("mid"));
            Double prev = upTo.size() > 1 ? (Double) upTo.lowerEntry(upTo.lastKey()).getValue().get("mid") : null;
            List<Map<String, Object>> history = new ArrayList<>();
            upTo.entrySet().stream().skip(Math.max(0, upTo.size() - 30)).forEach(e -> history.add(Map.of("date", e.getKey().toString(),
                    "mid", round5((Double) e.getValue().get("mid")))));
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("pair", s.id());
            doc.put("pairName", pair.substring(0, 3) + "/" + pair.substring(3));
            doc.put("mid", mid);
            doc.put("bid", mid);
            doc.put("ask", mid);
            doc.put("change1d", prev == null ? null : round5(mid / prev - 1));
            doc.put("spotDate", upTo.lastKey().toString());
            doc.put("history", history);
            doc.put("conventions", Map.of("base", pair.substring(0, 3), "quote", pair.substring(3), "fixing", "ECB reference rate, 14:10 CET",
                    "note", "Reference rate for information; not a traded price"));
            return doc;
        }

        static double round5(double v) {
            return Math.round(v * 1e5) / 1e5;
        }

        @Override
        public List<String> kinds() {
            return List.of("fx-spot");
        }
    }

    /** US Treasury daily par yield curve rates, as an {@code ir-curve} document (CRV-USD-UST). */
    static final class UsTreasury implements Feed {
        private static final String[][] TENORS = {{"BC_1MONTH", "1M"}, {"BC_2MONTH", "2M"}, {"BC_3MONTH", "3M"}, {"BC_6MONTH", "6M"},
            {"BC_1YEAR", "1Y"}, {"BC_2YEAR", "2Y"}, {"BC_3YEAR", "3Y"}, {"BC_5YEAR", "5Y"}, {"BC_7YEAR", "7Y"}, {"BC_10YEAR", "10Y"},
            {"BC_20YEAR", "20Y"}, {"BC_30YEAR", "30Y"}};

        @Override
        public List<String> urls(Map<String, String> s, LocalDate today) {
            if (s.containsKey("url")) {
                return List.of(s.get("url").split(","));
            }
            DateTimeFormatter m = DateTimeFormatter.ofPattern("yyyyMM");
            String base = "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/pages/xml?data=daily_treasury_yield_curve&field_tdr_date_value_month=";
            return List.of(base + today.minusMonths(1).format(m), base + today.format(m));
        }

        @Override
        public List<Series> parse(List<String> bodies, Map<String, String> s) throws Exception {
            NavigableMap<LocalDate, Map<String, Object>> obs = new TreeMap<>();
            for (String body : bodies) {
                NodeList props = xml(body).getElementsByTagNameNS("*", "properties");
                for (int i = 0; i < props.getLength(); i++) {
                    Element p = (Element) props.item(i);
                    String date = text(p, "NEW_DATE");
                    if (date == null) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String[] t : TENORS) {
                        String v = text(p, t[0]);
                        if (v != null && !v.isBlank()) {
                            row.put(t[1], Double.parseDouble(v));
                        }
                    }
                    obs.put(LocalDate.parse(date.substring(0, 10)), row);
                }
            }
            return List.of(new Series("ir-curve", "CRV-USD-UST", obs));
        }

        private static String text(Element e, String local) {
            NodeList n = e.getElementsByTagNameNS("*", local);
            return n.getLength() == 0 ? null : n.item(0).getTextContent();
        }

        @Override
        public Map<String, Object> document(Series s, LocalDate date) {
            Map.Entry<LocalDate, Map<String, Object>> e = s.observations().floorEntry(date);
            if (e == null) {
                return null;
            }
            List<Map<String, Object>> points = new ArrayList<>();
            Map<String, Double> y = new LinkedHashMap<>();
            for (String[] t : TENORS) {
                Object v = e.getValue().get(t[1]);
                if (v instanceof Double par) {
                    double years = t[1].endsWith("M") ? Integer.parseInt(t[1].replace("M", "")) / 12.0 : Integer.parseInt(t[1].replace("Y", ""));
                    double df = Math.pow(1 + par / 200, -2 * years);                 // semi-annual par yield, bond basis
                    y.put(t[1], par);
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("tenor", t[1]);
                    p.put("maturity", e.getKey().plusDays(Math.round(years * 365)).toString());
                    p.put("instrument", years < 1 ? "Bill (par yield)" : "Note/bond (par yield)");
                    p.put("quote", par / 100);
                    p.put("zeroRate", par);
                    p.put("df", Math.round(df * 1e6) / 1e6);
                    points.add(p);
                }
            }
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("curveId", s.id());
            doc.put("currency", "USD");
            doc.put("curveType", "Government (US Treasury par yields)");
            doc.put("index", "UST");
            doc.put("tenY", y.containsKey("10Y") ? y.get("10Y") / 100 : null);
            doc.put("slope2s10s", y.containsKey("10Y") && y.containsKey("2Y") ? Math.round((y.get("10Y") - y.get("2Y")) * 1000) / 10.0 : null);
            doc.put("asOf", e.getKey().toString());
            doc.put("points", points);
            doc.put("forwards", List.of());
            doc.put("source", "U.S. Department of the Treasury, Daily Treasury Par Yield Curve Rates");
            return doc;
        }

        @Override
        public List<String> kinds() {
            return List.of("ir-curve");
        }
    }
}
