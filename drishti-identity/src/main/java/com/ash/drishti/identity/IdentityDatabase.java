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

import com.ash.drishti.identity.db.IdentityRepositories;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The identity database: SQLite (default, a single file) or PostgreSQL, chosen by {@code drishti.identity.database-url}.
 * Its schema is {@code db/schema-sqlite.sql} or {@code db/schema-postgres.sql}, applied at start-up; both are idempotent,
 * so there are no migrations; Hibernate never changes the schema (on PostgreSQL it checks that the entities match it). Users, roles, preferences
 * and the audit trail are reached only through the JPA entities and Spring Data repositories in
 * {@code com.ash.drishti.identity.db}.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(basePackageClasses = IdentityRepositories.class, considerNestedRepositories = true,
        entityManagerFactoryRef = "identityEntityManagerFactory", transactionManagerRef = "identityTransactionManager")
public class IdentityDatabase {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityDatabase.class);

    @Bean(destroyMethod = "close")
    public HikariDataSource identityDataSource(IdentityProperties props, org.springframework.core.env.Environment env) {
        HikariConfig c = new HikariConfig();
        c.setPoolName("drishti-identity");
        c.setJdbcUrl(props.databaseUrl());
        if (props.sqlite()) {
            Path file = Path.of(props.databaseUrl().substring("jdbc:sqlite:".length()).replaceFirst("\\?.*$", ""));
            try {
                if (file.getParent() != null) {
                    Files.createDirectories(file.toAbsolutePath().getParent());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            // WAL: readers never wait for the writer; writers wait (busy_timeout) rather than fail; cascades need foreign keys.
            c.addDataSourceProperty("journal_mode", "WAL");
            c.addDataSourceProperty("busy_timeout", "10000");
            c.addDataSourceProperty("foreign_keys", "true");
            c.addDataSourceProperty("synchronous", "NORMAL");
            // A transaction that reads and then writes must take the write lock when it begins: two that both read first
            // would each wait for the other to finish reading, and SQLite fails one at once (SQLITE_BUSY) rather than wait.
            c.addDataSourceProperty("transaction_mode", "IMMEDIATE");
        } else {
            c.setUsername(props.databaseUser());
            c.setPassword(props.databasePassword());
        }
        applyPool(c, props, env);
        // Start even when PostgreSQL is not up yet (the pool connects on first use); the schema step below then waits.
        c.setInitializationFailTimeout(-1);
        HikariDataSource ds = new HikariDataSource(c);
        applySchema(ds, props);
        return ds;
    }

    /**
     * The pool: {@code drishti.identity.database-pool-size}, else the site default {@code drishti.db.pool.size} (20) on
     * PostgreSQL; SQLite is an embedded file with one writer at a time, so it keeps a small pool (8) and is exempt from the site
     * default. The times are the site's {@code drishti.db.pool.*} too. A connection is borrowed per transaction and returned at
     * once; one held past {@code leak-threshold} is logged by Hikari with the borrower's stack.
     */
    static void applyPool(HikariConfig c, IdentityProperties props, org.springframework.core.env.Environment env) {
        String p = "drishti.db.pool.";
        int size = props.databasePoolSize() != null ? props.databasePoolSize() : props.sqlite() ? 8 : env.getProperty(p + "size", Integer.class, 20);
        c.setMaximumPoolSize(size);
        c.setMinimumIdle(Math.min(size, env.getProperty(p + "min-idle", Integer.class, 2)));
        c.setIdleTimeout(millis(env, p + "idle-timeout", 600_000));
        c.setMaxLifetime(millis(env, p + "max-lifetime", 1_800_000));
        c.setConnectionTimeout(Math.max(250, millis(env, p + "connection-timeout", 10_000)));
        c.setLeakDetectionThreshold(millis(env, p + "leak-threshold", 60_000));
        long statement = millis(env, p + "statement-timeout", 300_000);
        if (!props.sqlite() && statement > 0) {
            c.addDataSourceProperty("options", "-c statement_timeout=" + statement);
        }
    }

    private static long millis(org.springframework.core.env.Environment env, String key, long fallback) {
        String v = env.getProperty(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        String t = v.strip().toLowerCase(java.util.Locale.ROOT);
        try {
            if (t.endsWith("ms")) {
                return Long.parseLong(t.substring(0, t.length() - 2).strip());
            }
            long unit = t.endsWith("h") ? 3_600_000 : t.endsWith("m") ? 60_000 : 1_000;
            return Long.parseLong(t.replaceAll("[hms]$", "").strip()) * unit;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(key + " must be a time such as 30, 500ms, 30s, 10m or 1h, not '" + v + "'");
        }
    }

    static void applySchema(DataSource ds, IdentityProperties props) {
        String schema = props.sqlite() ? "db/schema-sqlite.sql" : "db/schema-postgres.sql";
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource(schema));
        populator.setSqlScriptEncoding("UTF-8");
        long backoff = 1_000;
        for (int attempt = 1; ; attempt++) {
            try {
                populator.execute(ds);
                if (props.sqlite()) {
                    ensureHoldRange(ds);
                    ensureTokenScopes(ds);
                    ensureShareViewState(ds);
                }
                LOG.info("identity database ready: {} ({})", redact(props.databaseUrl()), schema);
                return;
            } catch (RuntimeException e) {
                if (attempt >= 30) {
                    throw e;
                }
                LOG.warn("identity database not reachable yet ({}); retrying in {} ms", e.getMessage(), backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                backoff = Math.min(backoff * 2, 10_000);
            }
        }
    }

    /** SQLite cannot add a column "if not exists": a hold table made before the date range existed gets its two columns here. */
    private static void ensureHoldRange(DataSource ds) {
        try (java.sql.Connection c = ds.getConnection(); java.sql.Statement st = c.createStatement()) {
            java.util.Set<String> have = new java.util.HashSet<>();
            try (java.sql.ResultSet r = st.executeQuery("PRAGMA table_info(drishti_collab_hold)")) {
                while (r.next()) {
                    have.add(r.getString("name"));
                }
            }
            for (String col : new String[] {"date_from", "date_to"}) {
                if (!have.contains(col)) {
                    st.execute("ALTER TABLE drishti_collab_hold ADD COLUMN " + col + " TIMESTAMP");
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not update drishti_collab_hold", e);
        }
    }

    /** A share table made before view state was sent with a share gets its column here (SQLite has no "add column if not exists"). */
    private static void ensureShareViewState(DataSource ds) {
        try (java.sql.Connection c = ds.getConnection(); java.sql.Statement st = c.createStatement()) {
            boolean have = false;
            try (java.sql.ResultSet r = st.executeQuery("PRAGMA table_info(drishti_share)")) {
                while (r.next()) {
                    have |= "view_state".equals(r.getString("name"));
                }
            }
            if (!have) {
                st.execute("ALTER TABLE drishti_share ADD COLUMN view_state TEXT");
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not update drishti_share", e);
        }
    }

    /** A token table made before scopes existed gets its column here (SQLite has no "add column if not exists"). */
    private static void ensureTokenScopes(DataSource ds) {
        try (java.sql.Connection c = ds.getConnection(); java.sql.Statement st = c.createStatement()) {
            boolean have = false;
            try (java.sql.ResultSet r = st.executeQuery("PRAGMA table_info(drishti_api_token)")) {
                while (r.next()) {
                    have |= "scopes".equals(r.getString("name"));
                }
            }
            if (!have) {
                st.execute("ALTER TABLE drishti_api_token ADD COLUMN scopes TEXT");
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not update drishti_api_token", e);
        }
    }

    private static String redact(String url) {
        return url.replaceAll("(?i)(password=)[^&]*", "$1***");
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean identityEntityManagerFactory(HikariDataSource identityDataSource, IdentityProperties props) {
        LocalContainerEntityManagerFactoryBean f = new LocalContainerEntityManagerFactoryBean();
        f.setPersistenceUnitName("drishti-identity");
        f.setDataSource(identityDataSource);
        f.setPackagesToScan(IdentityRepositories.class.getPackageName());
        f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        f.setJpaPropertyMap(Map.of(
                // PostgreSQL: the entities must match the schema file, or start-up fails. SQLite types columns by value, not
                // by declaration (its auto-numbered key must be INTEGER, never BIGINT), so the type check cannot apply;
                // the identity tests round-trip every mapped column on both databases instead.
                "hibernate.hbm2ddl.auto", props.sqlite() ? "none" : "validate",
                "hibernate.dialect", props.sqlite() ? "org.hibernate.community.dialect.SQLiteDialect" : "org.hibernate.dialect.PostgreSQLDialect",
                "hibernate.jdbc.time_zone", "UTC",
                "hibernate.type.preferred_instant_jdbc_type", "TIMESTAMP_UTC",
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
