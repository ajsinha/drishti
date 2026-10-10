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

import java.time.Duration;
import java.time.Instant;

/**
 * A certificate the connector was configured with (its client certificate chain, its CA bundle; not the JVM's own
 * authorities): where it came from and when it expires.
 *
 * @param source  the setting it was read from, e.g. {@code tls.cert-file}
 * @param subject its subject name
 * @param notBefore the start of its validity
 * @param notAfter the end of its validity
 */
public record CertInfo(String source, String subject, Instant notBefore, Instant notAfter) {

    /** Days until it expires; negative once it has. */
    public long daysLeft(Instant now) {
        return Math.floorDiv(Duration.between(now, notAfter).getSeconds(), 86_400L);
    }

    public boolean expired(Instant now) {
        return notAfter.isBefore(now);
    }

    public boolean notYetValid(Instant now) {
        return notBefore.isAfter(now);
    }
}
