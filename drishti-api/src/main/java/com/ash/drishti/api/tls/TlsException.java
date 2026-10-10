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
package com.ash.drishti.api.tls;

/**
 * A TLS setting that cannot be used: a file that is missing or unreadable, a wrong password, a key that does not belong to
 * its certificate, an unsupported format. The message names the setting, the file and the reason, so the operator reads
 * what to fix straight from the start-up error or the connector's health.
 */
public final class TlsException extends RuntimeException {

    public TlsException(String message) {
        super(message);
    }

    public TlsException(String message, Throwable cause) {
        super(message, cause);
    }
}
