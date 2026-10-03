package com.ash.drishti.engine.shape;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * A string that matches a simple regular expression, for synthetic samples: literals, {@code .}, classes
 * ({@code [A-Z0-9_-]}, {@code [^x]}), the escapes {@code \d \w \s}, groups with alternation, the quantifiers
 * {@code ? * + {n} {n,m}} and the anchors. Anything beyond that subset is not understood and {@link #sample} returns
 * {@code null}; the result is always checked against the real pattern, so a wrong guess is never returned.
 */
final class RegexSampler {

    private static final int OPEN_MAX = 3;
    private final String re;
    private final Random rnd;
    private int pos;

    private RegexSampler(String re, Random rnd) {
        this.re = re;
        this.rnd = rnd;
    }

    /** A string matching {@code pattern}, or null when the pattern is outside the supported subset. */
    static String sample(String pattern, Random rnd) {
        try {
            RegexSampler g = new RegexSampler(pattern, rnd);
            String s = g.alternation();
            if (g.pos != pattern.length()) {
                return null;
            }
            return Pattern.compile(pattern).matcher(s).find() ? s : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String alternation() {
        List<String> branches = new ArrayList<>();
        branches.add(sequence());
        while (pos < re.length() && re.charAt(pos) == '|') {
            pos++;
            branches.add(sequence());
        }
        return branches.get(rnd.nextInt(branches.size()));
    }

    private String sequence() {
        StringBuilder b = new StringBuilder();
        while (pos < re.length() && re.charAt(pos) != '|' && re.charAt(pos) != ')') {
            char c = re.charAt(pos);
            if (c == '^' || c == '$') {
                pos++;
                continue;
            }
            Atom atom = atom();
            int[] range = quantifier();
            int times = range[0] + (range[1] > range[0] ? rnd.nextInt(range[1] - range[0] + 1) : 0);
            for (int i = 0; i < times; i++) {
                b.append(i == 0 ? atom.text : atom.again());
            }
        }
        return b.toString();
    }

    /** One element; a class or group produces a fresh choice each time it repeats. */
    private final class Atom {
        private final String text;
        private final String source;

        Atom(String text, String source) {
            this.text = text;
            this.source = source;
        }

        String again() {
            if (source == null) {
                return text;
            }
            RegexSampler g = new RegexSampler(source, rnd);
            return g.alternation();
        }
    }

    private Atom atom() {
        char c = re.charAt(pos);
        if (c == '(') {
            int start = ++pos;
            if (re.startsWith("?:", pos)) {
                pos += 2;
                start = pos;
            } else if (re.charAt(pos) == '?') {
                throw new IllegalArgumentException("lookaround or named group");
            }
            int depth = 1;
            int p = pos;
            while (p < re.length() && depth > 0) {
                char x = re.charAt(p);
                if (x == '\\') {
                    p++;
                } else if (x == '(') {
                    depth++;
                } else if (x == ')') {
                    depth--;
                }
                p++;
            }
            if (depth != 0) {
                throw new IllegalArgumentException("unbalanced group");
            }
            String inner = re.substring(start, p - 1);
            pos = p;
            return new Atom(new RegexSampler(inner, rnd).alternation(), inner);
        }
        if (c == '[') {
            int end = pos + 1;
            if (end < re.length() && re.charAt(end) == '^') {
                end++;
            }
            end++;
            while (end < re.length() && re.charAt(end) != ']') {
                end += re.charAt(end) == '\\' ? 2 : 1;
            }
            String body = re.substring(pos + 1, end);
            pos = end + 1;
            return new Atom(pick(body), "[" + body + "]");
        }
        if (c == '\\') {
            String esc = re.substring(pos, pos + 2);
            pos += 2;
            return switch (esc.charAt(1)) {
                case 'd' -> new Atom(pick("0-9"), "\\d");
                case 'w' -> new Atom(pick("A-Za-z0-9_"), "\\w");
                case 's' -> new Atom(" ", null);
                case 'D', 'W', 'S', 'b', 'B', 'p', 'P', 'k' -> throw new IllegalArgumentException("unsupported escape");
                default -> new Atom(String.valueOf(esc.charAt(1)), null);
            };
        }
        if (c == '.') {
            pos++;
            return new Atom(pick("a-z"), ".");
        }
        if (c == '*' || c == '+' || c == '?' || c == '{') {
            throw new IllegalArgumentException("dangling quantifier");
        }
        pos++;
        return new Atom(String.valueOf(c), null);
    }

    private int[] quantifier() {
        if (pos >= re.length()) {
            return new int[] {1, 1};
        }
        int[] r;
        switch (re.charAt(pos)) {
            case '?' -> {
                pos++;
                r = new int[] {0, 1};
            }
            case '*' -> {
                pos++;
                r = new int[] {0, OPEN_MAX};
            }
            case '+' -> {
                pos++;
                r = new int[] {1, OPEN_MAX};
            }
            case '{' -> {
                int close = re.indexOf('}', pos);
                String[] parts = re.substring(pos + 1, close).split(",", -1);
                int lo = Integer.parseInt(parts[0].trim());
                int hi = parts.length == 1 ? lo : parts[1].isBlank() ? lo + OPEN_MAX : Integer.parseInt(parts[1].trim());
                pos = close + 1;
                r = new int[] {lo, Math.max(lo, Math.min(hi, lo + 12))};
            }
            default -> {
                return new int[] {1, 1};
            }
        }
        if (pos < re.length() && (re.charAt(pos) == '?' || re.charAt(pos) == '+')) {
            pos++;
        }
        return r;
    }

    /** One character from a class body such as {@code A-Z0-9_-} (a leading {@code ^} picks a letter outside it). */
    private String pick(String body) {
        boolean negate = body.startsWith("^");
        String b = negate ? body.substring(1) : body;
        List<Character> chars = new ArrayList<>();
        for (int i = 0; i < b.length(); i++) {
            char c = b.charAt(i);
            if (c == '\\' && i + 1 < b.length()) {
                char e = b.charAt(++i);
                switch (e) {
                    case 'd' -> addRange(chars, '0', '9');
                    case 'w' -> {
                        addRange(chars, 'a', 'z');
                        addRange(chars, '0', '9');
                    }
                    case 's' -> chars.add(' ');
                    default -> chars.add(e);
                }
            } else if (i + 2 < b.length() && b.charAt(i + 1) == '-') {
                addRange(chars, c, b.charAt(i + 2));
                i += 2;
            } else {
                chars.add(c);
            }
        }
        if (negate) {
            List<Character> outside = new ArrayList<>();
            for (char c = 'a'; c <= 'z'; c++) {
                if (!chars.contains(c)) {
                    outside.add(c);
                }
            }
            chars = outside;
        }
        if (chars.isEmpty()) {
            throw new IllegalArgumentException("empty class");
        }
        return String.valueOf(chars.get(rnd.nextInt(chars.size())));
    }

    private static void addRange(List<Character> out, char from, char to) {
        for (char c = from; c <= to; c++) {
            out.add(c);
        }
    }
}
