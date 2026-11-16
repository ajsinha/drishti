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

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The logic between the raw feed and what a view shows, as five small steps in a fixed order, each reporting how many
 * headlines it kept so a reader can see what happened to the rest:
 * <ol>
 *   <li><b>relevance</b>: the title must name the entity or one of its aliases (setting {@code relevance}, default on);</li>
 *   <li><b>sources</b>: a publisher on {@code deny-sources} goes; when {@code allow-sources} is set only those stay;</li>
 *   <li><b>order</b>: newest first (headlines without a date last);</li>
 *   <li><b>duplicates</b>: the same story from the same address, or the same normalised headline from the same publisher,
 *       is kept once (the newest);</li>
 *   <li><b>limit</b>: at most {@code max-items}.</li>
 * </ol>
 */
final class NewsPipeline {

    /** What one step did. */
    record Stage(String stage, int kept, int dropped) {}

    /** The headlines that remain and what each step did. */
    record Outcome(List<NewsItem> items, List<Stage> stages, int fetched) {}

    private final NewsSettings settings;

    NewsPipeline(NewsSettings settings) {
        this.settings = settings;
    }

    Outcome run(List<NewsItem> raw, String name, List<String> aliases, boolean relevanceInTitle) {
        List<Stage> stages = new ArrayList<>();
        List<NewsItem> items = new ArrayList<>(raw);
        if (relevanceInTitle) {
            List<String> names = new ArrayList<>(aliases);
            names.add(name);
            items = step(stages, "relevance", items, i -> names.stream().anyMatch(n -> mentions(i.title(), n)));
        }
        if (!settings.allowSources().isEmpty() || !settings.denySources().isEmpty()) {
            items = step(stages, "sources", items, i -> {
                String s = i.source().toLowerCase(Locale.ROOT);
                return !settings.denySources().contains(s) && (settings.allowSources().isEmpty() || settings.allowSources().contains(s));
            });
        }
        items.sort(Comparator.comparing(NewsItem::published, Comparator.nullsLast(Comparator.<Instant>naturalOrder().reversed())));
        Set<String> seen = new HashSet<>();
        items = step(stages, "duplicates", items, i -> {
            boolean fresh = seen.add("L|" + i.link());
            return seen.add("T|" + i.source().toLowerCase(Locale.ROOT) + "|" + fold(i.title())) && fresh;
        });
        int before = items.size();
        if (before > settings.maxItems()) {
            items = new ArrayList<>(items.subList(0, settings.maxItems()));
        }
        stages.add(new Stage("limit", items.size(), before - items.size()));
        return new Outcome(List.copyOf(items), List.copyOf(stages), raw.size());
    }

    private static List<NewsItem> step(List<Stage> stages, String name, List<NewsItem> in, java.util.function.Predicate<NewsItem> keep) {
        List<NewsItem> out = new ArrayList<>();
        for (NewsItem i : in) {
            if (keep.test(i)) {
                out.add(i);
            }
        }
        stages.add(new Stage(name, out.size(), in.size() - out.size()));
        return out;
    }

    /** Whether the title contains the name as whole words, ignoring case and accents. */
    static boolean mentions(String title, String name) {
        String t = " " + fold(title) + " ";
        String n = fold(name);
        return !n.isEmpty() && t.contains(" " + n + " ");
    }

    /** Lower case, accents removed, anything but letters and digits folded to single spaces. */
    static String fold(String s) {
        String d = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return d.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
