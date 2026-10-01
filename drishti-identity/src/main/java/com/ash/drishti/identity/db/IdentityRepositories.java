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
package com.ash.drishti.identity.db;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repositories for the identity tables: derived queries only, no SQL in code. */
public final class IdentityRepositories {

    private IdentityRepositories() {}

    public interface Users extends JpaRepository<UserEntity, String> {}

    public interface Roles extends JpaRepository<RoleEntity, String> {}

    public interface PackStates extends JpaRepository<PackStateEntity, String> {}

    public interface Alerts extends JpaRepository<AlertEntity, Long> {
        List<AlertEntity> findByUsernameOrderByIdDesc(String username, Pageable page);

        long countByUsername(String username);

        void deleteByUsernameAndIdLessThan(String username, Long id);

        void deleteByUsername(String username);
    }

    public interface Audit extends JpaRepository<AuditEventEntity, Long> {
        List<AuditEventEntity> findAllByOrderByIdDesc(Pageable page);

        List<AuditEventEntity> findBySubjectOrActorOrderByIdDesc(String subject, String actor, Pageable page);
    }

    public interface Preferences extends JpaRepository<PreferenceEntity, PreferenceEntity.Key> {
        List<PreferenceEntity> findByKeyUsernameAndKeyNamespaceOrderByKeyName(String username, String namespace);

        long countByKeyUsernameAndKeyNamespace(String username, String namespace);

        List<PreferenceEntity> findByKeyUsername(String username);

        void deleteByKeyUsername(String username);
    }
}
