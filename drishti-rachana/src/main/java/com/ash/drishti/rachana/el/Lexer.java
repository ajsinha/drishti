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
package com.ash.drishti.rachana.el;

import com.ash.drishti.rachana.el.Token.Type;
import java.util.ArrayList;
import java.util.List;

/** Splits a Rachana-EL expression into tokens. */
final class Lexer {

    private Lexer() {}

    static List<Token> tokens(String src) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
                int j = i;
                while (j < n && (Character.isDigit(src.charAt(j)) || src.charAt(j) == '.'
                        || src.charAt(j) == 'e' || src.charAt(j) == 'E'
                        || ((src.charAt(j) == '-' || src.charAt(j) == '+') && (src.charAt(j - 1) == 'e' || src.charAt(j - 1) == 'E')))) {
                    j++;
                }
                out.add(new Token(Type.NUMBER, src.substring(i, j), i));
                i = j;
            } else if (c == '\'' || c == '"') {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (j < n && src.charAt(j) != c) {
                    if (src.charAt(j) == '\\' && j + 1 < n) {
                        j++;
                    }
                    sb.append(src.charAt(j++));
                }
                if (j >= n) {
                    throw new ElException("unterminated string", i);
                }
                out.add(new Token(Type.STRING, sb.toString(), i));
                i = j + 1;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(src.charAt(j)) || src.charAt(j) == '_')) {
                    j++;
                }
                out.add(new Token(Type.IDENT, src.substring(i, j), i));
                i = j;
            } else {
                String two = i + 1 < n ? src.substring(i, i + 2) : "";
                switch (two) {
                    case "&&", "||", "==", "!=", "<=", ">=" -> {
                        out.add(new Token(Type.OP, two, i));
                        i += 2;
                        continue;
                    }
                    case "[?" -> {
                        out.add(new Token(Type.FILTER, two, i));
                        i += 2;
                        continue;
                    }
                    default -> { }
                }
                if (c == '#' && src.startsWith("#index", i)) {
                    out.add(new Token(Type.HASH_INDEX, "#index", i));
                    i += 6;
                    continue;
                }
                Type t = switch (c) {
                    case '$' -> Type.DOLLAR;
                    case '@' -> Type.AT;
                    case '(' -> Type.LPAREN;
                    case ')' -> Type.RPAREN;
                    case '[' -> Type.LBRACKET;
                    case ']' -> Type.RBRACKET;
                    case '.' -> Type.DOT;
                    case ',' -> Type.COMMA;
                    case '?' -> Type.QUESTION;
                    case ':' -> Type.COLON;
                    case '+', '-', '*', '/', '%', '<', '>', '!' -> Type.OP;
                    default -> throw new ElException("unexpected character '" + c + "'", i);
                };
                out.add(new Token(t, String.valueOf(c), i));
                i++;
            }
        }
        out.add(new Token(Type.EOF, "", n));
        return out;
    }
}
