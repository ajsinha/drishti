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
package com.ash.drishti.rachana.format;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The world's regions: every country (ISO 3166 alpha-3) in one sub-region, every sub-region in one continent, from the bundled
 * {@code world-regions.yaml}. It is the one grouping: the console's map reads a table generated from the same file, so a figure by
 * region is computed here, on the server, by the grouping the map draws. Codes: {@code CT-<ABC>} a continent, {@code SR-<NAME>} a
 * sub-region (a region's code never clashes with a country's).
 */
public final class WorldRegions {

    private final Map<String, String> continents = new LinkedHashMap<>();
    private final Map<String, String> subNames = new LinkedHashMap<>();
    private final Map<String, String> subContinent = new LinkedHashMap<>();
    private final Map<String, List<String>> subCountries = new LinkedHashMap<>();
    private final Map<String, String> countrySub = new LinkedHashMap<>();

    private WorldRegions(JsonNode root) {
        Iterator<Map.Entry<String, JsonNode>> cs = root.path("continents").fields();
        while (cs.hasNext()) {
            Map.Entry<String, JsonNode> e = cs.next();
            continents.put(e.getKey(), e.getValue().asText());
        }
        Iterator<Map.Entry<String, JsonNode>> ss = root.path("subregions").fields();
        while (ss.hasNext()) {
            Map.Entry<String, JsonNode> e = ss.next();
            JsonNode s = e.getValue();
            subNames.put(e.getKey(), s.path("name").asText());
            subContinent.put(e.getKey(), s.path("continent").asText());
            List<String> list = new ArrayList<>();
            for (JsonNode c : s.path("countries")) {
                list.add(c.asText());
                countrySub.put(c.asText(), e.getKey());
            }
            subCountries.put(e.getKey(), List.copyOf(list));
        }
    }

    /** The bundled grouping. */
    public static WorldRegions load() {
        try (InputStream in = WorldRegions.class.getClassLoader().getResourceAsStream("world-regions.yaml")) {
            return new WorldRegions(new ObjectMapper(new YAMLFactory()).readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The sub-region code of a country ({@code SR-WESTERN-EUROPE} for FRA), empty when the table does not list it. */
    public Optional<String> subRegionOf(String iso3) {
        return Optional.ofNullable(countrySub.get(iso3 == null ? "" : iso3.toUpperCase()));
    }

    /** The continent code of a country ({@code CT-EUR} for FRA), empty when the table does not list it. */
    public Optional<String> continentOf(String iso3) {
        return subRegionOf(iso3).map(subContinent::get);
    }

    /** The region of a country at a level: {@code "subregion"} or {@code "continent"} (the code), empty for another level or an unlisted country. */
    public Optional<String> regionOf(String iso3, String level) {
        return switch (level == null ? "" : level.toLowerCase()) {
            case "subregion", "sub-region" -> subRegionOf(iso3);
            case "continent" -> continentOf(iso3);
            default -> Optional.empty();
        };
    }

    /** The name of a continent or sub-region by its code, empty for another code. */
    public Optional<String> name(String code) {
        String k = code == null ? "" : code.toUpperCase();
        return Optional.ofNullable(continents.containsKey(k) ? continents.get(k) : subNames.get(k));
    }

    /** The countries of a sub-region, or of every sub-region of a continent, in the table's order; empty for another code. */
    public List<String> countriesOf(String code) {
        String k = code == null ? "" : code.toUpperCase();
        if (subCountries.containsKey(k)) {
            return subCountries.get(k);
        }
        List<String> out = new ArrayList<>();
        subContinent.forEach((sub, ct) -> {
            if (ct.equals(k)) {
                out.addAll(subCountries.get(sub));
            }
        });
        return List.copyOf(out);
    }

    /** Every continent code, in the table's order. */
    public List<String> continentCodes() {
        return List.copyOf(continents.keySet());
    }

    /** Every sub-region code, in the table's order. */
    public List<String> subRegionCodes() {
        return List.copyOf(subNames.keySet());
    }
}
