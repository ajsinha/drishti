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
        String setting = env.getProperty("drishti.modules.path", Path.of(env.getProperty("drishti.data.dir", com.ash.drishti.api.Roots.data()), "modules").toString());
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
        if (!dir.isBlank()) {
            return Path.of(dir).toAbsolutePath().normalize();
        }
        String data = env.getProperty("drishti.data.dir", com.ash.drishti.api.Roots.data());
        return (packaged(env) ? Path.of(data, "cache", "module-host") : Path.of(data, "modules")).toAbsolutePath().normalize();
    }

    /** Whether the process's modules folder holds packages ({@code *.dmod}): then the folder is the truth about which modules run. */
    static boolean packaged(Environment env) {
        Path home = com.ash.drishti.module.runtime.ModuleBoot.processHome(env::getProperty);
        return home != null
                && com.ash.drishti.module.runtime.ModuleBoot.usePackages(env.getProperty(com.ash.drishti.module.runtime.ModuleBoot.PACKAGES_SETTING))
                && com.ash.drishti.module.runtime.ModuleBoot.hasPackages(home);
    }

    /**
     * With packages beside the jar the module host's own folder is a cache of them ({@code <data>/cache/module-host}: what was
     * installed, what an administrator switched): made again from the packages at every start, so a package replaced, upgraded or taken
     * away between two starts is what runs, never an older copy kept in a store (until the host's store is deleted, i7a I8).
     */
    private static void freshHost(Path root) {
        if (!java.nio.file.Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> all = java.nio.file.Files.walk(root)) {
            for (Path p : (Iterable<Path>) all.sorted(java.util.Comparator.reverseOrder())::iterator) {
                java.nio.file.Files.deleteIfExists(p);
            }
        } catch (java.io.IOException ex) {
            LoggerFactory.getLogger(ModuleNode.class).warn("the module host's folder {} was not cleared: {}", root, ex.toString());
        }
    }

    /**
     * Development mode (the switch lives in {@link com.ash.drishti.packs.LaunchRoots#DEV}, default false): the folders of
     * {@code drishti.modules.path} are loaded as exploded modules, unverified, and reloaded when they change. Refused (with an error in
     * the log, and nothing exploded loaded) where {@link com.ash.drishti.packs.LaunchRoots#devRefusal} says so.
     */
    static List<Path> development(Environment env, ObjectProvider<com.ash.drishti.server.net.NetService> net) {
        if (!com.ash.drishti.packs.LaunchRoots.devAsked(env)) {
            return List.of();
        }
        com.ash.drishti.server.net.NetService n = net.getIfAvailable();
        String refused = com.ash.drishti.packs.LaunchRoots.devRefusal(env, n != null && n.running());
        if (refused != null) {
            LoggerFactory.getLogger(ModuleNode.class).error("{}=true is refused: {}; only verified .dmod packages load", com.ash.drishti.packs.LaunchRoots.DEV, refused);
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
        if (packaged(env)) {
            freshHost(root(env));
        }
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
                .sutras(() -> ctx.getBeanProvider(com.ash.drishti.rachana.SutraRegistry.class).getIfAvailable())
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
        return new ModuleDeployService(root(env), node, placement, calls, audit, Clock.systemUTC());
    }

    /**
     * The modules' first load is a change of the live contributions under way from the moment this server's beans are made: its web layer
     * answers a second or two before the modules have loaded, and a question that needs a module's contribution (an explainer) waits for
     * them rather than hearing it is not here. Ended when the modules have loaded, or when the server stops without getting there.
     */
    private final Runnable firstLoad = com.ash.drishti.common.Extensions.changing();

    @jakarta.annotation.PreDestroy
    void stopping() {
        firstLoad.run();
    }

    /** Loads the active modules placed here once the server's web layer is up (the request mapping exists). */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void startModules(org.springframework.boot.context.event.ApplicationReadyEvent e) {
        try {
            nodeRef = e.getApplicationContext().getBean(ModuleNode.class).start(e.getApplicationContext());
            if (e.getApplicationContext().getEnvironment().getProperty("drishti.modules.shipped", Boolean.class, true)) {
                e.getApplicationContext().getBean(ModuleDeployService.class).installShipped(shipped(ModuleDeployConfiguration.root(
                        e.getApplicationContext().getEnvironment()).resolve("shipped"), e.getApplicationContext().getEnvironment()), "the distribution")
                        .forEach(l -> LoggerFactory.getLogger(ModuleNode.class).info("the distribution's modules: {}", l));
            }
        } finally {
            // the modules are in: a connector that named a module's engine class before they loaded starts now (the start's order, not a
            // retry); one whose class is still missing fails in words from here on
            e.getApplicationContext().getBeanProvider(com.ash.drishti.engine.source.SourceRegistry.class).ifAvailable(
                    com.ash.drishti.engine.source.SourceRegistry::modulesLoaded);
            // the packs' watch starts from what the folders hold now that the modules' packs are in place (no reload of unchanged packs)
            e.getApplicationContext().getBeanProvider(com.ash.drishti.server.PackReloader.class).ifAvailable(com.ash.drishti.server.PackReloader::modulesLoaded);
            firstLoad.run();
        }
        started = true;
    }

    /**
     * The first-party modules the distribution carries: the packages ({@code *.dmod}) beside the server's jar, in the modules folder
     * of this process, written by {@code drishti.py build modules}), copied out to {@code folder} so they install like any
     * package. The server's jar holds none; with no modules folder (or {@code none}) the list is empty.
     */
    static List<Path> shipped(Path folder, Environment env) {
        if (!packaged(env)) {
            return List.of();
        }
        List<Path> found = com.ash.drishti.module.runtime.ModuleBoot.packages(com.ash.drishti.module.runtime.ModuleBoot.processHome(env::getProperty));
        List<Path> out = new ArrayList<>();
        try {
            if (found.isEmpty()) {
                return out;
            }
            java.nio.file.Files.createDirectories(folder);
            for (Path from : found) {
                Path to = folder.resolve(from.getFileName().toString());
                java.nio.file.Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                out.add(to);
            }
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException("the modules folder's packages do not read", ex);
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
