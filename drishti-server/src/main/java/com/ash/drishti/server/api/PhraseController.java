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
package com.ash.drishti.server.api;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.NodeType;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.engine.search.PhraseParser;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.inference.Semantics;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A phrase in plain words turned into a structured search ({@link PhraseParser}), with how each part was read and
 * which words were not understood. It only writes the query; running it is a search like any other. The vocabulary is
 * the kinds the caller may open, and each kind's fields as its documents have them (types and the values that repeat),
 * learned from a sample of documents and kept a few minutes.
 */
@RestController
@RequestMapping("/api/v1/phrase")
public class PhraseController {

    private static final int SAMPLE = 50;
    private static final int MAX_VALUES = 40;
    private static final Duration KEEP = Duration.ofMinutes(5);
    private final Mnemonics mnemonics;
    private final SourceRouter router;
    private final Entitlements entitlements;
    private final Map<String, Learned> learned = new ConcurrentHashMap<>();

    private record Learned(List<PhraseParser.Field> fields, long at) {}

    public PhraseController(Mnemonics mnemonics, SourceRouter router, Entitlements entitlements) {
        this.mnemonics = mnemonics;
        this.router = router;
        this.entitlements = entitlements;
    }

    @GetMapping
    public PhraseParser.Parsed parse(@RequestParam String text, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        List<PhraseParser.KindWord> kinds = new ArrayList<>();
        mnemonics.all().forEach((code, m) -> {
            if (entitlements.mayOpen(p, m.kind())) {
                kinds.add(new PhraseParser.KindWord(m.kind(), code, m.label() == null ? m.kind() : m.label()));
            }
        });
        Map<String, PhraseParser.KindWord> words = PhraseParser.kindWords(kinds);
        return new PhraseParser(new PhraseParser.Vocabulary() {
            @Override
            public Map<String, PhraseParser.KindWord> kinds() {
                return words;
            }

            @Override
            public List<PhraseParser.Field> fields(String kind) {
                return fieldsOf(kind);
            }
        }).parse(text.length() > 300 ? text.substring(0, 300) : text);
    }

    /** The kind's fields, from a sample of its documents (cached {@link #KEEP}). */
    List<PhraseParser.Field> fieldsOf(String kind) {
        Learned l = learned.get(kind);
        if (l != null && System.currentTimeMillis() - l.at() < KEEP.toMillis()) {
            return l.fields();
        }
        // a sample spread across the whole book, not its first ids (which may all be one booking system or desk)
        List<EntityHit> all = router.search(kind, "", SAMPLE * 40, Duration.ofSeconds(3), AsOf.LATEST);
        List<EntityHit> hits = new ArrayList<>();
        for (int i = 0; i < all.size() && hits.size() < SAMPLE; i += Math.max(1, all.size() / SAMPLE)) {
            hits.add(all.get(i));
        }
        Map<com.ash.drishti.api.EntityRef, EntityDocument> docs = router.fetchAll(hits.stream().map(EntityHit::ref).toList(), Duration.ofSeconds(5));
        Map<String, Stats> stats = new LinkedHashMap<>();
        docs.values().forEach(d -> walk("", d.data(), stats, 0));
        List<PhraseParser.Field> fields = new ArrayList<>();
        stats.forEach((path, s) -> {
            PhraseParser.Type type = s.numbers > 0 && s.texts == 0 ? PhraseParser.Type.NUMBER
                    : s.dates > 0 && s.dates == s.texts ? PhraseParser.Type.DATE
                    : s.booleans > 0 && s.texts == 0 ? PhraseParser.Type.BOOLEAN : PhraseParser.Type.TEXT;
            // values worth naming: few distinct ones that repeat (statuses, currencies, books), not free text
            Map<String, String> values = type == PhraseParser.Type.TEXT && s.values.size() <= MAX_VALUES ? s.values : Map.of();
            String[] parts = path.split("\\.");
            String label = (parts.length > 1 ? Semantics.humanize(parts[parts.length - 2]) + " " : "") + Semantics.humanize(parts[parts.length - 1]);
            fields.add(new PhraseParser.Field(path, label.toLowerCase(Locale.ROOT), type, values));
        });
        learned.put(kind, new Learned(List.copyOf(fields), System.currentTimeMillis()));
        return fields;
    }

    private static final class Stats {
        int numbers;
        int texts;
        int dates;
        int booleans;
        final Map<String, String> values = new LinkedHashMap<>();
    }

    /** Scalars at the top and one level down ({@code counterparty.name}). */
    private static void walk(String prefix, DataNode node, Map<String, Stats> stats, int depth) {
        if (!(node instanceof DataNode.Obj obj)) {
            return;
        }
        obj.fields().forEach((name, v) -> {
            String path = prefix.isEmpty() ? name : prefix + "." + name;
            if (v.type() == NodeType.OBJECT && depth == 0) {
                walk(path, v, stats, 1);
                return;
            }
            Stats s = stats.computeIfAbsent(path, x -> new Stats());
            switch (v.type()) {
                case NUMBER -> s.numbers++;
                case BOOLEAN -> s.booleans++;
                case STRING -> {
                    String t = v.asText();
                    s.texts++;
                    if (t.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                        s.dates++;
                    } else if (t.length() <= 40 && s.values.size() <= MAX_VALUES) {
                        s.values.putIfAbsent(t.toLowerCase(Locale.ROOT), t);
                    }
                }
                default -> { }
            }
        });
    }
}
