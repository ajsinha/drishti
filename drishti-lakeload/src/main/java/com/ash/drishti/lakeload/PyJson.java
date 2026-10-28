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
package com.ash.drishti.lakeload;

/**
 * JSON text exactly as {@code drishti.py lake build} writes a document ({@code json.dumps(doc, ensure_ascii=False,
 * separators=(",", ":"))} in {@code tools/lake/stage.py}): compact, non-ASCII kept, Python's escapes, an integer as its digits and a
 * float as Python's {@code repr} (the shortest text that reads back to the same double: {@code 1.0}, {@code 1e-05},
 * {@code 1.2345678901234568e+17}). A table loaded by the parallel loader therefore holds the same document text, byte for byte, as one
 * loaded by the Python path.
 */
final class PyJson {

    private static final byte[] HEX = "0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private PyJson() {}

    /** A JSON string of {@code len} characters from {@code c}, quoted and escaped as Python's encoder escapes it. */
    static void string(Bytes out, char[] c, int off, int len) {
        out.ensure(len * 3 + 2);
        byte[] a = out.a;
        int n = out.n;
        a[n++] = '"';
        int end = off + len;
        for (int i = off; i < end; i++) {
            char ch = c[i];
            if (ch < 0x80) {
                if (ch >= 0x20 && ch != '"' && ch != '\\') {
                    a[n++] = (byte) ch;
                    continue;
                }
                out.n = n;
                escape(out, ch);
                out.ensure((end - i) * 3 + 8);                         // the rest still fits at three bytes a character
                a = out.a;
                n = out.n;
            } else if (ch < 0x800) {
                a[n++] = (byte) (0xc0 | ch >> 6);
                a[n++] = (byte) (0x80 | ch & 0x3f);
            } else if (Character.isHighSurrogate(ch) && i + 1 < end && Character.isLowSurrogate(c[i + 1])) {
                int cp = Character.toCodePoint(ch, c[++i]);           // two characters, budgeted six bytes, take four
                a[n++] = (byte) (0xf0 | cp >> 18);
                a[n++] = (byte) (0x80 | cp >> 12 & 0x3f);
                a[n++] = (byte) (0x80 | cp >> 6 & 0x3f);
                a[n++] = (byte) (0x80 | cp & 0x3f);
            } else {
                char x = Character.isSurrogate(ch) ? '�' : ch;        // a lone surrogate cannot be UTF-8: the replacement character
                a[n++] = (byte) (0xe0 | x >> 12);
                a[n++] = (byte) (0x80 | x >> 6 & 0x3f);
                a[n++] = (byte) (0x80 | x & 0x3f);
            }
        }
        out.n = n;
        out.put('"');
    }

    static void string(Bytes out, String s) {
        string(out, s.toCharArray(), 0, s.length());
    }

    private static void escape(Bytes out, char ch) {
        out.ensure(6);
        switch (ch) {
            case '"' -> out.ascii("\\\"");
            case '\\' -> out.ascii("\\\\");
            case '\n' -> out.ascii("\\n");
            case '\r' -> out.ascii("\\r");
            case '\t' -> out.ascii("\\t");
            case '\b' -> out.ascii("\\b");
            case '\f' -> out.ascii("\\f");
            default -> {
                out.ascii("\\u00");
                out.put(HEX[ch >> 4 & 0xf]);
                out.put(HEX[ch & 0xf]);
            }
        }
    }

    /** An integer token's text as Python writes the int it reads: the same digits ({@code -0} is {@code 0}). */
    static String intText(String token) {
        return token.equals("-0") ? "0" : token;
    }

    /** Python's {@code repr(float)} (and what {@code json.dumps} writes for a float; NaN and the infinities as Python's encoder names them). */
    static String floatRepr(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        if (d == 0) {
            return (Double.doubleToRawLongBits(d) < 0) ? "-0.0" : "0.0";
        }
        // Java's Double.toString gives the shortest digits that read back to the same double (JDK 19 and later), as Python's repr does;
        // only the layout differs: Python writes fixed notation for 1e-4 <= |d| < 1e16, else d.ddde+XX
        String s = Double.toString(Math.abs(d));
        String digits;
        int decpt;                                                     // the value is 0.<digits> x 10^decpt
        int e = s.indexOf('E');
        if (e >= 0) {
            String mant = s.substring(0, e).replace(".", "");
            digits = stripTrailingZeros(mant);
            decpt = Integer.parseInt(s.substring(e + 1)) + 1;
        } else {
            int dot = s.indexOf('.');
            String all = s.substring(0, dot) + s.substring(dot + 1);
            int lead = 0;
            while (lead < all.length() - 1 && all.charAt(lead) == '0') {
                lead++;
            }
            digits = stripTrailingZeros(all.substring(lead));
            decpt = dot - lead;
        }
        StringBuilder out = new StringBuilder(24);
        if (d < 0) {
            out.append('-');
        }
        if (decpt > -4 && decpt <= 16) {
            if (decpt <= 0) {
                out.append("0.").append("0".repeat(-decpt)).append(digits);
            } else if (decpt >= digits.length()) {
                out.append(digits).append("0".repeat(decpt - digits.length())).append(".0");
            } else {
                out.append(digits, 0, decpt).append('.').append(digits, decpt, digits.length());
            }
        } else {
            out.append(digits.charAt(0));
            if (digits.length() > 1) {
                out.append('.').append(digits, 1, digits.length());
            }
            int exp = decpt - 1;
            out.append('e').append(exp < 0 ? '-' : '+');
            int a = Math.abs(exp);
            if (a < 10) {
                out.append('0');
            }
            out.append(a);
        }
        return out.toString();
    }

    private static String stripTrailingZeros(String s) {
        int end = s.length();
        while (end > 1 && s.charAt(end - 1) == '0') {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * An id as {@code lake build} keeps it ({@code stage.id_text}): a number that is whole (and under 1e18) as its digits, never
     * {@code 1000008.0}; another float as Python's repr.
     */
    static String idOfFloat(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e18) {
            return Long.toString((long) d);
        }
        return floatRepr(d);
    }
}
