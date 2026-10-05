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

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.UserView;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in verification (for the console), self service ({@code /auth/me}, password change) and user
 * administration ({@code /admin/**}, admins only). Password hashes never leave the server.
 */
@RestController
@RequestMapping("/api/v1")
public class IdentityController {

    /** @param username user name @param password password */
    public record LoginRequest(String username, String password) {}

    /** @param current current password @param next new password */
    public record PasswordChange(String current, String next) {}

    /** @param password new password chosen by an admin */
    public record PasswordReset(String password) {}

    /** @param enabled whether the user may sign in */
    public record EnabledChange(boolean enabled) {}

    /**
     * @param username user name
     * @param displayName display name
     * @param email email
     * @param desk desk
     * @param roles roles
     * @param enabled may sign in
     * @param password initial password
     * @param mustChangePassword ask the user to change it at first sign-in (default: {@code force-password-change-on-create})
     */
    public record NewUser(String username, String displayName, String email, String desk, Set<String> roles, Boolean enabled,
            String password, Boolean mustChangePassword, Set<String> packs) {}

    private final UserService users;
    private final Entitlements entitlements;
    private final com.ash.drishti.identity.PreferenceStore preferences;
    private final com.ash.drishti.server.security.PackAccess packAccess;
    private final com.ash.drishti.identity.AlertHistory alerts;
    private final com.ash.drishti.identity.ApiTokenStore apiTokens;
    private final com.ash.drishti.identity.SessionStore sessions;
    private final com.ash.drishti.identity.design.DesignService designs;
    private final com.ash.drishti.identity.collab.InboxStore inbox;

    public IdentityController(UserService users, Entitlements entitlements, com.ash.drishti.identity.PreferenceStore preferences,
            com.ash.drishti.server.security.PackAccess packAccess, com.ash.drishti.identity.AlertHistory alerts, com.ash.drishti.identity.ApiTokenStore apiTokens,
            com.ash.drishti.identity.SessionStore sessions, com.ash.drishti.identity.design.DesignService designs,
            com.ash.drishti.identity.collab.InboxStore inbox) {
        this.inbox = inbox;
        this.designs = designs;
        this.apiTokens = apiTokens;
        this.sessions = sessions;
        this.alerts = alerts;
        this.packAccess = packAccess;
        this.users = users;
        this.entitlements = entitlements;
        this.preferences = preferences;
    }

    private static UserView view(User u) {
        return UserView.of(u, Instant.now());
    }

    // ---- sign-in and self service --------------------------------------------------------------

    @PostMapping("/auth/login")
    public UserView login(@RequestBody LoginRequest req, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireService(p);
        return view(users.authenticate(req.username(), req.password()));
    }

    @GetMapping("/auth/me")
    public UserView me(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return view(users.require(p.user()));
    }

    @PostMapping("/auth/password")
    public UserView changePassword(@RequestBody PasswordChange req, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return view(users.changeOwnPassword(p.user(), req.current(), req.next()));
    }

    // ---- administration ------------------------------------------------------------------------

    @GetMapping("/admin/users")
    public List<UserView> list(@RequestParam(defaultValue = "") String q, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return users.list(q).stream().map(IdentityController::view).toList();
    }

    @PostMapping("/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@RequestBody NewUser n, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        packAccess.validate(n.packs());
        return view(users.create(p.user(), n.username(), new UserService.Profile(n.displayName(), n.email(), n.desk(),
                n.roles() == null ? Set.of() : n.roles(), n.enabled(), n.packs()), n.password(), n.mustChangePassword() == null ? users.forceChangeOnCreate() : n.mustChangePassword()));
    }

    @GetMapping("/admin/users/{username}")
    public UserView get(@PathVariable String username, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return view(users.require(username));
    }

    @PutMapping("/admin/users/{username}")
    public UserView update(@PathVariable String username, @RequestBody UserService.Profile profile,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        packAccess.validate(profile.packs());
        User u = users.update(p.user(), username, profile);
        if (!u.enabled()) {
            sessions.endAll(u.username(), p.user(), "disabled");
        }
        return view(u);
    }

    @PostMapping("/admin/users/{username}/enabled")
    public UserView enable(@PathVariable String username, @RequestBody EnabledChange req, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        User u = users.setEnabled(p.user(), username, req.enabled());
        if (!u.enabled()) {
            sessions.endAll(u.username(), p.user(), "disabled");   // signed out everywhere; enabling again needs a new sign-in
        }
        return view(u);
    }

    @PostMapping("/admin/users/{username}/password")
    public UserView reset(@PathVariable String username, @RequestBody PasswordReset req, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        User u = users.resetPassword(p.user(), username, req.password());
        sessions.endAll(u.username(), p.user(), "password reset");
        return view(u);
    }

    @DeleteMapping("/admin/users/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String username, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        users.delete(p.user(), username);
        preferences.forget(username);
        designs.forget(username);       // the user's Build designs and their samples go with the account
        alerts.forget(username);
        inbox.forget(username);         // and their inbox (shares they sent stay: they are the record)
        apiTokens.forget(username);
        sessions.endAll(username.trim().toLowerCase(java.util.Locale.ROOT), p.user(), "deleted");
    }

    @GetMapping("/admin/audit")
    public List<AuditLog.Event> audit(@RequestParam(defaultValue = "200") int limit, @RequestParam(defaultValue = "") String subject,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return users.audit(limit, subject);
    }

    @GetMapping("/admin/roles")
    public Set<String> roles(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return users.knownRoles();
    }

    @GetMapping("/admin/status")
    public Map<String, Object> status(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return Map.of("defaultAdminPasswordInUse", users.defaultAdminPasswordInUse(), "users", users.list("").size(),
                "forceChangeOnCreate", users.forceChangeOnCreate(), "installedPacks", packAccess.installed());
    }
}
