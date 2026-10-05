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
package com.ash.drishti.server.collab;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The people a sender may address: the directory Drishti already has (users, and roles as the groups it feeds). With
 * {@code directory.scope: shared-packs} (default) a user is visible only when they share at least one assigned pack with the
 * caller, so the picker never teaches a sender who uses packs they cannot see. Only enabled users are listed; email addresses are
 * never returned (only whether one exists).
 */
public final class DirectoryService {

    /** One picker entry: a {@code user} or a {@code role}; {@code reach} only under {@code undeliverable: tell} and a {@code kind}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(String type, String name, String displayName, String desk, Boolean email, Integer size, Boolean reach) {}

    private final UserService users;
    private final PackAccess packs;
    private final Entitlements entitlements;
    private final Principals principals;
    private final CollabProperties props;
    private final RateLimits limits;

    public DirectoryService(UserService users, PackAccess packs, Entitlements entitlements, Principals principals, CollabProperties props,
            RateLimits limits) {
        this.users = users;
        this.packs = packs;
        this.entitlements = entitlements;
        this.principals = principals;
        this.props = props;
        this.limits = limits;
    }

    /** Enabled users the caller may see, other than the caller. */
    public List<User> visibleUsers(Principal caller) {
        Set<String> mine = new HashSet<>(packs.assigned(caller.user()));
        List<User> out = new ArrayList<>();
        for (User u : users.list(null)) {
            if (!u.enabled() || u.username().equals(caller.user())) {
                continue;
            }
            if (props.directory().all() || packs.assigned(u.username()).stream().anyMatch(mine::contains)) {
                out.add(u);
            }
        }
        return out;
    }

    /** The user when the caller may see them (an unknown user and one outside the caller's scope look the same). */
    public Optional<User> visible(Principal caller, String username) {
        return visibleUsers(caller).stream().filter(u -> u.username().equals(username)).findFirst();
    }

    /** Every enabled member of a role, whoever they are (used to expand a group; the caller sees only counts). */
    public List<User> members(String role) {
        return users.list(null).stream().filter(u -> u.enabled() && u.roles().contains(role)).toList();
    }

    public List<Entry> search(Principal caller, String q, Integer limit, String kind) {
        String text = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (text.length() < props.directory().minQuery()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "q needs at least " + props.directory().minQuery() + " characters");
        }
        limits.hit("directory", caller.user(), props.limits().directoryPerMinute(), Duration.ofMinutes(1), "directory searches");
        int max = limit == null ? props.directory().limit() : Math.max(1, Math.min(limit, props.directory().limit()));
        List<User> visible = visibleUsers(caller);
        boolean tell = kind != null && !kind.isBlank() && props.share().tell();
        List<Entry> out = new ArrayList<>();
        visible.stream().filter(u -> u.username().toLowerCase(Locale.ROOT).contains(text)
                || nz(u.displayName()).toLowerCase(Locale.ROOT).contains(text)).sorted(Comparator.comparing(User::username)).limit(max)
                .forEach(u -> out.add(new Entry("user", u.username(), u.displayName(), u.desk(), !nz(u.email()).isBlank(), null,
                        tell ? entitlements.mayReach(principals.of(u.username()), kind) : null)));
        if (out.size() < max) {
            users.knownRoles().stream().sorted().filter(r -> r.toLowerCase(Locale.ROOT).contains(text) && props.mentionable(r)).forEach(r -> {
                long size = visible.stream().filter(u -> u.roles().contains(r)).count();
                if (size > 0 && out.size() < max) {
                    out.add(new Entry("role", r, null, null, null, (int) size, null));
                }
            });
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
