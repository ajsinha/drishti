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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserServiceTest {

    @TempDir
    Path dir;

    private UserService service() {
        return service(false);
    }

    private UserService service(boolean forceOnReset) {
        IdentityProperties p = new IdentityProperties(dir.resolve("users.json").toString(), dir.resolve("audit.jsonl").toString(),
                1000, 10, 3, Duration.ofMinutes(15), true, null, null, null, false, forceOnReset);
        return new UserService(new FileUserStore(Path.of(p.usersFile())), new PasswordHasher(1000), new AuditLog(Path.of(p.auditFile())), p,
                Set.of("admin", "trader", "risk", "author"));
    }

    private static String code(Throwable t) {
        return ((DrishtiException) t).errorCode().code();
    }

    @Test
    void seedsTheDevelopmentAdminOnlyWhenEmpty() {
        UserService s = service();
        assertThat(s.seedIfEmpty()).isTrue();
        assertThat(s.seedIfEmpty()).isFalse();
        User admin = s.authenticate("drishti-dev-admin", "drishti-dev-admin123");
        assertThat(admin.roles()).containsExactly("admin");
        assertThat(admin.lastLoginAt()).isNotNull();
        assertThat(s.defaultAdminPasswordInUse()).isTrue();
        s.changeOwnPassword("drishti-dev-admin", "drishti-dev-admin123", "a-better-secret-42");
        assertThat(s.defaultAdminPasswordInUse()).isFalse();
    }

    @Test
    void signInFailuresLockTheAccountAndResetUnlocksIt() {
        UserService s = service();
        s.seedIfEmpty();
        s.create("drishti-dev-admin", "tina", new UserService.Profile("Tina", "tina@example.com", "FX desk", Set.of("trader"), true),
                "trader-pass-1", false);
        assertThatThrownBy(() -> s.authenticate("tina", "nope")).satisfies(e -> assertThat(code(e)).isEqualTo("DRS-6004"));
        assertThatThrownBy(() -> s.authenticate("nobody", "x")).satisfies(e -> assertThat(code(e)).isEqualTo("DRS-6004"));
        assertThatThrownBy(() -> s.authenticate("tina", "nope2")).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> s.authenticate("tina", "nope3")).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> s.authenticate("tina", "trader-pass-1")).satisfies(e -> assertThat(code(e)).isEqualTo("DRS-6005"));
        s.resetPassword("drishti-dev-admin", "tina", "fresh-pass-77");
        User t = s.authenticate("TINA", "fresh-pass-77");
        assertThat(t.mustChangePassword()).as("not forced unless configured").isFalse();
        service(true).resetPassword("drishti-dev-admin", "tina", "fresh-pass-88");
        assertThat(service(true).authenticate("tina", "fresh-pass-88").mustChangePassword()).isTrue();
        assertThat(s.audit(50, "tina")).extracting(AuditLog.Event::action).contains("locked", "password-reset", "login", "user-created");
    }

    @Test
    void validationRules() {
        UserService s = service();
        s.seedIfEmpty();
        var p = new UserService.Profile("X", null, null, Set.of("trader"), true);
        assertThatThrownBy(() -> s.create("a", "No Spaces!", p, "long-enough-1", false)).satisfies(e -> assertThat(code(e)).isEqualTo("DRS-6007"));
        assertThatThrownBy(() -> s.create("a", "bob", p, "short1", false)).satisfies(e -> assertThat(code(e)).isEqualTo("DRS-6003"));
        assertThatThrownBy(() -> s.create("a", "bob", p, "onlyletters", false)).satisfies(e -> assertThat(code(e)).isEqualTo("DRS-6003"));
        assertThatThrownBy(() -> s.create("a", "bob", new UserService.Profile("B", null, null, Set.of("wizard"), true), "long-enough-1", false))
                .satisfies(e -> assertThat(code(e)).isEqualTo("DRS-6007"));
        assertThatThrownBy(() -> s.create("a", "bob", new UserService.Profile("B", "not-an-email", null, Set.of(), true), "long-enough-1", false))
                .satisfies(e -> assertThat(code(e)).isEqualTo("DRS-6007"));
        s.create("a", "bob", p, "long-enough-1", false);
        assertThatThrownBy(() -> s.create("a", "bob", p, "long-enough-1", false)).satisfies(e -> assertThat(code(e)).isEqualTo("DRS-6002"));
        s.setEnabled("drishti-dev-admin", "bob", false);
        assertThatThrownBy(() -> s.authenticate("bob", "long-enough-1")).hasMessageContaining("disabled");
    }

    @Test
    void thereIsAlwaysAnEnabledAdmin() {
        UserService s = service();
        s.seedIfEmpty();
        String admin = "drishti-dev-admin";
        assertThatThrownBy(() -> s.update("x", admin, new UserService.Profile(null, null, null, Set.of("risk"), null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("DRS-6006"));
        assertThatThrownBy(() -> s.setEnabled(admin, admin, false)).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> s.delete(admin, admin)).isInstanceOf(DrishtiException.class);
        s.create(admin, "ops-admin", new UserService.Profile("Ops", null, null, Set.of("admin"), true), "ops-admin-pass-9", false);
        s.update("ops-admin", admin, new UserService.Profile(null, null, null, Set.of("risk"), null));
        assertThat(s.require(admin).roles()).containsExactly("risk");
        assertThatThrownBy(() -> s.delete(admin, "ops-admin")).satisfies(e -> assertThat(code(e)).isEqualTo("DRS-6006"));
    }

    @Test
    void usersPersistAcrossRestartsAndHashesStayOnDisk() throws Exception {
        UserService s = service();
        s.seedIfEmpty();
        s.create("drishti-dev-admin", "rita", new UserService.Profile("Rita", null, "Risk", Set.of("risk"), true), "risk-pass-123", false);
        UserService again = service();
        assertThat(again.list("")).extracting(User::username).containsExactly("drishti-dev-admin", "rita");
        assertThat(again.authenticate("rita", "risk-pass-123").desk()).isEqualTo("Risk");
        String disk = Files.readString(dir.resolve("users.json"));
        assertThat(disk).contains("pbkdf2_sha256$").doesNotContain("risk-pass-123");
        assertThat(again.list("risk")).extracting(User::username).containsExactly("rita");
        assertThat(Files.readAllLines(dir.resolve("audit.jsonl"))).isNotEmpty().noneMatch(l -> l.contains("risk-pass-123"));
        assertThat(List.of(new PasswordHasher(1000).hash("x")).get(0)).startsWith("pbkdf2_sha256$1000$");
    }
}
