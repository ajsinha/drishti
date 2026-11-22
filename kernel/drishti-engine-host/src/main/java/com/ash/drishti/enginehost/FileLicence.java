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
package com.ash.drishti.enginehost;

import com.ash.engine.host.Licence;
import com.ash.engine.text.StrictYaml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The installation's licence as the engine host reads it (owner direction 2026-11-22; engine specification 17.8): a file that lists the
 * product ids it grants and, optionally, limits per product. An engine whose descriptor's {@code product} is not granted is blocked at start
 * with {@code ENG-1014}, one sentence naming the product; nothing else is affected.
 *
 * <pre>
 * licence:
 *   customer: Example Bank
 *   products: [ drishti-risk, drishti-engine-samples ]
 *   limits: { drishti-risk: { nodes: 3 } }      # read and shown; enforced with module licensing
 * </pre>
 *
 * No file: the development licence, every product granted (as the module licence without its file). The format is signed later, with module
 * licensing, so modules and engines share one licence file; until then the file is unsigned and Admin says so.
 *
 * @param products the product ids granted; {@code *} grants every product
 * @param limits   per product, as written
 * @param source   where it was read, in words
 * @param problem  why the file could not be read (then no product is granted), or null
 */
public record FileLicence(String customer, Set<String> products, Map<String, Object> limits, String source, String problem) implements Licence {

    public FileLicence {
        products = Set.copyOf(products);
        limits = Map.copyOf(limits);
    }

    @Override
    public boolean grants(String product) {
        return products.contains("*") || products.contains(product);
    }

    /** True for the development licence (no file). */
    public boolean development() {
        return products.contains("*") && source.startsWith("no licence file");
    }

    /** The licence at {@code file}: absent, the development licence; unreadable, a licence that grants nothing and says why. */
    @SuppressWarnings("unchecked")
    public static FileLicence read(Path file) {
        if (!Files.isRegularFile(file)) {
            return new FileLicence("development", Set.of("*"), Map.of(), "no licence file at " + file + ": every product (development)", null);
        }
        try {
            Map<String, Object> doc = StrictYaml.readMap(Files.readString(file, StandardCharsets.UTF_8));
            Map<String, Object> m = doc.get("licence") instanceof Map<?, ?> inner ? (Map<String, Object>) inner : doc;
            if (!(m.get("products") instanceof List<?> list)) {
                return refused(file, "it lists no products (licence.products: [ ... ])");
            }
            Set<String> products = new LinkedHashSet<>();
            list.forEach(p -> products.add(String.valueOf(p).strip()));
            Map<String, Object> limits = m.get("limits") instanceof Map<?, ?> l ? new TreeMap<>((Map<String, Object>) l) : Map.of();
            return new FileLicence(m.get("customer") == null ? "" : String.valueOf(m.get("customer")), products, limits, file + " (unsigned)", null);
        } catch (IOException | IllegalArgumentException e) {
            return refused(file, e.getMessage());
        }
    }

    private static FileLicence refused(Path file, String why) {
        return new FileLicence("", Set.of(), Map.of(), file.toString(), file + " cannot be read: " + why + "; no engine product is granted until it is fixed");
    }
}
