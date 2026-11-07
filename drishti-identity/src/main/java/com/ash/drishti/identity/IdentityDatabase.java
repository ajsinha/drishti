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

import com.ash.drishti.identity.database.DatabaseSettings;
import com.ash.drishti.identity.database.DrishtiDatabase;
import com.ash.drishti.identity.db.IdentityRepositories;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Drishti database in Spring (docs/architecture/DATA_ARCHITECTURE.md, "The Drishti database"): one database for every Drishti-owned
 * table, SQLite (default, a single file) or PostgreSQL, chosen in its own settings file {@code config/db/database.yaml}
 * ({@code drishti.database.*}). Its complete schema ({@code db/schema-sqlite.sql} or {@code db/schema-postgres.sql}) is applied at start-up;
 * there are no migrations: a schema change is taken by copying the database into a fresh one ({@code drishti.py db copy}). Hibernate never
 * changes the schema (on PostgreSQL it checks that the entities match it). Users, roles, preferences and the audit trail are reached only
 * through the JPA entities and Spring Data repositories in {@code com.ash.drishti.identity.db}.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(basePackageClasses = IdentityRepositories.class, considerNestedRepositories = true,
        entityManagerFactoryRef = "identityEntityManagerFactory", transactionManagerRef = "identityTransactionManager")
public class IdentityDatabase {

    /** The settings, {@code drishti.database.*} (config/db/database.yaml is imported by application.yaml). */
    @Bean
    public DatabaseSettings drishtiDatabaseSettings(org.springframework.core.env.Environment env) {
        for (String old : new String[] {"drishti.identity.database-url", "drishti.identity.database-user", "drishti.identity.database-password",
                "drishti.identity.database-pool-size", "DRISHTI_IDENTITY_DB_URL", "DRISHTI_IDENTITY_DB_USER", "DRISHTI_IDENTITY_DB_PASSWORD"}) {
            if (env.getProperty(old) != null) {           // set by an older site's application.local.yaml, or the old environment names
                throw new IllegalStateException(old + " has moved to the Drishti database's own file, config/db/database.yaml "
                        + "(drishti.database.kind, sqlite.file, postgres.url, postgres.user, postgres.password, pool.size; environment "
                        + "DRISHTI_DB_KIND, DRISHTI_DB_FILE, DRISHTI_DB_URL, DRISHTI_DB_USER, DRISHTI_DB_PASSWORD). Move the setting and remove the old one "
                        + "(RELEASE_NOTES.md, upgrade notes)");
            }
        }
        return DatabaseSettings.from(env::getProperty, Path.of(env.getProperty("drishti.data.dir", "./data")),
                env.getProperty("drishti.db.file", "./config/db/database.yaml"));
    }

    /** The one pool of the Drishti database, its schema applied and checked ({@link DrishtiDatabase#open}). */
    @Bean(destroyMethod = "close")
    public HikariDataSource drishtiDataSource(DatabaseSettings settings, org.springframework.core.env.Environment env) {
        return DrishtiDatabase.open(settings, env::getProperty);
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean identityEntityManagerFactory(HikariDataSource drishtiDataSource, DatabaseSettings db) {
        LocalContainerEntityManagerFactoryBean f = new LocalContainerEntityManagerFactoryBean();
        f.setPersistenceUnitName("drishti-identity");
        f.setDataSource(drishtiDataSource);
        f.setPackagesToScan(IdentityRepositories.class.getPackageName());
        f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        f.setJpaPropertyMap(Map.of(
                // PostgreSQL: the entities must match the schema file, or start-up fails. SQLite types columns by value, not
                // by declaration (its auto-numbered key must be INTEGER, never BIGINT), so the type check cannot apply;
                // the identity tests round-trip every mapped column on both databases instead.
                "hibernate.hbm2ddl.auto", db.sqlite() ? "none" : "validate",
                "hibernate.dialect", db.sqlite() ? "org.hibernate.community.dialect.SQLiteDialect" : "org.hibernate.dialect.PostgreSQLDialect",
                "hibernate.jdbc.time_zone", "UTC",
                // SQLite: TIMESTAMP with the UTC zone above (a calendar of its own per read). TIMESTAMP_UTC reads through one static
                // calendar for the whole JVM and the SQLite driver sets each row's time on it, so two reads at once in two databases
                // (the nodes of an in-JVM cluster) could swap their times. Both store the same epoch milliseconds.
                "hibernate.type.preferred_instant_jdbc_type", db.sqlite() ? "TIMESTAMP" : "TIMESTAMP_UTC",
                "hibernate.boot.allow_jdbc_metadata_access", "false",
                "hibernate.hbm2ddl.jdbc_metadata_extraction_strategy", "individually"));
        return f;
    }

    @Bean
    public PlatformTransactionManager identityTransactionManager(EntityManagerFactory identityEntityManagerFactory) {
        return new JpaTransactionManager(identityEntityManagerFactory);
    }

    @Bean
    public TransactionTemplate identityTransactions(PlatformTransactionManager identityTransactionManager) {
        return new TransactionTemplate(identityTransactionManager);
    }
}
