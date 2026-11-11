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
package com.ash.drishti.iceberg.io;

/**
 * A failure's reason in words, for a one-line message: the first message in its cause chain that says something, else what its innermost
 * cause is (QA4-07: a REST catalog that could not be reached read {@code …: null (ClosedChannelException …)}, as the exception has no message).
 */
public final class Reasons {

    private Reasons() {}

    /** The first meaningful message of {@code e} or its causes, else the innermost cause in words, never "null". */
    public static String of(Throwable e) {
        if (e == null) {
            return "unknown";
        }
        Throwable root = e;
        for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
            root = c;
            String m = c.getMessage();
            if (meaningful(m) && !(c.getCause() != null && m.equals(c.getCause().toString()))) {   // not the cause's name, which wrapping gives
                return m.strip();
            }
        }
        return words(e, root);
    }

    private static boolean meaningful(String m) {
        return m != null && !m.isBlank() && !m.strip().equals("null") && !m.strip().endsWith(": null");
    }

    /** A cause without a message, in words with its type. */
    private static String words(Throwable e, Throwable root) {
        String type = root.getClass().getSimpleName();
        for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof java.net.ConnectException) {
                return "the connection was refused or closed before an answer (" + type + ")";
            }
            if (c instanceof java.net.http.HttpTimeoutException || c instanceof java.net.SocketTimeoutException) {
                return "no answer in time (" + type + ")";
            }
            if (c instanceof java.net.UnknownHostException) {
                return "the host name is not known (" + type + ")";
            }
        }
        if (root instanceof java.nio.channels.ClosedChannelException || root instanceof java.io.EOFException) {
            return "the connection was closed before an answer (" + type + ")";
        }
        return type;
    }
}
