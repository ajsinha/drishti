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

/**
 * A lexical token.
 *
 * @param type the token type
 * @param text the source text (unquoted for strings)
 * @param pos 0-based offset in the expression
 */
record Token(Type type, String text, int pos) {

    enum Type { NUMBER, STRING, IDENT, DOLLAR, AT, HASH_INDEX, OP, LPAREN, RPAREN, LBRACKET, FILTER, RBRACKET, DOT, COMMA, QUESTION, COLON, EOF }

    boolean is(Type t, String s) {
        return type == t && text.equals(s);
    }
}
