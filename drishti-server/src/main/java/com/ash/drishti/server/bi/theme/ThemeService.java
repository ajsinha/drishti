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
package com.ash.drishti.server.bi.theme;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.BiAccess;
import com.ash.drishti.server.bi.BiFolder;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.report.Report;
import com.ash.drishti.server.bi.report.ReportParser;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Theme items for the people who use them (docs/guides/REPORTS.md section 5.2): the list the designer offers, one theme, a check of theme YAML, and the theme a
 * report draws with, worked out for the viewer. A theme is styling, not data: a report's readers see the colours of the theme it names when it is published
 * (or, for its authors, a draft of their own), whatever workspace it is in. A report that names a theme which does not exist draws with the default palette and
 * says so in its problems.
 */
public final class ThemeService {

    private final BiService bi;

    public ThemeService(BiService bi) {
        this.bi = bi;
    }

    /** The theme items a caller can name: every published one (stored and shipped), then their own drafts. */
    List<BiStore.Item> items(Principal p) {
        List<BiStore.Item> all = new ArrayList<>(bi.publishedOfKind("theme"));
        if (p != null && bi.mayAuthor(p)) {
            bi.draftsOfKind(p, "theme").stream().filter(d -> all.stream().noneMatch(x -> x.id().equals(d.id()))).forEach(all::add);
        }
        return all;
    }

    /** The theme {@code ref} names (its catalogue id, a shipped file's name, its declared name or its title), parsed; null when there is none. */
    public Theme find(Principal p, String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        Theme byTitle = null;
        for (BiStore.Item i : items(p)) {
            Theme t = ThemeParser.parse(text(p, i)).theme();
            if (t == null) {
                continue;
            }
            if (i.id().equals(ref) || i.id().equals("file:theme:" + ref) || ref.equals(t.name())) {
                return t;
            }
            if (byTitle == null && i.title().equalsIgnoreCase(ref)) {
                byTitle = t;
            }
        }
        return byTitle;
    }

    private String text(Principal p, BiStore.Item i) {
        String published = bi.publishedBody(i);
        return published != null && !published.isBlank() ? published : i.body();
    }

    /** The themes for the designer's picker: id, name, title, palette and background of each. */
    public List<Map<String, Object>> list(Principal p) {
        bi.requirePower(p, BiAccess.BI);
        List<Map<String, Object>> out = new ArrayList<>();
        for (BiStore.Item i : items(p)) {
            Theme t = ThemeParser.parse(text(p, i)).theme();
            if (t == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.id());
            m.put("name", t.name());
            m.put("title", i.title());
            m.put("palette", t.palette());
            m.put("background", t.background());
            m.put("shipped", BiFolder.isShipped(i.id()));
            m.put("status", i.status());
            out.add(m);
        }
        return out;
    }

    /** One theme as the viewer and the designer read it. */
    public Map<String, Object> get(Principal p, String ref) {
        bi.requirePower(p, BiAccess.BI);
        Theme t = find(p, ref);
        if (t == null) {
            throw new DrishtiException(ErrorCode.BI_NOT_FOUND, "no theme '" + ref + "' (a catalogue id, a file name such as corporate, or the theme's name)");
        }
        return t.toMap();
    }

    /** Checks theme YAML without storing it (authors): the problems with their codes, and the theme when it reads. */
    public Map<String, Object> validate(Principal p, String yaml) {
        bi.requirePower(p, BiAccess.AUTHOR);
        ThemeParser.Result r = ThemeParser.parse(yaml);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("valid", r.ok());
        m.put("problems", r.problems().stream().map(ReportParser.Problem::toMap).toList());
        if (r.theme() != null) {
            m.put("theme", r.theme().toMap());
        }
        return m;
    }

    /**
     * The theme a report draws with: its {@code use} item's colours, type and visual defaults, under the report's own {@code palette} and {@code background}
     * (which win); a report that names only a palette gets just that. {@code missing} names a theme that could not be found.
     */
    public Map<String, Object> resolve(Principal p, Map<String, String> reportTheme) {
        Map<String, Object> m = new LinkedHashMap<>();
        String use = reportTheme.get("use");
        Theme t = use == null ? null : find(p, use);
        if (t != null) {
            m.put("name", t.name());
            m.put("title", t.title());
            m.put("palette", t.palette());
            putIf(m, "background", t.background());
            putIf(m, "foreground", t.foreground());
            if (!t.font().isEmpty()) {
                m.put("font", t.font());
            }
            putIf(m, "good", t.good());
            putIf(m, "bad", t.bad());
            if (!t.visuals().isEmpty()) {
                m.put("visuals", t.visuals());
            }
        } else if (use != null) {
            m.put("missing", use);
        }
        if (reportTheme.get("palette") != null) {
            m.put("palette", reportTheme.get("palette"));                       // a palette name (default, colour-blind, mono) the console knows
        } else if (t == null) {
            m.put("palette", "default");
        }
        if (reportTheme.get("background") != null) {
            m.put("background", reportTheme.get("background"));
        }
        return m;
    }

    /** The problem of a report whose theme cannot be found (a warning: it draws with the default palette). */
    public List<ReportParser.Problem> problems(Principal p, Report r) {
        String use = r.theme().get("use");
        if (use == null || find(p, use) != null) {
            return List.of();
        }
        return List.of(new ReportParser.Problem("warning", ThemeParser.INVALID, "theme", "no theme '" + use + "' that you can use (a shipped theme such as corporate, or a published theme item); "
                + "the report draws with the default colours"));
    }

    private static void putIf(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }
}
