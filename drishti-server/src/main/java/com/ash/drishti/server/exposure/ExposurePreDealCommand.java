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
package com.ash.drishti.server.exposure;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pre-deal command's grammar (EXPOSURE_GUIDE.md section 18.9), one request whether typed or sent as fields:
 *
 * <pre>
 *   PREDEAL &lt;counterparty&gt; LIKE &lt;trade&gt; [&lt;notional&gt;] [REVERSE] [IN &lt;netting set&gt;]
 *   PREDEAL &lt;counterparty&gt; &lt;asset class&gt; &lt;notional&gt; &lt;maturity&gt; [MTM &lt;amount&gt;] [REVERSE] [IN &lt;netting set&gt;]
 * </pre>
 *
 * The counterparty as its id ({@code CP-ALDERSHOT}, {@code CPTY:CP-ALDERSHOT} or {@code CPTY CP-ALDERSHOT}). {@code LIKE} names the
 * template: a booked trade whose paths on the date's run are copied, scaled to the notional ({@code REVERSE}: the other side), the exact
 * method. Without a template the trade is described by its asset class ({@code RATES}, {@code FX}, {@code CREDIT}, {@code EQUITY},
 * {@code COMMODITY}), notional and maturity (a date or a tenor: {@code 5Y}, {@code 18M}), and the answer is the conservative proxy,
 * which only refers. Amounts: {@code 250M}, {@code 1.5BN}, {@code 400K}, {@code 250,000,000}. The words are Drishti's: upper case,
 * one space apart, any order after the counterparty.
 */
record ExposurePreDealCommand(String counterparty, String like, Double notional, boolean reverse, String nettingSet, String assetClass,
        String maturity, Double mtm) {

    static final String WORD = "PREDEAL";
    static final List<String> CLASSES = List.of("RATES", "FX", "CREDIT", "EQUITY", "COMMODITY");
    private static final Pattern AMOUNT = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]+)?)(K|M|MM|BN|B)?");
    private static final Pattern TENOR = Pattern.compile("(\\d+)([DWMY])");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern ID = Pattern.compile("[A-Z0-9][A-Z0-9_.\\-]{0,79}");

    /** The exact method: a template trade's paths copied. */
    boolean exact() {
        return like != null;
    }

    /** The command as Drishti writes it back (the canonical form the answer shows). */
    String text() {
        StringBuilder b = new StringBuilder(WORD).append(' ').append(counterparty);
        if (like != null) {
            b.append(" LIKE ").append(like);
        } else {
            b.append(' ').append(assetClass);
        }
        if (notional != null) {
            b.append(' ').append(amount(notional));
        }
        if (like == null && maturity != null) {
            b.append(' ').append(maturity);
        }
        if (mtm != null) {
            b.append(" MTM ").append(amount(mtm));
        }
        if (reverse) {
            b.append(" REVERSE");
        }
        if (nettingSet != null) {
            b.append(" IN ").append(nettingSet);
        }
        return b.toString();
    }

    /** The maturity as a date: a date as given, a tenor from the business date. */
    LocalDate maturityOn(LocalDate businessDate) {
        if (maturity == null) {
            return null;
        }
        if (DATE.matcher(maturity).matches()) {
            return LocalDate.parse(maturity);
        }
        Matcher m = TENOR.matcher(maturity);
        if (!m.matches()) {
            throw bad("a maturity is a date (YYYY-MM-DD) or a tenor (5Y, 18M, 6W, 90D), not " + maturity);
        }
        int n = Integer.parseInt(m.group(1));
        return switch (m.group(2)) {
            case "D" -> businessDate.plusDays(n);
            case "W" -> businessDate.plusWeeks(n);
            case "M" -> businessDate.plusMonths(n);
            default -> businessDate.plusYears(n);
        };
    }

    /** A typed command: {@code PREDEAL CP-ALDERSHOT LIKE 1000001 250M REVERSE IN NS-ALDERSHOT-LDN}. */
    static ExposurePreDealCommand parse(String typed) {
        String t = typed == null ? "" : typed.replace("<GO>", "").replace("<go>", "").trim();
        List<String> w = new ArrayList<>(List.of(t.isEmpty() ? new String[0] : t.toUpperCase(Locale.ROOT).split("\\s+")));
        if (w.isEmpty() || !w.get(0).equals(WORD)) {
            throw bad("a pre-deal check starts with " + WORD + ": " + usage());
        }
        w.remove(0);
        if (w.isEmpty()) {
            throw bad("name the counterparty: " + usage());
        }
        String cp = w.remove(0);
        if ((cp.equals("CPTY") || cp.equals("CPTY:")) && !w.isEmpty()) {
            cp = w.remove(0);
        }
        String like = null;
        String set = null;
        String cls = null;
        String maturity = null;
        Double notional = null;
        Double mtm = null;
        boolean reverse = false;
        for (int i = 0; i < w.size(); i++) {
            String x = w.get(i);
            switch (x) {
                case "LIKE" -> like = next(w, ++i, "LIKE names the template trade: LIKE <trade>");
                case "IN" -> set = next(w, ++i, "IN names the netting set: IN NS-…");
                case "MTM" -> mtm = signedAmount(next(w, ++i, "MTM is followed by an amount"));
                case "REVERSE" -> reverse = true;
                default -> {
                    if (CLASSES.contains(x)) {
                        cls = x;
                    } else if (DATE.matcher(x).matches() || TENOR.matcher(x).matches() && notional != null) {
                        maturity = x;
                    } else if (AMOUNT.matcher(x).matches() && notional == null) {
                        notional = amount(x);
                    } else {
                        throw bad("the word " + x + " is not part of a pre-deal check: " + usage());
                    }
                }
            }
        }
        return of(cp, like, notional, reverse, set, cls, maturity, mtm);
    }

    /**
     * A request's fields: {@code command} (typed, as {@link #parse}), or {@code counterparty} with {@code template} ({@code {copyOf}}),
     * {@code like}, {@code notional} (a number or {@code 250m}), {@code side} ({@code same} or {@code reverse}), {@code nettingSet},
     * {@code assetClass}, {@code maturity}, {@code mtm}.
     */
    static ExposurePreDealCommand of(Map<String, Object> body) {
        Object cmd = body.get("command");
        if (cmd != null && !String.valueOf(cmd).isBlank()) {
            return parse(String.valueOf(cmd));
        }
        String cp = str(body.get("counterparty"));
        if (cp == null) {
            throw bad("a pre-deal check names its counterparty: {\"counterparty\": \"CP-ALDERSHOT\", \"like\": \"<trade>\", \"notional\": \"250m\"} "
                    + "or {\"command\": \"" + WORD + " CP-ALDERSHOT LIKE <trade> 250M\"}");
        }
        String like = str(body.get("like"));
        if (like == null && body.get("template") instanceof Map<?, ?> tm) {
            like = str(tm.get("copyOf"));
        }
        Object n = body.get("notional");
        Double notional = n == null ? null : n instanceof Number x ? Double.valueOf(x.doubleValue()) : amount(String.valueOf(n).trim());
        String side = str(body.get("side"));
        if (side != null && !side.equals("SAME") && !side.equals("REVERSE")) {
            throw bad("side is same (the template's side) or reverse, not " + side.toLowerCase(Locale.ROOT));
        }
        Object m = body.get("mtm");
        Double mtm = m == null ? null : m instanceof Number x ? Double.valueOf(x.doubleValue()) : signedAmount(String.valueOf(m).trim());
        String cls = str(body.get("assetClass"));
        if (cls != null && !CLASSES.contains(cls)) {                    // as the trades name it (Interest Rate, Foreign Exchange ...)
            cls = ExposureWhatIf.factor(cls).toUpperCase(Locale.ROOT);
        }
        String mat = str(body.get("maturity"));
        if (mat != null && mat.length() > 10 && DATE.matcher(mat.substring(0, 10)).matches()) {
            mat = mat.substring(0, 10);                                  // a timestamp's date
        }
        return of(cp, like, notional, "REVERSE".equals(side), str(body.get("nettingSet")), cls, mat, mtm);
    }

    private static ExposurePreDealCommand of(String cp, String like, Double notional, boolean reverse, String set, String cls, String maturity,
            Double mtm) {
        String c = cp.startsWith("CPTY:") ? cp.substring(5) : cp;
        if (!ID.matcher(c).matches()) {
            throw bad("the counterparty is an id (CP-ALDERSHOT), not " + cp);
        }
        if (like != null && !ID.matcher(like).matches()) {
            throw bad("the template is a trade id, not " + like);
        }
        String s = set == null ? null : set.startsWith("NS-") ? set : "NS-" + set;
        if (s != null && !ID.matcher(s).matches()) {
            throw bad("the netting set is an id (NS-…), not " + set);
        }
        if (notional != null && !(notional > 0 && Double.isFinite(notional))) {
            throw bad("the notional is a positive amount (250M, 1.5BN)");
        }
        if (like != null && (cls != null || maturity != null)) {
            throw bad("a template trade (LIKE) brings its asset class and maturity: give one or the other");
        }
        if (like == null) {
            if (cls == null || !CLASSES.contains(cls)) {
                throw bad("without a template (LIKE <trade>) name the asset class (" + String.join(", ", CLASSES) + "), the notional and the maturity: "
                        + usage());
            }
            if (notional == null || maturity == null) {
                throw bad("without a template name the notional and the maturity (5Y or a date): " + usage());
            }
        }
        return new ExposurePreDealCommand(c, like, notional, reverse, s, cls, maturity, mtm);
    }

    static String usage() {
        return WORD + " <counterparty> LIKE <trade> [<notional>] [REVERSE] [IN <netting set>], or " + WORD
                + " <counterparty> <RATES|FX|CREDIT|EQUITY|COMMODITY> <notional> <maturity> [MTM <amount>] [REVERSE] [IN <netting set>]";
    }

    private static String next(List<String> w, int i, String why) {
        if (i >= w.size()) {
            throw bad(why);
        }
        return w.get(i);
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    /** {@code 250M}, {@code 1.5BN}, {@code 400K}, {@code 250,000,000}. */
    static double amount(String s) {
        Matcher m = AMOUNT.matcher(s.toUpperCase(Locale.ROOT).replace(" ", ""));
        if (!m.matches()) {
            throw bad("an amount is a number with K, M or BN (250M, 1.5BN), not " + s);
        }
        double v = Double.parseDouble(m.group(1).replace(",", ""));
        String u = m.group(2) == null ? "" : m.group(2);
        return v * switch (u) {
            case "K" -> 1e3;
            case "M", "MM" -> 1e6;
            case "BN", "B" -> 1e9;
            default -> 1;
        };
    }

    private static double signedAmount(String s) {
        return s.startsWith("-") ? -amount(s.substring(1)) : amount(s);
    }

    /** An amount as the command writes it: {@code 250M}, {@code 1.5BN}, {@code 400K}. */
    static String amount(double v) {
        double a = Math.abs(v);
        String sign = v < 0 ? "-" : "";
        if (a >= 1e9 && a % 1e7 == 0) {
            return sign + strip(a / 1e9) + "BN";
        }
        if (a >= 1e6 && a % 1e4 == 0) {
            return sign + strip(a / 1e6) + "M";
        }
        if (a >= 1e3 && a % 10 == 0) {
            return sign + strip(a / 1e3) + "K";
        }
        return sign + strip(a);
    }

    private static String strip(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private static DrishtiException bad(String why) {
        return new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, why);
    }
}
