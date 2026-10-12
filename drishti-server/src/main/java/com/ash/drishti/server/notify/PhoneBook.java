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
package com.ash.drishti.server.notify;

import com.ash.drishti.identity.PreferenceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.regex.Pattern;

/** The mobile number a person gave for text messages, kept with their preferences (namespace {@code notify}); it is never in an alert, a mail or a log. */
public final class PhoneBook {

    static final String NS = "notify";
    static final String KEY = "me";
    private static final Pattern NUMBER = Pattern.compile("\\+?[0-9][0-9 ()-]{5,20}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PreferenceStore store;

    public PhoneBook(PreferenceStore store) {
        this.store = store;
    }

    public Optional<String> phone(String user) {
        return store.get(user, NS, KEY).map(n -> n.path("phone").asText("")).filter(s -> !s.isBlank());
    }

    /** Saves the number; blank removes it. Throws {@link IllegalArgumentException} for a text that is not a phone number. */
    public void set(String user, String phone) {
        String p = phone == null ? "" : phone.strip();
        if (p.isEmpty()) {
            store.delete(user, NS, KEY);
            return;
        }
        if (!NUMBER.matcher(p).matches()) {
            throw new IllegalArgumentException("a phone number is digits, with an optional leading + (and spaces, dashes or brackets)");
        }
        store.put(user, NS, KEY, JSON.createObjectNode().put("phone", p));
    }

    /** The number with all but its last two digits hidden, for showing back to its owner. */
    public static String hide(String phone) {
        String d = phone.replaceAll("[^0-9]", "");
        return d.length() <= 2 ? "**" : "*".repeat(d.length() - 2) + d.substring(d.length() - 2);
    }
}
