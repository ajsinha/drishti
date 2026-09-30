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
package com.ash.drishti.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only audit of identity events (sign-ins, failures, lockouts, user changes). Each event is one
 * JSON line in the audit file; the most recent events are also kept in memory for the admin page.
 */
public final class AuditLog {

    /**
     * @param at when
     * @param actor who did it ({@code system} for the seeder)
     * @param action what ({@code login}, {@code login-failed}, {@code user-created}, ...)
     * @param subject the user it concerns
     * @param detail extra context, never a password
     */
    public record Event(Instant at, String actor, String action, String subject, String detail) {}

    private static final Logger LOG = LoggerFactory.getLogger(AuditLog.class);
    private static final int KEEP = 1000;
    private final Path file;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Deque<Event> recent = new ArrayDeque<>();

    public AuditLog(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public synchronized void record(String actor, String action, String subject, String detail) {
        Event e = new Event(Instant.now(), actor, action, subject, detail);
        recent.addFirst(e);
        while (recent.size() > KEEP) {
            recent.removeLast();
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.writeValueAsString(e) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ex) {
            LOG.error("audit write failed for {} {}", action, subject, ex);
        }
    }

    /** Newest first, at most {@code limit}, optionally only events about {@code subject}. */
    public synchronized List<Event> recent(int limit, String subject) {
        List<Event> out = new ArrayList<>();
        for (Event e : recent) {
            if (subject == null || subject.isBlank() || subject.equals(e.subject()) || subject.equals(e.actor())) {
                out.add(e);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }
}
