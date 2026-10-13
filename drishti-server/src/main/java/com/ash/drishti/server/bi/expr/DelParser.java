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
package com.ash.drishti.server.bi.expr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The DEL v2 parser (docs/architecture/BI_DESIGN.md section 3.3.2): hand-written recursive descent over the accepted subset of Python's expression grammar.
 * Every text it accepts is a valid Python expression ({@code ast.parse(text, mode="eval")}); everything else Python has (assignment, lambda, comprehensions,
 * subscripts, method calls, star arguments, f-strings, dict/set/tuple literals, imports, await) is refused with {@code DRS-9127} naming the construct and the
 * DEL way to write it. Bounded: 2,000 characters (4,000 when written over several lines), nesting depth 64. Pure and thread-safe.
 *
 * <pre>
 * expr       := orexpr ["if" orexpr "else" expr]
 * orexpr     := andexpr ("or" andexpr)*
 * andexpr    := notexpr ("and" notexpr)*
 * notexpr    := "not" notexpr | comparison
 * comparison := arith (compop arith)*
 * arith      := term (("+" | "-") term)*
 * term       := factor (("*" | "/" | "//" | "%") factor)*
 * factor     := ("-" | "+") factor | power
 * power      := primary ["**" factor]
 * primary    := atom | call | field
 * call       := NAME "(" [arg ("," arg)* [","]] ")"
 * arg        := expr | NAME "=" expr
 * atom       := NUMBER | STRING | True | False | None | ALL | list | "(" expr ")"
 * </pre>
 */
public final class DelParser {

    public static final String CODE = "DRS-9127";
    public static final int MAX_LENGTH = 2000;
    public static final int MAX_BLOCK_LENGTH = 4000;
    public static final int MAX_DEPTH = 64;

    /** Python keywords DEL refuses, with what to say. */
    private static final java.util.Map<String, String[]> REFUSED_WORDS = java.util.Map.ofEntries(
            java.util.Map.entry("lambda", new String[] {"lambda is not DEL: an expression cannot define a function", "use a catalogue function, or a Python item for code"}),
            java.util.Map.entry("for", new String[] {"comprehensions and generators are not DEL", "aggregate with sum(x, where=...) or count(alias, where=...)"}),
            java.util.Map.entry("import", new String[] {"import is not DEL", null}),
            java.util.Map.entry("from", new String[] {"import is not DEL", null}),
            java.util.Map.entry("await", new String[] {"await is not DEL", null}),
            java.util.Map.entry("yield", new String[] {"yield is not DEL", null}),
            java.util.Map.entry("def", new String[] {"def is not DEL: an expression cannot define a function", null}),
            java.util.Map.entry("class", new String[] {"class is not DEL", null}),
            java.util.Map.entry("del", new String[] {"del is not DEL", null}),
            java.util.Map.entry("global", new String[] {"global is not DEL", null}),
            java.util.Map.entry("nonlocal", new String[] {"nonlocal is not DEL", null}),
            java.util.Map.entry("return", new String[] {"return is not DEL: an expression is its own value", null}),
            java.util.Map.entry("assert", new String[] {"assert is not DEL", null}),
            java.util.Map.entry("with", new String[] {"with is not DEL", null}),
            java.util.Map.entry("try", new String[] {"try is not DEL: DEL never raises (a bad value is None)", null}),
            java.util.Map.entry("while", new String[] {"loops are not DEL", "aggregate with sum(x, where=...)"}),
            java.util.Map.entry("raise", new String[] {"raise is not DEL", null}),
            java.util.Map.entry("pass", new String[] {"pass is not DEL", null}));

    /** The old syntax's words, refused with the DEL spelling. */
    private static final java.util.Map<String, String> OLD_WORDS = java.util.Map.of("true", "True", "false", "False", "null", "None");

    private static final Set<String> KEYWORDS = Set.of("and", "or", "not", "if", "else", "in", "is", "True", "False", "None");

    enum T { NUM, STR, NAME, OP, EOF }

    record Tok(T type, String text, int start, int end, Object value) {}

    private final String src;
    private final List<Tok> toks;
    private int p;
    private int depth;

    private DelParser(String src, List<Tok> toks) {
        this.src = src;
        this.toks = toks;
    }

    /** The tree of a DEL v2 expression, or a {@link DelException} (code, message, fix, position). */
    public static DelAst parse(String src) {
        if (src == null || src.isBlank()) {
            throw new DelException(CODE, "the expression is empty", null, 0, 0);
        }
        int max = src.indexOf('\n') >= 0 ? MAX_BLOCK_LENGTH : MAX_LENGTH;
        if (src.length() > max) {
            throw new DelException(CODE, "the expression is longer than " + max + " characters", "split it into measures that use each other with measure('name')", max, src.length());
        }
        DelParser ps = new DelParser(src, lex(src));
        DelAst n = ps.expr();
        Tok t = ps.peek();
        if (t.type() != T.EOF) {
            throw ps.unexpected(t);
        }
        return n;
    }

    // ---- lexer -------------------------------------------------------------------------------------------------------

    private static final List<String> OPS3 = List.of("**=", "//=", "...");
    private static final List<String> OPS2 = List.of("==", "!=", "<=", ">=", "//", "**", ":=", "&&", "||", "->", "+=", "-=", "*=", "/=", "%=", "<<", ">>");

    static List<Tok> lex(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '#') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '\\' && i + 1 < n && s.charAt(i + 1) == '\n') {
                i += 2;                                                         // a line continuation
            } else if (Character.isDigit(c) || c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1))) {
                i = number(s, i, out);
            } else if (c == '\'' || c == '"') {
                i = string(s, i, out);
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                    j++;
                }
                String word = s.substring(i, j);
                if (j < n && (s.charAt(j) == '\'' || s.charAt(j) == '"') && word.length() <= 2 && word.toLowerCase(Locale.ROOT).matches("[rbfu]+")) {
                    throw new DelException(CODE, "prefixed strings (" + word + "'...') are not DEL: f-strings, bytes and raw strings are refused", "write a plain 'text'", i, j + 1);
                }
                out.add(new Tok(T.NAME, word, i, j, null));
                i = j;
            } else {
                String three = i + 2 < n ? s.substring(i, i + 3) : "";
                String two = i + 1 < n ? s.substring(i, i + 2) : "";
                if (OPS3.contains(three)) {
                    out.add(new Tok(T.OP, three, i, i + 3, null));
                    i += 3;
                } else if (OPS2.contains(two)) {
                    out.add(new Tok(T.OP, two, i, i + 2, null));
                    i += 2;
                } else if ("+-*/%<>=()[],.{}:;@&|^~!?".indexOf(c) >= 0) {
                    out.add(new Tok(T.OP, String.valueOf(c), i, i + 1, null));
                    i++;
                } else {
                    throw new DelException(CODE, "unexpected character '" + c + "'", null, i, i + 1);
                }
            }
        }
        out.add(new Tok(T.EOF, "", n, n, null));
        return out;
    }

    private static int number(String s, int i, List<Tok> out) {
        int n = s.length();
        int j = i;
        boolean integral = true;
        while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '_')) {
            j++;
        }
        if (j < n && s.charAt(j) == '.') {
            integral = false;
            j++;
            while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                j++;
            }
        }
        if (j < n && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
            int k = j + 1;
            if (k < n && (s.charAt(k) == '+' || s.charAt(k) == '-')) {
                k++;
            }
            if (k < n && Character.isDigit(s.charAt(k))) {
                integral = false;
                j = k;
                while (j < n && Character.isDigit(s.charAt(j))) {
                    j++;
                }
            }
        }
        if (j < n && (Character.isLetter(s.charAt(j)) || s.charAt(j) == '_')) {
            throw new DelException(CODE, "'" + s.substring(i, j + 1) + "' is not a number", null, i, j + 1);
        }
        String text = s.substring(i, j);
        if (text.startsWith("_") || text.endsWith("_") || text.contains("__") || text.contains("_.") || text.contains("._")) {
            throw new DelException(CODE, "'" + text + "' is not a number: underscores go between digits (1_000_000)", null, i, j);
        }
        double v;
        try {
            v = Double.parseDouble(text.replace("_", ""));
        } catch (NumberFormatException e) {
            throw new DelException(CODE, "'" + text + "' is not a number", null, i, j);
        }
        out.add(new Tok(T.NUM, text, i, j, new Object[] {v, integral}));
        return j;
    }

    private static int string(String s, int i, List<Tok> out) {
        int n = s.length();
        char q = s.charAt(i);
        if (i + 2 < n && s.charAt(i + 1) == q && s.charAt(i + 2) == q) {
            throw new DelException(CODE, "triple-quoted strings are not DEL", "write a plain 'text' on one line", i, i + 3);
        }
        StringBuilder sb = new StringBuilder();
        int j = i + 1;
        while (j < n && s.charAt(j) != q) {
            char c = s.charAt(j);
            if (c == '\n') {
                throw new DelException(CODE, "the text starting here is not closed on its line", "close it with " + q, i, j);
            }
            if (c == '\\' && j + 1 < n) {
                char e = s.charAt(j + 1);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '\\', '\'', '"' -> sb.append(e);
                    default -> sb.append('\\').append(e);                    // as Python keeps an unknown escape
                }
                j += 2;
                continue;
            }
            sb.append(c);
            j++;
        }
        if (j >= n) {
            throw new DelException(CODE, "the text starting here is not closed", "close it with " + q, i, n);
        }
        out.add(new Tok(T.STR, s.substring(i, j + 1), i, j + 1, sb.toString()));
        return j + 1;
    }

    // ---- parser ------------------------------------------------------------------------------------------------------

    private Tok peek() {
        return toks.get(p);
    }

    private Tok peek(int ahead) {
        return toks.get(Math.min(p + ahead, toks.size() - 1));
    }

    private boolean isOp(String text) {
        Tok t = peek();
        return t.type() == T.OP && t.text().equals(text);
    }

    private boolean isWord(String text) {
        Tok t = peek();
        return t.type() == T.NAME && t.text().equals(text);
    }

    private boolean op(String text) {
        if (isOp(text)) {
            p++;
            return true;
        }
        return false;
    }

    private boolean word(String text) {
        if (isWord(text)) {
            p++;
            return true;
        }
        return false;
    }

    private int lastEnd() {
        return p == 0 ? 0 : toks.get(p - 1).end();
    }

    private Tok expect(String text, String what) {
        Tok t = peek();
        if (t.type() == T.OP && t.text().equals(text)) {
            p++;
            return t;
        }
        if (t.type() == T.EOF) {
            throw new DelException(CODE, "expected '" + text + "' " + what + " but the expression ends", "add '" + text + "'", t.start(), t.end());
        }
        throw new DelException(CODE, "expected '" + text + "' " + what + " but found '" + t.text() + "'", null, t.start(), t.end());
    }

    DelAst expr() {
        if (++depth > MAX_DEPTH) {
            throw new DelException(CODE, "the expression nests deeper than " + MAX_DEPTH + " levels", "split it into measures that use each other", peek().start(), peek().end());
        }
        try {
            int start = peek().start();
            DelAst body = or();
            if (word("if")) {
                DelAst test = or();
                if (!word("else")) {
                    Tok t = peek();
                    throw new DelException(CODE, "a conditional needs 'else': a if condition else b", "add else and the value when the condition is False", t.start(), t.end());
                }
                DelAst orElse = expr();
                return new DelAst.IfExp(body, test, orElse, start, orElse.end());
            }
            if (isOp("?")) {
                throw new DelException(CODE, "'c ? a : b' is the old syntax", "write: a if c else b", peek().start(), peek().end());
            }
            if (isOp(":=")) {
                throw new DelException(CODE, "assignment (:=) is not DEL: an expression has no variables", "write the expression where its value is needed, or make it a measure", peek().start(),
                        peek().end());
            }
            if (isWord("for")) {
                throw refusedWord(peek());
            }
            return body;
        } finally {
            depth--;
        }
    }

    private DelAst or() {
        int start = peek().start();
        DelAst first = and();
        if (!isWord("or") && !isOp("||")) {
            return first;
        }
        List<DelAst> values = new ArrayList<>(List.of(first));
        while (word("or") || oldOp("||", "or")) {
            values.add(and());
        }
        return new DelAst.BoolOp("or", values, start, lastEnd());
    }

    private DelAst and() {
        int start = peek().start();
        DelAst first = not();
        if (!isWord("and") && !isOp("&&")) {
            return first;
        }
        List<DelAst> values = new ArrayList<>(List.of(first));
        while (word("and") || oldOp("&&", "and")) {
            values.add(not());
        }
        return new DelAst.BoolOp("and", values, start, lastEnd());
    }

    /** An operator of the old syntax: refused with the DEL word. */
    private boolean oldOp(String old, String del) {
        if (isOp(old)) {
            throw new DelException(CODE, "'" + old + "' is the old syntax", "write '" + del + "'", peek().start(), peek().end());
        }
        return false;
    }

    private DelAst not() {
        Tok t = peek();
        if (word("not")) {
            DelAst operand = not();
            return new DelAst.Unary("not", operand, t.start(), operand.end());
        }
        if (isOp("!")) {
            throw new DelException(CODE, "'!' is the old syntax", "write 'not'", t.start(), t.end());
        }
        return comparison();
    }

    private DelAst comparison() {
        int start = peek().start();
        DelAst first = arith();
        List<String> ops = new ArrayList<>();
        List<DelAst> rest = new ArrayList<>();
        while (true) {
            String o = compOp();
            if (o == null) {
                break;
            }
            ops.add(o);
            rest.add(arith());
        }
        if (isOp("=")) {
            throw new DelException(CODE, "'=' is assignment, which DEL does not have", "for equality write ==", peek().start(), peek().end());
        }
        return ops.isEmpty() ? first : new DelAst.Compare(first, ops, rest, start, lastEnd());
    }

    private String compOp() {
        for (String o : new String[] {"==", "!=", "<=", ">=", "<", ">"}) {
            if (op(o)) {
                return o;
            }
        }
        if (word("in")) {
            return "in";
        }
        if (isWord("not") && peek(1).type() == T.NAME && peek(1).text().equals("in")) {
            p += 2;
            return "not in";
        }
        if (word("is")) {
            return word("not") ? "is not" : "is";
        }
        return null;
    }

    private DelAst arith() {
        int start = peek().start();
        DelAst l = term();
        while (true) {
            String o = op("+") ? "+" : op("-") ? "-" : null;
            if (o == null) {
                return l;
            }
            DelAst r = term();
            l = new DelAst.Binary(o, l, r, start, r.end());
        }
    }

    private DelAst term() {
        int start = peek().start();
        DelAst l = factor();
        while (true) {
            String o = op("*") ? "*" : op("//") ? "//" : op("/") ? "/" : op("%") ? "%" : null;
            if (o == null) {
                if (isOp("@") || isOp("&") || isOp("|") || isOp("^") || isOp("<<") || isOp(">>")) {
                    throw new DelException(CODE, "the operator '" + peek().text() + "' is not DEL", "use and / or for conditions; arithmetic is + - * / // % **", peek().start(),
                            peek().end());
                }
                return l;
            }
            DelAst r = factor();
            l = new DelAst.Binary(o, l, r, start, r.end());
        }
    }

    private DelAst factor() {
        Tok t = peek();
        if (op("-") || op("+")) {
            DelAst operand = factor();
            return new DelAst.Unary(t.text(), operand, t.start(), operand.end());
        }
        if (isOp("~")) {
            throw new DelException(CODE, "the operator '~' is not DEL", "write not for a condition, - for a negative number", t.start(), t.end());
        }
        return power();
    }

    private DelAst power() {
        DelAst base = primary();
        if (op("**")) {
            DelAst exp = factor();
            return new DelAst.Binary("**", base, exp, base.start(), exp.end());
        }
        return base;
    }

    private DelAst primary() {
        DelAst a = atom();
        while (true) {
            Tok t = peek();
            if (isOp("[")) {
                throw new DelException(CODE, "subscripts and slices (x[...]) are not DEL", "name the column you need: alias.column", t.start(), t.end());
            }
            if (isOp(".")) {
                Tok name = peek(1);
                if (name.type() == T.NAME && peek(2).type() == T.OP && peek(2).text().equals("(")) {
                    throw new DelException(CODE, "method calls (x." + name.text() + "(...)) are not DEL", a instanceof DelAst.Field f
                            ? "call the function on it: " + name.text() + "(" + f.text() + ")" : "call the catalogue function on the value", t.start(), name.end());
                }
                throw new DelException(CODE, "attribute access is only alias.column[.datepart], user.x, param.x or fn.x(...)", null, t.start(), name.end());
            }
            if (isOp("(") && !(a instanceof DelAst.Field)) {
                throw new DelException(CODE, "only a catalogue function can be called", null, t.start(), t.end());
            }
            return a;
        }
    }

    private DelAst atom() {
        Tok t = peek();
        switch (t.type()) {
            case NUM -> {
                p++;
                Object[] v = (Object[]) t.value();
                return new DelAst.Num(t.text(), (Double) v[0], (Boolean) v[1], t.start(), t.end());
            }
            case STR -> {
                p++;
                StringBuilder value = new StringBuilder((String) t.value());
                int end = t.end();
                while (peek().type() == T.STR) {                            // 'a' 'b' is one text in Python
                    value.append((String) peek().value());
                    end = peek().end();
                    p++;
                }
                return new DelAst.Str(value.toString(), t.start(), end);
            }
            case NAME -> {
                return name(t);
            }
            case EOF -> throw new DelException(CODE, "the expression ends early: a value is missing", null, t.start(), t.end());
            default -> { }
        }
        if (op("(")) {
            DelAst inner = expr();
            if (isOp(",")) {
                throw new DelException(CODE, "tuples are not DEL", "a list is written [a, b]", peek().start(), peek().end());
            }
            if (isWord("for")) {
                throw refusedWord(peek());
            }
            expect(")", "to close the bracket");
            return inner;
        }
        if (isOp("[")) {
            return list();
        }
        if (isOp("{")) {
            throw new DelException(CODE, "dict and set literals are not DEL", "a list for in is written [a, b]", t.start(), t.end());
        }
        if (isOp("*") || isOp("**")) {
            throw new DelException(CODE, "star arguments (*args, **kwargs) are not DEL", "write each argument", t.start(), t.end());
        }
        throw unexpected(t);
    }

    private DelAst list() {
        Tok open = peek();
        p++;
        List<DelAst> items = new ArrayList<>();
        if (!isOp("]")) {
            do {
                if (isOp("]")) {
                    break;                                                      // a trailing comma
                }
                items.add(expr());
                if (isWord("for")) {
                    throw refusedWord(peek());
                }
                if (isOp(":")) {
                    throw new DelException(CODE, "slices are not DEL", null, peek().start(), peek().end());
                }
            } while (op(","));
        }
        Tok close = expect("]", "to close the list");
        return new DelAst.ListLit(items, open.start(), close.end());
    }

    private DelAst name(Tok t) {
        String w = t.text();
        if (REFUSED_WORDS.containsKey(w)) {
            throw refusedWord(t);
        }
        if (OLD_WORDS.containsKey(w)) {
            throw new DelException(CODE, "'" + w + "' is the old syntax", "write " + OLD_WORDS.get(w), t.start(), t.end());
        }
        switch (w) {
            case "True", "False" -> {
                p++;
                return new DelAst.Bool("True".equals(w), t.start(), t.end());
            }
            case "None" -> {
                p++;
                return new DelAst.NoneLit(t.start(), t.end());
            }
            case "ALL" -> {
                p++;
                return new DelAst.AllLit(t.start(), t.end());
            }
            default -> { }
        }
        if (KEYWORDS.contains(w)) {
            throw new DelException(CODE, "'" + w + "' cannot start a value", "if c then a else b is written: a if c else b", t.start(), t.end());
        }
        p++;
        List<String> parts = new ArrayList<>(List.of(w));
        int end = t.end();
        while (isOp(".") && peek(1).type() == T.NAME && !(peek(2).type() == T.OP && peek(2).text().equals("("))) {
            Tok part = peek(1);
            if (KEYWORDS.contains(part.text()) || REFUSED_WORDS.containsKey(part.text())) {
                break;
            }
            p += 2;
            parts.add(part.text());
            end = part.end();
        }
        if (parts.size() == 1 && "fn".equals(w) && isOp(".") && peek(1).type() == T.NAME && peek(2).type() == T.OP && peek(2).text().equals("(")) {
            parts.add(peek(1).text());                                          // fn.name(...): a user function (wave B)
            p += 2;
        }
        if (isOp("(")) {
            if (parts.size() > 1 && !(parts.size() == 2 && "fn".equals(parts.get(0)))) {
                throw new DelException(CODE, "method calls (" + String.join(".", parts) + "(...)) are not DEL",
                        "call the function on the field: " + parts.get(parts.size() - 1) + "(" + String.join(".", parts.subList(0, parts.size() - 1)) + ")", t.start(), end);
            }
            return call(String.join(".", parts), t.start());
        }
        return new DelAst.Field(parts, t.start(), end);
    }

    private DelAst call(String name, int start) {
        p++;                                                                    // (
        List<DelAst> args = new ArrayList<>();
        List<DelAst.Keyword> keywords = new ArrayList<>();
        if (!isOp(")")) {
            do {
                if (isOp(")")) {
                    break;                                                      // a trailing comma
                }
                Tok t = peek();
                if (isOp("*") || isOp("**")) {
                    throw new DelException(CODE, "star arguments (*args, **kwargs) are not DEL", "write each argument", t.start(), t.end());
                }
                if (t.type() == T.NAME && peek(1).type() == T.OP && peek(1).text().equals("=")) {
                    p += 2;
                    DelAst value = expr();
                    if (keywords.stream().anyMatch(k -> k.name().equals(t.text()))) {
                        throw new DelException(CODE, "the keyword " + t.text() + "= is given twice", null, t.start(), value.end());
                    }
                    keywords.add(new DelAst.Keyword(t.text(), value, t.start(), value.end()));
                } else {
                    DelAst a = expr();
                    if (isWord("for")) {
                        throw refusedWord(peek());
                    }
                    if (!keywords.isEmpty()) {
                        throw new DelException(CODE, "a positional argument follows a keyword argument", "put the positional arguments first", a.start(), a.end());
                    }
                    args.add(a);
                }
            } while (op(","));
        }
        Tok close = expect(")", "to close the call of " + name + "()");
        return new DelAst.Call(name, args, keywords, start, close.end());
    }

    private DelException refusedWord(Tok t) {
        String[] r = REFUSED_WORDS.get(t.text());
        return new DelException(CODE, r[0], r[1], t.start(), t.end());
    }

    private DelException unexpected(Tok t) {
        if (t.type() == T.EOF) {
            return new DelException(CODE, "the expression ends early: a value is missing", null, t.start(), t.end());
        }
        if (t.type() == T.OP && "?".equals(t.text())) {
            return new DelException(CODE, "'c ? a : b' is the old syntax", "write: a if c else b", t.start(), t.end());
        }
        if (t.type() == T.OP && ("&&".equals(t.text()) || "||".equals(t.text()))) {
            return new DelException(CODE, "'" + t.text() + "' is the old syntax", "write '" + ("&&".equals(t.text()) ? "and" : "or") + "'", t.start(), t.end());
        }
        if (t.type() == T.OP && "=".equals(t.text())) {
            return new DelException(CODE, "'=' is assignment, which DEL does not have", "for equality write ==", t.start(), t.end());
        }
        if (t.type() == T.OP && ",".equals(t.text())) {
            return new DelException(CODE, "tuples are not DEL", "a list is written [a, b]", t.start(), t.end());
        }
        return new DelException(CODE, "unexpected '" + t.text() + "'", null, t.start(), t.end());
    }

    /** The text of a span of the source (for messages). */
    String text(DelAst n) {
        return src.substring(n.start(), Math.min(n.end(), src.length()));
    }
}
