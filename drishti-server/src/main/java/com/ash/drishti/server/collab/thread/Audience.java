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
package com.ash.drishti.server.collab.thread;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.Recipient;
import com.ash.drishti.server.collab.DirectoryService;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.collab.ShareService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who a comment's {@code @mentions} reach. A name resolves to a user the author can see in the directory, else to a mentionable role
 * (the roles are the groups); a name that is neither stays plain text. Each person is then checked with
 * {@link Entitlements#mayReach} for the thread's kind and gate kind: <strong>someone who cannot see the entity is never notified</strong>,
 * not even with "someone mentioned you" (cross-pack rule). Under {@code undeliverable: tell} the author is told who was skipped and why.
 */
public final class Audience {

    private static final Pattern MENTION = Pattern.compile("(?<![A-Za-z0-9._-])@([A-Za-z0-9_](?:[A-Za-z0-9._-]*[A-Za-z0-9_])?)");

    /** The resolved mentions: {@code targets} (user:x, role:x, in order), who is reached, and who was skipped. */
    record Mentioned(Set<String> targets, List<String> reached, List<ShareService.Skipped> skipped) {
        static final Mentioned NONE = new Mentioned(Set.of(), List.of(), List.of());
    }

    private final UserService users;
    private final DirectoryService directory;
    private final Principals principals;
    private final Entitlements entitlements;
    private final CollabProperties props;

    public Audience(UserService users, DirectoryService directory, Principals principals, Entitlements entitlements, CollabProperties props) {
        this.users = users;
        this.directory = directory;
        this.principals = principals;
        this.entitlements = entitlements;
        this.props = props;
    }

    /** The names a body mentions, in order, without duplicates. */
    static Set<String> names(String body) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = MENTION.matcher(body);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    Mentioned resolve(Principal author, String body, String kind, String gate) {
        Set<String> names = names(body);
        if (names.isEmpty()) {
            return Mentioned.NONE;
        }
        Map<String, User> visible = new LinkedHashMap<>();
        directory.visibleUsers(author).forEach(u -> visible.put(u.username(), u));
        Set<String> targets = new LinkedHashSet<>();
        Map<String, String> people = new LinkedHashMap<>();                     // person -> as addressed
        for (String n : names) {
            if (visible.containsKey(n)) {
                targets.add("user:" + n);
                if (!n.equals(author.user())) {
                    people.putIfAbsent(n, "user:" + n);
                }
            } else if (users.knownRoles().contains(n) && props.mentionable(n)) {
                List<User> members = directory.members(n);
                if (members.size() > props.maxGroupSize()) {
                    throw new DrishtiException(ErrorCode.BAD_RECIPIENTS, "role '" + n + "' has " + members.size() + " people; the limit is "
                            + props.maxGroupSize());
                }
                targets.add("role:" + n);
                members.stream().map(User::username).filter(m -> !m.equals(author.user())).forEach(m -> people.putIfAbsent(m, "role:" + n));
            }
        }
        if (people.size() > props.share().maxExpanded()) {
            throw new DrishtiException(ErrorCode.BAD_RECIPIENTS, people.size() + " people after roles are expanded; the limit is "
                    + props.share().maxExpanded());
        }
        List<String> reached = new ArrayList<>();
        List<ShareService.Skipped> skipped = new ArrayList<>();
        Map<String, Integer> hidden = new LinkedHashMap<>();
        people.forEach((person, how) -> {
            String state = state(principals.of(person), kind, gate);
            if (Recipient.NOTIFIED.equals(state)) {
                reached.add(person);
            } else if (visible.containsKey(person)) {
                skipped.add(new ShareService.Skipped(person, ShareService.reason(state, kind)));
            } else {
                hidden.merge(how, 1, Integer::sum);
            }
        });
        hidden.forEach((how, n) -> skipped.add(new ShareService.Skipped(how, "some members may not open " + kind + " views")));
        return new Mentioned(targets, reached, props.share().tell() ? skipped : List.of());
    }

    /** {@code notified} when the person may be reached for the kind (and the gate kind), else the recipient state that says why not. */
    String state(Principal person, String kind, String gate) {
        String state = Recipient.NOTIFIED;
        for (String k : gate == null ? List.of(kind) : List.of(kind, gate)) {
            String rs = entitlements.reachState(person, k);
            if ("no-access".equals(rs)) {
                return Recipient.NO_ACCESS;
            }
            if ("no-pack".equals(rs)) {
                state = Recipient.NO_PACK;
            }
        }
        return state;
    }

    boolean reaches(String user, String kind, String gate) {
        return Recipient.NOTIFIED.equals(state(principals.of(user), kind, gate));
    }
}
