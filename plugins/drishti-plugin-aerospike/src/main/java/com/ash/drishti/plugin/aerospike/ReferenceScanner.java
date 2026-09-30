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
package com.ash.drishti.plugin.aerospike;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds the identifiers a document refers to, for reverse lookups: every string value that looks like an
 * identifier (letters, digits and dashes, with at least one dash or digit), anywhere in the document. Streaming,
 * so a large document is not materialised twice.
 */
final class ReferenceScanner {

    private static final JsonFactory JSON = new JsonFactory();
    private static final Pattern ID = Pattern.compile("[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+");

    private ReferenceScanner() {
    }

    static Set<String> referencedIds(String json) {
        Set<String> out = new HashSet<>();
        try (JsonParser p = JSON.createParser(json)) {
            JsonToken t;
            while ((t = p.nextToken()) != null) {
                if (t == JsonToken.VALUE_STRING) {
                    String v = p.getText();
                    if (v.length() <= 64 && ID.matcher(v).matches()) {
                        out.add(v);
                    }
                }
            }
        } catch (IOException e) {
            // an unreadable document refers to nothing
        }
        return out;
    }
}
