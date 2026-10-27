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
package com.ash.drishti.server.var;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The retention of the currencies built ahead (VAR_GUIDE.md section 15.11, {@code drishti.var.currency-retention-dates}): the listed
 * currencies' hot files of dates outside the newest N are deleted; the USD books, the kept dates and the currencies not listed (built on
 * first ask) stay; the settings default to USD, EUR, CAD, JPY, GBP and 5 dates, USD always first.
 */
class VarCurrencyRetentionTest {

    @TempDir
    Path dir;

    private Path file(String folder) throws Exception {
        Path f = Files.createDirectories(dir.resolve(folder)).resolve("inputs-0123456789abcdef.vhot");
        Files.writeString(f, "x");
        return f;
    }

    @Test
    void theListedCurrenciesOfOlderDatesAreDeleted() throws Exception {
        Path oldEur = file("2026-09-28-EUR");
        Path oldCad = file("2026-09-28-CAD");
        Path oldUsd = file("2026-09-28");
        Path oldChf = file("2026-09-28-CHF");
        Path keepEur = file("2026-09-30-EUR");
        Path keep29 = file("2026-09-29-CAD");
        VarTier tier = new VarTier(new VarProperties.Tier("mapped", dir.toString(), 60, true, 0));
        int gone = tier.pruneCurrencies(Set.of(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 29)), List.of("USD", "EUR", "CAD"));
        assertThat(gone).isEqualTo(2);
        assertThat(oldEur).doesNotExist();
        assertThat(oldCad).doesNotExist();
        assertThat(dir.resolve("2026-09-28-EUR")).doesNotExist();           // the empty folder goes too
        assertThat(oldUsd).exists();                                       // the USD book follows disk-dates, not this
        assertThat(oldChf).exists();                                       // not listed: built on first ask, kept by disk-dates
        assertThat(keepEur).exists();
        assertThat(keep29).exists();
        assertThat(tier.currencyFiles().get("files")).isEqualTo(3);
    }

    @Test
    void theSettingsDefaultToFiveCurrenciesAndFiveDates() {
        assertThat(VarProperties.currencyList(List.of("eur", "USD", " gbp", "EUR", "", "EURO"))).containsExactly("USD", "EUR", "GBP");
        VarProperties p = new VarProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        assertThat(p.currencies()).containsExactly("USD", "EUR", "CAD", "JPY", "GBP");
        assertThat(p.currencyRetentionDates()).isEqualTo(5);
    }
}
