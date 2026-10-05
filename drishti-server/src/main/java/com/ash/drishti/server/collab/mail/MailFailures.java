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
package com.ash.drishti.server.collab.mail;

import jakarta.mail.internet.AddressException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;

/** Reads a mail failure: is it permanent (an SMTP 5xx, a refused address; retrying cannot help) and what to say about it. */
final class MailFailures {

    private MailFailures() {}

    static boolean permanent(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof SMTPAddressFailedException a && a.getReturnCode() >= 500 && a.getReturnCode() < 600) {
                return true;
            }
            if (c instanceof SMTPSendFailedException s && s.getReturnCode() >= 500 && s.getReturnCode() < 600) {
                return true;
            }
            if (c instanceof AddressException) {
                return true;
            }
        }
        return false;
    }

    /** A short, single-line cause: the innermost message, which for SMTP carries the server's reply. */
    static String describe(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String m = root.getMessage() == null ? root.getClass().getSimpleName() : root.getClass().getSimpleName() + ": " + root.getMessage();
        return m.replaceAll("[\\r\\n]+", " ").strip();
    }
}
