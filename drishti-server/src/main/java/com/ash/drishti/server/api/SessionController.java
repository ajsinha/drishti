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
import com.ash.drishti.identity.SessionStore;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.UserView;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Console sign-in sessions, for the console's service identity only. The console opens a session after a verified
 * sign-in, asks on each request (cached for its {@code auth.recheck_seconds}) whether it still stands and who the user is
 * now, and ends it at sign-out. A session of a disabled or deleted user does not stand; disabling, deleting or resetting
 * the password of a user ends all their sessions (so enabling them again does not revive one).
 */
@RestController
@RequestMapping("/api/v1/auth/sessions")
public class SessionController {

    /** @param username the user just verified @param seconds how long the session lasts (the console's cookie lifetime) */
    public record NewSession(String username, Long seconds) {}

    /** @param id the session id (in this answer to open only) @param expiresAt when it ends @param user the user now */
    public record SessionView(String id, Instant expiresAt, UserView user) {}

    private final SessionStore sessions;
    private final UserService users;
    private final Entitlements entitlements;

    public SessionController(SessionStore sessions, UserService users, Entitlements entitlements) {
        this.sessions = sessions;
        this.users = users;
        this.entitlements = entitlements;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView open(@RequestBody NewSession req, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireService(p);
        User u = users.require(req.username());
        if (!u.enabled()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "the account '" + u.username() + "' is disabled");
        }
        SessionStore.Session s = sessions.open(u.username(), Duration.ofSeconds(req.seconds() == null ? 36_000 : req.seconds()));
        return new SessionView(s.id(), s.expiresAt(), UserView.of(u, Instant.now()));
    }

    /** The session and its user as they are now; 401 when it has ended, expired, or its user is disabled or deleted. */
    @GetMapping("/{id}")
    public SessionView check(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireService(p);
        SessionStore.Session s = sessions.find(id).orElseThrow(SessionController::ended);
        User u = users.find(s.user()).filter(User::enabled).orElseThrow(SessionController::ended);
        return new SessionView(null, s.expiresAt(), UserView.of(u, Instant.now()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void end(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireService(p);
        sessions.end(id);
    }

    private static DrishtiException ended() {
        return new DrishtiException(ErrorCode.UNAUTHENTICATED, "session ended: sign in again");
    }
}
