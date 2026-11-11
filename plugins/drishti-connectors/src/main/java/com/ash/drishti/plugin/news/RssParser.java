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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads an RSS 2.0 body into {@link NewsItem}s. The parser never resolves a DOCTYPE, an external entity or an
 * include, so a hostile feed cannot read a file or call out (XXE); the size is limited before it gets here.
 */
final class RssParser {

    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    private RssParser() {}

    static List<NewsItem> parse(byte[] body) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            NodeList items = b.parse(new ByteArrayInputStream(body)).getElementsByTagName("item");
            List<NewsItem> out = new ArrayList<>();
            for (int i = 0; i < items.getLength(); i++) {
                NewsItem n = item((Element) items.item(i));
                if (n != null) {
                    out.add(n);
                }
            }
            return out;
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new IOException("the response is not a readable RSS feed: " + e.getMessage(), e);
        }
    }

    private static NewsItem item(Element e) {
        String title = clean(text(e, "title"));
        String link = text(e, "link").strip();
        if (title.isEmpty() || !(link.startsWith("https://") || link.startsWith("http://"))) {
            return null;                                           // no headline or no safe address: not an item
        }
        String source = clean(text(e, "source"));
        String suffix = " - " + source;
        if (!source.isEmpty() && title.endsWith(suffix)) {
            title = title.substring(0, title.length() - suffix.length()).strip();     // Google appends the publisher to the title
        }
        String snippet = clean(text(e, "description"));
        if (snippet.startsWith(title)) {                           // Google's description repeats the title, then the publisher
            snippet = snippet.substring(title.length()).strip();
        }
        if (snippet.equals(source) || snippet.equals("- " + source)) {
            snippet = "";
        }
        return new NewsItem(title, source, link, date(text(e, "pubDate")), snippet.length() > 300 ? snippet.substring(0, 300) + "…" : snippet);
    }

    private static Instant date(String s) {
        try {
            return s.isBlank() ? null : ZonedDateTime.parse(s.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String text(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && c.getTagName().equals(tag)) {
                return c.getTextContent() == null ? "" : c.getTextContent();
            }
        }
        return "";
    }

    /** Plain text: tags removed (twice, because descriptions arrive as escaped HTML), entities decoded, white space folded. */
    static String clean(String s) {
        String t = TAGS.matcher(s).replaceAll(" ");
        t = t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        t = TAGS.matcher(t).replaceAll(" ");
        return SPACE.matcher(t).replaceAll(" ").strip();
    }
}
