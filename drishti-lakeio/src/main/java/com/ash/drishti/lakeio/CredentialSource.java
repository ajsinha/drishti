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
package com.ash.drishti.lakeio;

/**
 * Temporary S3 credentials that renew themselves: a catalog's credential vending (Unity Catalog's temporary table credentials) hands a
 * reader short-lived keys for one table, and this source fetches new ones before the old expire. The S3 client asks {@link #current()} on
 * every request, so an implementation answers from memory and renews only when the keys are near their expiry. Thread-safe.
 */
@FunctionalInterface
public interface CredentialSource {

    /** One set of temporary keys. */
    record Key(String accessKey, String secretKey, String sessionToken) {

        @Override
        public String toString() {                               // never the secret
            return "Key[" + (accessKey.length() > 4 ? accessKey.substring(0, 4) + "..." : "...") + "]";
        }
    }

    /**
     * The keys to sign the next request with.
     *
     * @throws IllegalStateException when no valid keys can be had (the catalog refused, in words)
     */
    Key current();
}
