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
package com.ash.drishti.server.api;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.PreferenceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * What a user typed before, and the short words they made for longer commands. Both are kept with their saved
 * documents (namespace {@code commands}), so they follow the user from browser to browser. History holds the newest
 * {@value #MAX_HISTORY} distinct commands; an alias ({@code MYBOOK} for {@code BOOK BOOK-RATES-1}) is expanded before a
 * command is read, with whatever follows it appended. Changes for one user are serialised; history is written off the
 * request thread, so a command never waits for it.
 */
public final class CommandMemory implements AutoCloseable {

    static final String NS = "commands";
    static final int MAX_HISTORY = 50;
    static final int MAX_ALIASES = 50;
    private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_-]{0,15}");
    private final PreferenceStore store;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newVirtualThreadPerTaskExecutor();

    public CommandMemory(PreferenceStore store) {
        this.store = store;
    }

    public List<String> history(String user) {
        List<String> out = new ArrayList<>();
        store.get(user, NS, "history").ifPresent(n -> n.path("commands").forEach(c -> out.add(c.asText())));
        return out;
    }

    /** Adds a command to the front of the user's history (once), in the background. */
    public void remember(String user, String text) {
        String t = text == null ? "" : text.replaceAll("(?i)<\\s*GO\\s*>", " ").trim();
        if (user == null || user.isBlank() || t.isEmpty() || t.length() > 300) {
            return;
        }
        writer.execute(() -> withLock(user, () -> {
            List<String> h = new ArrayList<>(history(user));
            h.removeIf(x -> x.equalsIgnoreCase(t));
            h.add(0, t);
            ObjectNode node = json.createObjectNode();
            ArrayNode arr = node.putArray("commands");
            h.stream().limit(MAX_HISTORY).forEach(arr::add);
            store.put(user, NS, "history", node);
            return null;
        }));
    }

    public Map<String, String> aliases(String user) {
        Map<String, String> out = new TreeMap<>();
        store.get(user, NS, "aliases").ifPresent(n -> n.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText())));
        return out;
    }

    /**
     * Replaces the user's aliases.
     *
     * @param taken names that are not free (mnemonics, pack codes)
     */
    public Map<String, String> setAliases(String user, Map<String, String> wanted, Predicate<String> taken) {
        Map<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : (wanted == null ? Map.<String, String>of() : wanted).entrySet()) {
            String name = e.getKey() == null ? "" : e.getKey().trim().toUpperCase(Locale.ROOT);
            String body = e.getValue() == null ? "" : e.getValue().replaceAll("(?i)<\\s*GO\\s*>", " ").trim();
            if (!NAME.matcher(name).matches()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "alias names are 1-16 of A-Z, 0-9, _ and -, starting with a letter: '" + e.getKey() + "'");
            }
            if (taken.test(name)) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "'" + name + "' is already a mnemonic or a pack code; choose another name");
            }
            if (body.isEmpty() || body.length() > 300) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "alias " + name + " needs a command of 1-300 characters");
            }
            if (body.toUpperCase(Locale.ROOT).split("\\s+")[0].equals(name)) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "alias " + name + " may not start with itself");
            }
            clean.put(name, body);
        }
        if (clean.size() > MAX_ALIASES) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "at most " + MAX_ALIASES + " aliases");
        }
        return withLock(user, () -> {
            ObjectNode node = json.createObjectNode();
            clean.forEach(node::put);
            store.put(user, NS, "aliases", node);
            return aliases(user);
        });
    }

    /** The command with a leading alias expanded once ({@code MYBOOK F3} becomes {@code BOOK BOOK-RATES-1 F3}). */
    public String expand(String user, String text) {
        if (user == null || user.isBlank() || text == null) {
            return text;
        }
        String t = text.trim();
        int sp = t.indexOf(' ');
        String head = (sp < 0 ? t : t.substring(0, sp)).toUpperCase(Locale.ROOT);
        if (!NAME.matcher(head).matches()) {
            return text;
        }
        JsonNode a = store.get(user, NS, "aliases").map(n -> n.get(head)).orElse(null);
        return a == null ? text : a.asText() + (sp < 0 ? "" : t.substring(sp));
    }

    private <T> T withLock(String user, java.util.function.Supplier<T> body) {
        ReentrantLock lock = locks.computeIfAbsent(user, u -> new ReentrantLock());
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        writer.close();
    }
}
