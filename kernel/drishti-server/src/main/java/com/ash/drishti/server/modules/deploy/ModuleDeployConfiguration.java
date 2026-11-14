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
package com.ash.drishti.server.modules.deploy;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.module.runtime.KernelFacts;
import com.ash.drishti.kernel.api.health.HealthFigures;
import com.ash.drishti.server.net.PeerCalls;
import com.ash.drishti.server.placement.PlacementService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Module deployment's beans: this node's module runtime and registry, the coordinator, the route gate and the Health figure. */
@Configuration(proxyBeanMethods = false)
public class ModuleDeployConfiguration {

    /**
     * The module folders ({@code drishti.modules.path}, commas or the path separator; default {@code <data folder>/modules}): unpacked
     * modules ({@code <id>/<version>/}) loaded at start, and the {@code .dmod} packages auto-deploy watches.
     */
    static List<Path> modulesPath(Environment env) {
        List<Path> folders = new ArrayList<>();
        String setting = env.getProperty("drishti.modules.path", Path.of(env.getProperty("drishti.data.dir", "./data"), "modules").toString());
        for (String s : setting.split("[," + java.io.File.pathSeparator + "]")) {
            if (!s.isBlank()) {
                folders.add(Path.of(s.strip()).toAbsolutePath().normalize());
            }
        }
        return folders;
    }

    /**
     * Auto-deploy ({@code drishti.modules.autodeploy}): {@code true}, {@code false}, or unset for on with a single node and off in a cluster
     * (Drishti*net running), where plan → apply is the way.
     */
    static boolean autodeploy(Environment env, ObjectProvider<com.ash.drishti.server.net.NetService> net) {
        String v = env.getProperty("drishti.modules.autodeploy", "");
        if (!v.isBlank()) {
            return Boolean.parseBoolean(v.strip());
        }
        com.ash.drishti.server.net.NetService n = net.getIfAvailable();
        return n == null || !n.running();
    }

    static {
        // a library object made while a module's code ran keeps that thread's context class loader: the Hadoop configuration of a
        // Parquet codec (cached for the process) is given the kernel's back when the module leaves, so its loader can go
        com.ash.drishti.common.ModuleLoaders.onLeave(ModuleDeployConfiguration::releaseLibraryLoaders);
        // the JDK's one delay thread of CompletableFuture starts when first asked and keeps the access context of the code that asked:
        // started here, by the kernel, before any module's code runs, so it never holds a module's protection domain (and loader)
        new java.util.concurrent.CompletableFuture<Void>().completeOnTimeout(null, 1, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    static void releaseLibraryLoaders(ClassLoader leaving) {
        try {
            java.lang.reflect.Field f = Class.forName("org.apache.parquet.hadoop.CodecFactory").getDeclaredField("CODEC_BY_NAME");
            f.setAccessible(true);
            for (Object codec : List.copyOf(((java.util.Map<?, ?>) f.get(null)).values())) {
                java.lang.reflect.Method getConf = codec.getClass().getMethod("getConf");
                Object conf = getConf.invoke(codec);
                if (conf != null && conf.getClass().getMethod("getClassLoader").invoke(conf) == leaving) {
                    conf.getClass().getMethod("setClassLoader", ClassLoader.class).invoke(conf, ModuleDeployConfiguration.class.getClassLoader());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LoggerFactory.getLogger(ModuleDeployConfiguration.class).debug("the Parquet codecs' configurations were not released: {}", e.toString());
        }
    }

    @Bean
    public ModuleAutoDeploy moduleAutoDeploy(ModuleDeployService deploy, ModuleNode node, Environment env) {
        return new ModuleAutoDeploy(modulesPath(env), deploy, node);
    }

    /** The auto-deploy watch: a look at the module folders every {@code drishti.modules.autodeploy-seconds}, after the modules started. */
    @Bean(destroyMethod = "shutdownNow")
    public java.util.concurrent.ScheduledExecutorService moduleAutoDeployWatch(ModuleAutoDeploy auto, Environment env,
            ObjectProvider<com.ash.drishti.server.net.NetService> net) {
        java.util.concurrent.ScheduledExecutorService s = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("drishti-module-autodeploy").factory());
        long every = Math.max(1, env.getProperty("drishti.modules.autodeploy-seconds", Long.class, 5L));
        s.scheduleWithFixedDelay(() -> {
            try {
                if (started && autodeploy(env, net)) {
                    auto.scan();
                }
                ModuleNode node = nodeRef;
                if (started && node != null && node.developmentMode()) {
                    node.reloadChanged();
                }
            } catch (RuntimeException e) {
                LoggerFactory.getLogger(ModuleAutoDeploy.class).warn("auto-deploy: {}", e.toString());
            }
        }, every, every, java.util.concurrent.TimeUnit.SECONDS);
        return s;
    }

    private volatile boolean started;
    private volatile ModuleNode nodeRef;

    static Path root(Environment env) {
        String dir = env.getProperty("drishti.module-deploy.dir", "");
        return (dir.isBlank() ? Path.of(env.getProperty("drishti.data.dir", "./data"), "modules") : Path.of(dir)).toAbsolutePath().normalize();
    }

    /**
     * Development mode ({@code drishti.modules.dev}, default false): the folders of {@code drishti.modules.path} are loaded as exploded
     * modules, unverified, and reloaded when they change. Refused (with an error in the log, and nothing exploded loaded) in a cluster and
     * under the {@code prod} or {@code production} profile: production loads only verified {@code .dmod} packages.
     */
    static List<Path> development(Environment env, ObjectProvider<com.ash.drishti.server.net.NetService> net) {
        if (!env.getProperty("drishti.modules.dev", Boolean.class, false)) {
            return List.of();
        }
        com.ash.drishti.server.net.NetService n = net.getIfAvailable();
        String refused = n != null && n.running() ? "this server is in a cluster"
                : env.acceptsProfiles(org.springframework.core.env.Profiles.of("prod", "production")) ? "the profile is production" : null;
        if (refused != null) {
            LoggerFactory.getLogger(ModuleNode.class).error("drishti.modules.dev=true is refused: {}; only verified .dmod packages load", refused);
            return List.of();
        }
        return modulesPath(env);
    }

    @Bean(destroyMethod = "close")
    public ModuleNode moduleNode(PlacementService placement, ApplicationContext ctx, Environment env,
            ObjectProvider<com.ash.drishti.server.net.NetService> net) {
        ApiMounts mounts = new ApiMounts(() -> ctx.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class), () -> {
            Collection<Object> out = new ArrayList<>(ctx.getBeansOfType(RequestMappingHandlerAdapter.class).values());
            out.addAll(ctx.getBeansOfType(HandlerExceptionResolver.class).values());
            out.addAll(ctx.getBeansOfType(org.springframework.core.convert.ConversionService.class).values());
            out.addAll(ctx.getBeansOfType(com.fasterxml.jackson.databind.ObjectMapper.class).values());
            ctx.getBeansOfType(RequestMappingHandlerAdapter.class).values().forEach(a -> a.getMessageConverters().forEach(c -> {
                if (c instanceof org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter j) {
                    out.add(j.getObjectMapper());
                }
            }));
            return out;
        });
        long drain = env.getProperty("drishti.module-deploy.drain-seconds", Long.class, 30L) * 1000;
        List<Path> folders = development(env, net);
        com.ash.drishti.server.modules.host.ModuleWiring wiring = new com.ash.drishti.server.modules.host.ModuleWiring(
                () -> ctx.getBeanProvider(com.ash.drishti.engine.source.SourceRegistry.class).getIfAvailable(),
                () -> ctx.getBeanProvider(com.ash.drishti.server.engines.EngineRegistry.class).getIfAvailable(),
                () -> ctx.getBeanProvider(com.ash.drishti.engine.source.SourceRouter.class).getIfAvailable(),
                () -> ctx.getBeanProvider(com.ash.drishti.identity.AuditLog.class).getIfAvailable(),
                env instanceof org.springframework.core.env.ConfigurableEnvironment c ? c : null,
                () -> ctx.getBeanProvider(com.ash.drishti.server.caches.MemoryBudget.class).getIfAvailable())
                .bindings(() -> ctx.getBeanProvider(com.ash.drishti.server.bindings.BindingService.class).getIfAvailable())
                .packs(() -> ctx.getBeanProvider(com.ash.drishti.server.PackReloader.class).getIfAvailable())
                .python(() -> ctx.getBeanProvider(com.ash.drishti.compute.ComputeService.class).getIfAvailable(),
                        m -> placement.computePartHere(m.id()));
        return new ModuleNode(root(env), KernelFacts.ofKernel(), placement, mounts, drain, folders, wiring)
                .siteSwitch(id -> ctx.getBeanProvider(com.ash.drishti.registry.AccessRegistry.class).getIfAvailable() == null
                        || ctx.getBean(com.ash.drishti.registry.AccessRegistry.class).module(id).map(s -> s.enabled() && s.licensed()).orElse(true));
    }

    @Bean
    public ModuleDeployService moduleDeployService(ModuleNode node, PlacementService placement, PeerCalls peers, AuditLog audit, Environment env) {
        NodeCalls calls = new NodeCalls(node, placement::self, peers,
                Duration.ofSeconds(env.getProperty("drishti.module-deploy.node-call-seconds", Long.class, 120L)));
        return new ModuleDeployService(root(env), node, placement, calls, audit, Clock.systemUTC(),
                env.getProperty("drishti.module-deploy.conformance-cycles", Integer.class, 5));
    }

    /** Loads the active modules placed here once the server's web layer is up (the request mapping exists). */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void startModules(org.springframework.boot.context.event.ApplicationReadyEvent e) {
        nodeRef = e.getApplicationContext().getBean(ModuleNode.class).start(e.getApplicationContext());
        if (e.getApplicationContext().getEnvironment().getProperty("drishti.modules.shipped", Boolean.class, true)) {
            e.getApplicationContext().getBean(ModuleDeployService.class).installShipped(shipped(ModuleDeployConfiguration.root(
                    e.getApplicationContext().getEnvironment()).resolve("shipped")), "the distribution")
                    .forEach(l -> LoggerFactory.getLogger(ModuleNode.class).info("the distribution's modules: {}", l));
        }
        started = true;
    }

    /**
     * The first-party modules the distribution carries ({@code drishti/modules/*.dmod} on the class path: the full jar has banking,
     * market-data, capital-markets and economy; the thin jar none), copied out to {@code folder} so they install like any package.
     */
    static List<Path> shipped(Path folder) {
        List<Path> out = new ArrayList<>();
        try {
            org.springframework.core.io.Resource[] all = new org.springframework.core.io.support.PathMatchingResourcePatternResolver(
                    ModuleDeployConfiguration.class.getClassLoader()).getResources("classpath*:drishti/modules/*.dmod");
            if (all.length == 0) {
                return out;
            }
            java.nio.file.Files.createDirectories(folder);
            for (org.springframework.core.io.Resource r : all) {
                Path to = folder.resolve(java.util.Objects.requireNonNull(r.getFilename()));
                try (java.io.InputStream in = r.getInputStream()) {
                    java.nio.file.Files.copy(in, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                out.add(to);
            }
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException("the distribution's modules do not read", ex);
        }
        return out;
    }

    /** After placement's guard (4): removed 410, off 404, a swap waited for, requests counted for the drain. */
    @Bean
    public FilterRegistrationBean<ModuleRouteFilter> moduleRouteFilter(ModuleNode node, Environment env) {
        FilterRegistrationBean<ModuleRouteFilter> r = new FilterRegistrationBean<>(
                new ModuleRouteFilter(node, env.getProperty("drishti.module-deploy.swap-wait-seconds", Long.class, 30L) * 1000));
        r.addUrlPatterns("/api/*");
        r.setOrder(5);
        return r;
    }

    @Bean
    public HealthFigures moduleDeployHealth(ModuleNode node, ObjectProvider<ModuleDeployService> deploy) {
        return new HealthFigures() {
            @Override
            public String key() {
                return "deployedModules";
            }

            @Override
            public Object value() {
                return node.runtime() == null ? List.of() : node.status().get("modules");
            }
        };
    }
}
