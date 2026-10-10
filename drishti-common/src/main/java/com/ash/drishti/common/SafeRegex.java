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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A linear-time regular-expression engine for search: a Thompson NFA run as a Pike simulation, so matching a value of
 * length n against a program of m steps costs at most n x m steps, whatever the pattern ({@code (a+)+$} included).
 * It is written here rather than taken from RE2/J because the build is offline; it offers the same safe subset:
 * literals, {@code .}, classes ({@code [a-z]}, {@code [^0-9]}, {@code \d \w \s} and their negations), groups
 * {@code (..)} and {@code (?:..)}, alternation, {@code * + ? {n} {n,} {n,m}} (greedy or lazy: a yes/no match does not
 * care), the anchors {@code ^} and {@code $}, and a leading {@code (?i)} or {@code (?-i)}. Back-references, look-around,
 * atomic groups, possessive quantifiers and word boundaries are refused with {@link ErrorCode#REGEX_REFUSED}, as are
 * patterns over {@link Limits#maxPattern()} characters or whose expanded program passes {@link Limits#maxProgram()}.
 * A match that runs past {@link Limits#timeoutNanos()} raises {@link ErrorCode#REGEX_TIMEOUT}; a value is cut to
 * {@link Limits#maxInput()} characters. A compiled pattern is immutable and may be shared between threads.
 */
public final class SafeRegex {

    /**
     * The caps. {@link #defaults()} reads the system properties {@code drishti.search.regex.max-pattern}, {@code .max-program},
     * {@code .max-input} and {@code .timeout-ms} (defaults 200, 4000, 8192 and 50).
     */
    public record Limits(int maxPattern, int maxProgram, int maxInput, long timeoutNanos) {
        public static Limits defaults() {
            return new Limits(intProp("max-pattern", 200), intProp("max-program", 4000), intProp("max-input", 8192),
                    intProp("timeout-ms", 50) * 1_000_000L);
        }

        private static int intProp(String name, int dflt) {
            try {
                return Math.max(1, Integer.parseInt(System.getProperty("drishti.search.regex." + name, String.valueOf(dflt)).trim()));
            } catch (NumberFormatException e) {
                return dflt;
            }
        }
    }

    private static final int CHAR = 0;
    private static final int ANY = 1;
    private static final int CLASS = 2;
    private static final int SPLIT = 3;
    private static final int JMP = 4;
    private static final int BOL = 5;
    private static final int EOL = 6;
    private static final int MATCH = 7;
    private static final int MAX_REPEAT = 100;

    private final int[] op;
    private final int[] x;
    private final int[] y;
    private final int[][][] classes;
    private final boolean fold;
    private final Limits limits;
    private final String source;

    private SafeRegex(Parser p, Limits limits, String source) {
        this.op = p.op.stream().mapToInt(Integer::intValue).toArray();
        this.x = p.x.stream().mapToInt(Integer::intValue).toArray();
        this.y = p.y.stream().mapToInt(Integer::intValue).toArray();
        this.classes = p.classes.toArray(new int[0][][]);
        this.fold = p.fold;
        this.limits = limits;
        this.source = source;
    }

    /** Compiles with the default {@link Limits}. */
    public static SafeRegex compile(String pattern, boolean caseInsensitive) {
        return compile(pattern, caseInsensitive, Limits.defaults());
    }

    /** Compiles, or refuses with {@link ErrorCode#REGEX_REFUSED} saying why. */
    public static SafeRegex compile(String pattern, boolean caseInsensitive, Limits limits) {
        if (pattern == null || pattern.isEmpty()) {
            throw refused("the pattern is empty");
        }
        if (pattern.length() > limits.maxPattern()) {
            throw refused("the pattern is " + pattern.length() + " characters; the limit is " + limits.maxPattern());
        }
        Parser p = new Parser(pattern, caseInsensitive, limits);
        p.parse();
        return new SafeRegex(p, limits, pattern);
    }

    public String pattern() {
        return source;
    }

    /** True when the pattern matches anywhere in the text (use {@code ^} and {@code $} to anchor). */
    public boolean find(CharSequence text) {
        int n = Math.min(text.length(), limits.maxInput());
        int m = op.length;
        int[] cur = new int[m];
        int[] next = new int[m];
        int[] mark = new int[m];
        Arrays.fill(mark, -1);
        int[] stack = new int[m * 2 + 2];
        int curN = 0;
        long deadline = System.nanoTime() + limits.timeoutNanos();
        for (int i = 0; i <= n; i++) {
            if ((i & 255) == 0 && System.nanoTime() > deadline) {
                throw new DrishtiException(ErrorCode.REGEX_TIMEOUT, "the pattern '" + source + "' took longer than "
                        + limits.timeoutNanos() / 1_000_000 + " ms on a value");
            }
            curN = add(cur, curN, 0, i, n, mark, stack);                 // an unanchored search: a new thread at every position
            for (int k = 0; k < curN; k++) {
                if (op[cur[k]] == MATCH) {
                    return true;
                }
            }
            if (i == n) {
                break;
            }
            char c = text.charAt(i);
            int nextN = 0;
            for (int k = 0; k < curN; k++) {
                int pc = cur[k];
                if (step(pc, c)) {
                    nextN = add(next, nextN, pc + 1, i + 1, n, mark, stack);
                }
            }
            int[] t = cur;
            cur = next;
            next = t;
            curN = nextN;
        }
        return false;
    }

    private boolean step(int pc, char c) {
        return switch (op[pc]) {
            case CHAR -> c == x[pc] || (fold && Character.toLowerCase(c) == x[pc]);
            case ANY -> c != '\n';
            case CLASS -> inClass(classes[x[pc]], c, y[pc] == 1);
            default -> false;
        };
    }

    private boolean inClass(int[][] ranges, char c, boolean negated) {
        boolean in = has(ranges, c) || (fold && (has(ranges, Character.toLowerCase(c)) || has(ranges, Character.toUpperCase(c))));
        return in != negated;
    }

    private static boolean has(int[][] ranges, int c) {
        for (int[] r : ranges) {
            if (c >= r[0] && c <= r[1]) {
                return true;
            }
        }
        return false;
    }

    /** Adds the thread at start and everything it reaches without consuming input; each step once per position (mark). */
    private int add(int[] list, int n, int start, int pos, int len, int[] mark, int[] stack) {
        int sp = 0;
        stack[sp++] = start;
        while (sp > 0) {
            int pc = stack[--sp];
            if (mark[pc] == pos) {
                continue;
            }
            mark[pc] = pos;
            switch (op[pc]) {
                case JMP -> stack[sp++] = x[pc];
                case SPLIT -> {
                    stack[sp++] = y[pc];
                    stack[sp++] = x[pc];
                }
                case BOL -> {
                    if (pos == 0) {
                        stack[sp++] = pc + 1;
                    }
                }
                case EOL -> {
                    if (pos == len) {
                        stack[sp++] = pc + 1;
                    }
                }
                default -> list[n++] = pc;
            }
        }
        return n;
    }

    private static DrishtiException refused(String why) {
        return new DrishtiException(ErrorCode.REGEX_REFUSED, why);
    }

    /** Parses the pattern into a small syntax tree, then emits the NFA program from it. */
    private static final class Parser {
        final String s;
        final Limits limits;
        boolean fold;
        int pos;
        final List<Integer> op = new ArrayList<>();
        final List<Integer> x = new ArrayList<>();
        final List<Integer> y = new ArrayList<>();
        final List<int[][]> classes = new ArrayList<>();
        int groupDepth;

        Parser(String s, boolean fold, Limits limits) {
            this.s = s;
            this.fold = fold;
            this.limits = limits;
        }

        void parse() {
            if (s.startsWith("(?i)")) {
                fold = true;
                pos = 4;
            } else if (s.startsWith("(?-i)")) {
                fold = false;
                pos = 5;
            }
            Node n = alternation();
            if (pos < s.length()) {
                throw refused("an unbalanced ')' at position " + pos);
            }
            emit(n);
            emit(MATCH, 0, 0);
        }

        sealed interface Node permits Atom, Seq, Alt, Rep {}

        record Atom(int op, int x, int y) implements Node {}

        record Seq(List<Node> parts) implements Node {}

        record Alt(List<Node> options) implements Node {}

        record Rep(Node body, int min, int max) implements Node {}      // max -1: unbounded

        Node alternation() {
            List<Node> options = new ArrayList<>();
            options.add(sequence());
            while (pos < s.length() && s.charAt(pos) == '|') {
                pos++;
                options.add(sequence());
            }
            return options.size() == 1 ? options.get(0) : new Alt(options);
        }

        Node sequence() {
            List<Node> parts = new ArrayList<>();
            while (pos < s.length() && s.charAt(pos) != '|' && s.charAt(pos) != ')') {
                parts.add(quantified(atom()));
            }
            return new Seq(parts);
        }

        Node quantified(Node atom) {
            Node out = atom;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                int min;
                int max;
                int close = c == '{' ? s.indexOf('}', pos) : -1;
                if (c == '*') {
                    min = 0;
                    max = -1;
                    pos++;
                } else if (c == '+') {
                    min = 1;
                    max = -1;
                    pos++;
                } else if (c == '?') {
                    min = 0;
                    max = 1;
                    pos++;
                } else if (close > 0 && s.substring(pos + 1, close).matches("\\d+(,\\d*)?")) {
                    String[] parts = s.substring(pos + 1, close).split(",", -1);
                    min = bound(parts[0]);
                    max = parts.length == 1 ? min : parts[1].isEmpty() ? -1 : bound(parts[1]);
                    if (max != -1 && max < min) {
                        throw refused("a repeat {" + min + "," + max + "} runs backwards");
                    }
                    pos = close + 1;
                } else {
                    break;
                }
                if (pos < s.length() && s.charAt(pos) == '?') {
                    pos++;                                           // lazy: the same yes/no answer
                } else if (pos < s.length() && s.charAt(pos) == '+') {
                    throw refused("possessive quantifiers are not offered");
                }
                out = new Rep(out, min, max);
            }
            return out;
        }

        int bound(String digits) {
            if (digits.length() > 3 || Integer.parseInt(digits) > MAX_REPEAT) {
                throw refused("a repeat count over " + MAX_REPEAT + " is not offered");
            }
            return Integer.parseInt(digits);
        }

        Node atom() {
            char c = s.charAt(pos++);
            switch (c) {
                case '(' -> {
                    if (s.startsWith("?:", pos)) {
                        pos += 2;
                    } else if (s.startsWith("?", pos)) {
                        throw refused("look-around, atomic and named groups and inline flags ('(?...') are not offered");
                    }
                    if (++groupDepth > 20) {
                        throw refused("groups nest more than 20 deep");
                    }
                    Node inner = alternation();
                    if (pos >= s.length() || s.charAt(pos) != ')') {
                        throw refused("a '(' is not closed");
                    }
                    pos++;
                    groupDepth--;
                    return inner;
                }
                case '[' -> {
                    return charClass();
                }
                case '.' -> {
                    return new Atom(ANY, 0, 0);
                }
                case '^' -> {
                    return new Atom(BOL, 0, 0);
                }
                case '$' -> {
                    return new Atom(EOL, 0, 0);
                }
                case '\\' -> {
                    return escape();
                }
                case '*', '+', '?' -> throw refused("'" + c + "' has nothing to repeat");
                default -> {
                    return literal(c);
                }
            }
        }

        Node literal(int c) {
            return new Atom(CHAR, fold ? Character.toLowerCase(c) : c, 0);
        }

        Node escape() {
            if (pos >= s.length()) {
                throw refused("the pattern ends with a backslash");
            }
            char c = s.charAt(pos++);
            if (c >= '1' && c <= '9') {
                throw refused("back-references are not offered (they cannot be matched in linear time)");
            }
            if (c == 'b' || c == 'B' || c == 'A' || c == 'z' || c == 'Z' || c == 'G') {
                throw refused("\\" + c + " is not offered");
            }
            int[][] set = shorthand(c);
            if (set != null) {
                classes.add(set);
                return new Atom(CLASS, classes.size() - 1, Character.isUpperCase(c) ? 1 : 0);
            }
            return literal(named(c));
        }

        int named(char c) {
            return switch (c) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case 'f' -> '\f';
                default -> {
                    if (Character.isLetterOrDigit(c)) {
                        throw refused("\\" + c + " is not a known escape");
                    }
                    yield c;
                }
            };
        }

        static int[][] shorthand(char c) {
            return switch (Character.toLowerCase(c)) {
                case 'd' -> new int[][] {{'0', '9'}};
                case 'w' -> new int[][] {{'0', '9'}, {'A', 'Z'}, {'_', '_'}, {'a', 'z'}};
                case 's' -> new int[][] {{' ', ' '}, {'\t', '\r'}};
                default -> null;
            };
        }

        Node charClass() {
            boolean negated = false;
            if (pos < s.length() && s.charAt(pos) == '^') {
                negated = true;
                pos++;
            }
            List<int[]> ranges = new ArrayList<>();
            boolean first = true;
            while (pos < s.length() && (s.charAt(pos) != ']' || first)) {
                first = false;
                int lo = classChar(ranges);
                if (lo < 0) {
                    continue;                                        // a shorthand was added whole
                }
                if (pos + 1 < s.length() && s.charAt(pos) == '-' && s.charAt(pos + 1) != ']') {
                    pos++;
                    int hi = classChar(ranges);
                    if (hi < 0 || hi < lo) {
                        throw refused("a range in a class is not valid");
                    }
                    ranges.add(new int[] {lo, hi});
                } else {
                    ranges.add(new int[] {lo, lo});
                }
            }
            if (pos >= s.length()) {
                throw refused("a '[' is not closed");
            }
            pos++;
            classes.add(ranges.toArray(new int[0][]));
            return new Atom(CLASS, classes.size() - 1, negated ? 1 : 0);
        }

        int classChar(List<int[]> ranges) {
            char c = s.charAt(pos++);
            if (c != '\\') {
                return c;
            }
            if (pos >= s.length()) {
                throw refused("the pattern ends with a backslash");
            }
            char e = s.charAt(pos++);
            int[][] set = shorthand(e);
            if (set == null) {
                return named(e);
            }
            if (Character.isUpperCase(e)) {
                int from = 0;
                int[][] sorted = set.clone();
                Arrays.sort(sorted, (a, b) -> Integer.compare(a[0], b[0]));
                for (int[] r : sorted) {
                    if (r[0] > from) {
                        ranges.add(new int[] {from, r[0] - 1});
                    }
                    from = r[1] + 1;
                }
                ranges.add(new int[] {from, 0xFFFF});
            } else {
                ranges.addAll(Arrays.asList(set));
            }
            return -1;
        }

        int emit(int o, int a, int b) {
            if (op.size() >= limits.maxProgram()) {
                throw refused("the pattern is too large once expanded (more than " + limits.maxProgram() + " steps)");
            }
            op.add(o);
            x.add(a);
            y.add(b);
            return op.size() - 1;
        }

        void emit(Node n) {
            switch (n) {
                case Atom a -> emit(a.op(), a.x(), a.y());
                case Seq q -> q.parts().forEach(this::emit);
                case Alt a -> {
                    List<Integer> jumps = new ArrayList<>();
                    for (int i = 0; i < a.options().size(); i++) {
                        if (i < a.options().size() - 1) {
                            int split = emit(SPLIT, 0, 0);
                            x.set(split, op.size());
                            emit(a.options().get(i));
                            jumps.add(emit(JMP, 0, 0));
                            y.set(split, op.size());
                        } else {
                            emit(a.options().get(i));
                        }
                    }
                    jumps.forEach(j -> x.set(j, op.size()));
                }
                case Rep r -> {
                    for (int i = 0; i < r.min(); i++) {
                        emit(r.body());
                    }
                    if (r.max() == -1) {
                        int split = emit(SPLIT, 0, 0);
                        x.set(split, op.size());
                        emit(r.body());
                        emit(JMP, split, 0);
                        y.set(split, op.size());
                    } else {
                        List<Integer> splits = new ArrayList<>();
                        for (int i = r.min(); i < r.max(); i++) {
                            int split = emit(SPLIT, 0, 0);
                            x.set(split, op.size());
                            splits.add(split);
                            emit(r.body());
                        }
                        splits.forEach(sp -> y.set(sp, op.size()));
                    }
                }
            }
        }
    }
}
