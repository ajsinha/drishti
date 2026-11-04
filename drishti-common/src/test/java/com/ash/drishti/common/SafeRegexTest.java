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
package com.ash.drishti.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The search's regular-expression engine: right answers on the safe subset, quick answers on catastrophic patterns, refusals with a code. */
class SafeRegexTest {

    private static boolean find(String re, String text) {
        return SafeRegex.compile(re, false).find(text);
    }

    @Test
    void matchesTheSafeSubset() {
        assertThat(find("abc", "xxabcxx")).isTrue();
        assertThat(find("^abc$", "abc")).isTrue();
        assertThat(find("^abc$", "xabc")).isFalse();
        assertThat(find("MX-\\d+", "id MX-20041")).isTrue();
        assertThat(find("^MX-\\d{3,5}$", "MX-123456")).isFalse();
        assertThat(find("^MX-\\d{3,5}$", "MX-12345")).isTrue();
        assertThat(find("a.c", "abc")).isTrue();
        assertThat(find("a.c", "a\nc")).isFalse();
        assertThat(find("^(USD|EUR|GBP)$", "EUR")).isTrue();
        assertThat(find("^(?:USD|EUR)-[A-Z]{2}$", "EUR-ab")).isFalse();
        assertThat(find("[^0-9]+", "123")).isFalse();
        assertThat(find("[\\w.]+@[a-z]+\\.com", "me.x@site.com")).isTrue();
        assertThat(find("colou?r", "color")).isTrue();
        assertThat(find("a*?b", "aaab")).isTrue();
        assertThat(find("\\.", "a.b")).isTrue();
        assertThat(find("[\\D]", "5")).isFalse();
    }

    @Test
    void caseHandling() {
        assertThat(SafeRegex.compile("mer[a-z]+", true).find("MERIDIAN")).isTrue();
        assertThat(SafeRegex.compile("mer[a-z]+", false).find("MERIDIAN")).isFalse();
        assertThat(SafeRegex.compile("(?i)mer", false).find("MERIDIAN")).isTrue();
    }

    @Test
    @Timeout(5)
    void catastrophicPatternsFinishFast() {
        String evil = "a".repeat(5000) + "!";
        for (String re : new String[] {"(a+)+$", "(a*)*$", "(a|aa)+$", "(a|a?)+$", "^(([a-z])+.)+[A-Z]([a-z])+$", "(.*a){20}"}) {
            long t = System.nanoTime();
            SafeRegex.compile(re, false).find(evil);                     // a backtracking engine would not return in a lifetime
            assertThat((System.nanoTime() - t) / 1_000_000).as(re).isLessThan(2000);
        }
    }

    @Test
    void longValuesAreCutNotScanned() {
        SafeRegex re = SafeRegex.compile("needle", false, new SafeRegex.Limits(200, 4000, 100, 50_000_000L));
        assertThat(re.find("x".repeat(100) + "needle")).isFalse();
    }

    @Test
    void aSlowMatchIsStoppedWithItsCode() {
        SafeRegex re = SafeRegex.compile("(a|b)*c(a|b)*d", false, new SafeRegex.Limits(200, 4000, 10_000_000, 1L));
        assertThatThrownBy(() -> re.find("ab".repeat(2_000_000))).isInstanceOfSatisfying(DrishtiException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REGEX_TIMEOUT));
    }

    @Test
    void refusesWhatItCannotDoInLinearTime() {
        for (String re : new String[] {"(a)\\1", "(?=a)", "(?!a)", "(?<=a)b", "(?<n>a)", "(?>a)", "a*+", "a++", "\\bx\\b", "(", "a)", "[a", "*a", "a{3,1}",
                "a{101}", "\\q", "", "(((((((((((((((((((((a)))))))))))))))))))))", "((a{100}){100}){100}"}) {
            assertThatThrownBy(() -> SafeRegex.compile(re, false)).as("'" + re + "'").isInstanceOfSatisfying(DrishtiException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REGEX_REFUSED));
        }
        assertThatThrownBy(() -> SafeRegex.compile("a".repeat(201), false)).hasMessageContaining("limit is 200");
    }

    /** extract picks the match and the groups Java's own engine picks: leftmost, then greedy longest or lazy shortest. */
    @Test
    void extractAgreesWithJavasEngineOnTheSafeSubset() {
        String[][] cases = {
            {"20[0-9]{2}-[0-9]{2}", "USD 2026-10 swap 2027-01"}, {"(\\d+)-(\\d+)", "id 12-345 and 6-7"}, {"a+", "baaab"},
            {"a+?", "baaab"}, {"(a|ab)(c|bcd)", "abcd"}, {"^(\\w+)@(\\w+)\\.com$", "me@site.com"}, {"(x)?y", "y"},
            {"(?:USD|EUR)-([A-Z]{2})", "pair EUR-GB, USD-JP"}, {"colou?r", "the colour red"}, {"(a*)(a*)", "aaa"},
            {"(a*?)(a*)", "aaa"}, {"[0-9]+$", "abc123"}, {"nothing", "here"}, {".*,", "a,b,c"}, {".*?,", "a,b,c"},
        };
        for (String[] c : cases) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(c[0]).matcher(c[1]);
            boolean found = m.find();
            SafeRegex re = SafeRegex.compile(c[0], false);
            assertThat(re.groups()).as(c[0]).isEqualTo(m.groupCount());
            for (int g = 0; g <= re.groups(); g++) {
                assertThat(re.extract(c[1], g)).as("%s on %s, group %d", c[0], c[1], g).isEqualTo(found ? m.group(g) : null);
            }
        }
        assertThat(SafeRegex.compile("(?i)usd", false).extract("in Usd terms", 0)).isEqualTo("Usd");
        assertThat(SafeRegex.compile("(?:a)(b)", false).groups()).isEqualTo(1);
        assertThat(find("(a+?)b", "aaab")).isTrue();                          // a lazy repeat answers yes/no as before
        assertThatThrownBy(() -> SafeRegex.compile("(a)", false).extract("a", 2)).isInstanceOfSatisfying(DrishtiException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REGEX_REFUSED));
    }

    @Test
    @Timeout(5)
    void extractStaysLinearOnCatastrophicPatterns() {
        String text = "a".repeat(8000) + "!";
        assertThat(SafeRegex.compile("^(a+)+$", false).extract(text, 1)).isNull();
        assertThat(SafeRegex.compile("(a|aa)+!", false).extract(text, 0)).hasSize(8001);
    }
}
