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
package com.ash.drishti.server.modules.host;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.kernel.api.data.BindingState;
import com.ash.drishti.kernel.api.module.ModuleContext;
import com.ash.drishti.kernel.api.module.ModuleSettings;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

/**
 * What the kernel gives a loaded module's entry ({@link com.ash.drishti.kernel.api.module.DrishtiModule#start}): the kinds as rows through the
 * kernel's router (never a connection), its settings ({@code drishti.module.<id>.*}), its own folder under the data folder, the audit log
 * and the business date. A produced kind has no write binding yet, so a write is refused in words.
 */
final class KernelModuleContext implements ModuleContext {

    private final String id;
    private final Supplier<SourceRouter> router;
    private final Supplier<AuditLog> audit;
    private final Path dataDir;
    private final Settings settings;

    KernelModuleContext(String id, Supplier<SourceRouter> router, Supplier<AuditLog> audit, Path dataDir, ConfigurableEnvironment env) {
        this.id = id;
        this.router = router;
        this.audit = audit;
        this.dataDir = dataDir;
        this.settings = new Settings(read(env, "drishti.module." + id + "."));
    }

    @Override
    public String moduleId() {
        return id;
    }

    @Override
    public Kinds kinds() {
        return new Kinds() {
            @Override
            public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
                SourceRouter r = router.get();
                if (r == null) {
                    return Optional.empty();
                }
                try {
                    return Optional.ofNullable(r.fetch(ref, asOf).get(30, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                } catch (Exception e) {
                    return Optional.empty();
                }
            }

            @Override
            public boolean documents(String kind, AsOf asOf, Consumer<EntityDocument> sink) {
                SourceRouter r = router.get();
                return r != null && r.documents(kind, asOf, sink);
            }

            @Override
            public Subscription subscribe(EntityRef ref, Consumer<EntityDocument> listener) {
                SourceRouter r = router.get();
                return r == null ? Subscription.NONE : r.subscribe(ref, listener);
            }

            @Override
            public BindingState state(String kind) {
                SourceRouter r = router.get();
                return r != null && r.declaredKinds().contains(kind) ? BindingState.BOUND : BindingState.UNBOUND;
            }
        };
    }

    @Override
    public KindWriter writer() {
        return (kind, date, rows) -> {
            throw new IllegalStateException("module " + id + ": the kind " + kind + " has no write binding on this server (config/bindings.yaml)");
        };
    }

    @Override
    public ModuleSettings settings() {
        return settings;
    }

    @Override
    public Path dataDir() {
        return dataDir;
    }

    @Override
    public void audit(String action, Map<String, Object> detail) {
        AuditLog a = audit.get();
        if (a != null) {
            a.record("module:" + id, action, "module/" + id, String.valueOf(detail));
        }
    }

    @Override
    public LocalDate businessDate() {
        return LocalDate.now(java.time.ZoneOffset.UTC);
    }

    private static Map<String, String> read(ConfigurableEnvironment env, String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        if (env == null) {
            return out;
        }
        for (var s : env.getPropertySources()) {
            if (s instanceof EnumerablePropertySource<?> e) {
                for (String n : e.getPropertyNames()) {
                    if (n.startsWith(prefix) && !out.containsKey(n.substring(prefix.length()))) {
                        String v = env.getProperty(n);
                        if (v != null) {
                            out.put(n.substring(prefix.length()), v);
                        }
                    }
                }
            }
        }
        return out;
    }

    /** The module's settings: keys without the prefix ({@code greeting}, {@code limits.rows}). */
    private record Settings(Map<String, String> all) implements ModuleSettings {

        Settings {
            all = Map.copyOf(all);
        }

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(all.get(key));
        }

        @Override
        public <T extends Record> T bind(Class<T> type) {
            RecordComponent[] cs = type.getRecordComponents();
            Object[] args = new Object[cs.length];
            Class<?>[] types = new Class<?>[cs.length];
            for (int i = 0; i < cs.length; i++) {
                types[i] = cs[i].getType();
                String v = all.get(cs[i].getName());
                args[i] = convert(v, types[i]);
            }
            try {
                return type.getDeclaredConstructor(types).newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("the settings do not bind to " + type.getSimpleName() + ": " + e.getMessage(), e);
            }
        }

        private static Object convert(String v, Class<?> t) {
            if (t == String.class) {
                return v;
            }
            if (t == int.class || t == Integer.class) {
                return v == null ? (t == int.class ? 0 : null) : Integer.valueOf(v.trim());
            }
            if (t == long.class || t == Long.class) {
                return v == null ? (t == long.class ? 0L : null) : Long.valueOf(v.trim());
            }
            if (t == double.class || t == Double.class) {
                return v == null ? (t == double.class ? 0d : null) : Double.valueOf(v.trim());
            }
            if (t == boolean.class || t == Boolean.class) {
                return v == null ? (t == boolean.class ? Boolean.FALSE : null) : Boolean.valueOf(v.trim());
            }
            if (t == List.class) {
                return v == null ? List.of() : List.of(v.split("\\s*,\\s*"));
            }
            throw new IllegalArgumentException("a module setting is text, a number, true/false or a list, not " + t.getSimpleName());
        }
    }
}
