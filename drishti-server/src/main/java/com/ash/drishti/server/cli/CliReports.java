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
package com.ash.drishti.server.cli;

import com.ash.drishti.engine.view.ViewModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** What the CLI writes besides its console lines: JUnit XML for CI and a self-contained HTML snapshot of a rendered view. */
final class CliReports {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private CliReports() {}

    /** One check: {@code failure} is null when it passed; {@code skipped} marks one that could not run (no samples). */
    record Case(String suite, String name, String failure, boolean skipped) {
        static Case pass(String suite, String name) {
            return new Case(suite, name, null, false);
        }

        static Case fail(String suite, String name, String why) {
            return new Case(suite, name, why, false);
        }

        static Case skip(String suite, String name, String why) {
            return new Case(suite, name, why, true);
        }
    }

    static void junit(Path file, List<Case> cases) throws IOException {
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites>\n");
        cases.stream().map(Case::suite).distinct().forEach(suite -> {
            List<Case> mine = cases.stream().filter(c -> c.suite().equals(suite)).toList();
            long failures = mine.stream().filter(c -> c.failure() != null && !c.skipped()).count();
            long skipped = mine.stream().filter(Case::skipped).count();
            x.append("  <testsuite name=\"").append(esc(suite)).append("\" tests=\"").append(mine.size()).append("\" failures=\"")
                    .append(failures).append("\" skipped=\"").append(skipped).append("\">\n");
            for (Case c : mine) {
                x.append("    <testcase classname=\"").append(esc(suite)).append("\" name=\"").append(esc(c.name())).append('"');
                if (c.failure() == null) {
                    x.append("/>\n");
                } else if (c.skipped()) {
                    x.append("><skipped message=\"").append(esc(c.failure())).append("\"/></testcase>\n");
                } else {
                    x.append("><failure message=\"").append(esc(firstLine(c.failure()))).append("\">").append(esc(c.failure())).append("</failure></testcase>\n");
                }
            }
            x.append("  </testsuite>\n");
        });
        x.append("</testsuites>\n");
        if (file.toAbsolutePath().getParent() != null) {
            Files.createDirectories(file.toAbsolutePath().getParent());
        }
        Files.writeString(file, x.toString(), StandardCharsets.UTF_8);
    }

    /** A static HTML page of the view: header, key figures, and each panel with its status and data (JSON). */
    static void snapshot(Path file, String title, ViewModel vm) throws IOException {
        StringBuilder h = new StringBuilder("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>").append(esc(title))
                .append("</title><style>body{font:14px system-ui,sans-serif;margin:1.5rem;color:#1d2433}h1{font-size:1.3rem}"
                        + ".strip span{display:inline-block;margin:0 1rem .5rem 0}section{border:1px solid #cdd3dd;border-radius:6px;margin:.8rem 0;padding:.6rem .9rem}"
                        + "h2{font-size:1rem;margin:0 0 .3rem}.error{color:#b00020}.empty{color:#6b7280}pre{overflow:auto;max-height:18rem;background:#f4f6fa;padding:.5rem}"
                        + "</style></head><body><h1>").append(esc(title)).append("</h1>");
        if (vm.strip() != null && !vm.strip().isEmpty()) {
            h.append("<p class=\"strip\">");
            vm.strip().forEach(c -> h.append("<span><b>").append(esc(String.valueOf(c.label()))).append("</b> ").append(esc(String.valueOf(c.text()))).append("</span>"));
            h.append("</p>");
        }
        for (ViewModel.PanelView p : vm.panels()) {
            String state = p.denied() != null ? "no access" : p.error() != null ? "error" : p.empty() ? "empty" : "ok";
            h.append("<section data-panel=\"").append(esc(p.id())).append("\" data-state=\"").append(state).append("\"><h2>")
                    .append(esc(p.title() == null ? p.id() : p.title())).append(" <small>(").append(esc(p.kind())).append(", ").append(state).append(")</small></h2>");
            if (p.error() != null) {
                h.append("<p class=\"error\">").append(esc(p.error())).append("</p>");
            } else if (p.empty()) {
                h.append("<p class=\"empty\">No data.</p>");
            } else if (p.data() != null) {
                h.append("<pre>").append(esc(json(p.data()))).append("</pre>");
            }
            h.append("</section>");
        }
        h.append("</body></html>\n");
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, h.toString(), StandardCharsets.UTF_8);
    }

    static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (IOException e) {
            return String.valueOf(o);
        }
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
