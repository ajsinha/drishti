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
package com.ash.drishti.server.bi.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The Region economics sample: dataset and report are clean, say they are illustrative, and every region code in the data is a shape the map has. */
class RegionEconomicsSampleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path SAMPLES = Path.of("../config/packs/world-economy/samples/region-economy");
    private static final Path SHAPES = Path.of("../drishti-console/web/static/vendor/echarts/maps/regions");

    private static Dataset dataset() throws IOException {
        DatasetParser.Result r = DatasetParser.parse(Files.readString(ReportParserTest.DATASETS.resolve("region-economics.dataset.yaml")));
        assertThat(r.problems().stream().filter(DatasetParser.Problem::error).toList()).isEmpty();
        return r.dataset();
    }

    private static List<JsonNode> rows() throws IOException {
        try (Stream<Path> s = Files.list(SAMPLES)) {
            return s.sorted().map(p -> {
                try {
                    return JSON.readTree(p.toFile());
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).toList();
        }
    }

    @Test
    void theDatasetIsOneTableWithThreeMeasuresACountryRegionHierarchyAndSaysItIsIllustrative() throws IOException {
        Dataset d = dataset();
        assertThat(d.sample()).isTrue();
        assertThat(d.tables()).containsOnlyKeys("regions");
        assertThat(d.measures().keySet()).containsExactly("Population", "GDP (USD bn)", "GDP per head (USD)");
        assertThat(d.hierarchies().get("Country and region")).containsExactly("regions.countryName", "regions.name");
        assertThat(d.description()).contains("Illustrative, rounded from public figures, not for reporting");
        assertThat(d.tables().get("regions").columns().keySet()).contains("iso3", "regionCode", "name", "population", "gdpUsdBn", "year", "source");
    }

    @Test
    void theReportParsesItsWellsResolveAndAVisualSaysTheFiguresAreIllustrative() throws IOException {
        ReportParser.Result r = ReportParser.parse(ReportParserTest.report("region-economics"));
        assertThat(r.problems().stream().filter(ReportParser.Problem::error).toList()).isEmpty();
        assertThat(r.report().sample()).isTrue();
        assertThat(r.report().dataset()).isEqualTo("region-economics");
        assertThat(ReportValidator.validate(r.report(), dataset())).isEmpty();
        List<Report.Visual> visuals = r.report().page("regions").visuals();
        assertThat(visuals).extracting(Report.Visual::type).containsExactly("text", "slicer", "map", "bar", "table");
        assertThat(visuals.get(0).text()).contains("Illustrative, rounded from public figures, not for reporting");
        assertThat(r.report().description()).contains("Illustrative, rounded from public figures, not for reporting");
    }

    @Test
    void everyRegionCodeInTheDataIsAShapeInTheVendoredFileOfItsCountry() throws IOException {
        List<JsonNode> rows = rows();
        assertThat(rows).hasSize(59);
        Set<String> seen = new HashSet<>();
        for (JsonNode row : rows) {
            String iso3 = row.path("iso3").asText();
            String code = row.path("regionCode").asText();
            assertThat(seen.add(code)).as("duplicate " + code).isTrue();
            Set<String> shapes = new HashSet<>();
            for (JsonNode f : JSON.readTree(SHAPES.resolve(iso3 + ".json").toFile()).path("features")) {
                shapes.add(f.path("properties").path("code").asText());
            }
            assertThat(shapes).as(code + " in " + iso3 + ".json").contains(code);
            assertThat(row.path("source").asText()).contains("Illustrative");
            assertThat(row.path("population").asLong() % 10_000).as(code + " rounded to 10,000").isZero();
        }
        assertThat(seen).contains("AU-NSW", "AU-NT", "US-CA", "US-DC");
    }
}
