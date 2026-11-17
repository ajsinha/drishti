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
package com.ash.drishti.server.modules;

import com.ash.drishti.module.runtime.KernelFacts;
import com.ash.drishti.kernel.api.health.HealthFigures;
import com.ash.drishti.server.modules.host.ApiMounts;
import com.ash.drishti.server.placement.PlacementService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** The module host's beans: this node's module runtime and the Health figure. */
@Configuration(proxyBeanMethods = false)
public class ModuleHostConfiguration {

    static {
        // a library object made while a module's code ran keeps that thread's context class loader: the Hadoop configuration of a
        // Parquet codec (cached for the process) is given the kernel's back when the module leaves, so its loader can go
        com.ash.drishti.common.ModuleLoaders.onLeave(ModuleHostConfiguration::releaseLibraryLoaders);
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
                    conf.getClass().getMethod("setClassLoader", ClassLoader.class).invoke(conf, ModuleHostConfiguration.class.getClassLoader());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LoggerFactory.getLogger(ModuleHostConfiguration.class).debug("the Parquet codecs' configurations were not released: {}", e.toString());
        }
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
        com.ash.drishti.server.modules.host.ModuleWiring wiring = new com.ash.drishti.server.modules.host.ModuleWiring(
                () -> ctx.getBeanProvider(com.ash.drishti.engine.source.SourceRegistry.class).getIfAvailable(),
                () -> ctx.getBeanProvider(com.ash.drishti.server.engines.EngineRegistry.class).getIfAvailable(),
                () -> ctx.getBeanProvider(com.ash.drishti.engine.source.SourceRouter.class).getIfAvailable(),
                () -> ctx.getBeanProvider(com.ash.drishti.identity.AuditLog.class).getIfAvailable(),
                env instanceof org.springframework.core.env.ConfigurableEnvironment c ? c : null,
                () -> ctx.getBeanProvider(com.ash.drishti.server.caches.MemoryBudget.class).getIfAvailable())
                .bindings(() -> ctx.getBeanProvider(com.ash.drishti.server.bindings.BindingService.class).getIfAvailable())
                .kernel(() -> ctx.getBeanProvider(com.ash.drishti.kernel.api.services.Kernel.class).getIfAvailable())
                .packs(() -> ctx.getBeanProvider(com.ash.drishti.server.PackReloader.class).getIfAvailable())
                .sutras(() -> ctx.getBeanProvider(com.ash.drishti.rachana.SutraRegistry.class).getIfAvailable())
                .python(() -> ctx.getBeanProvider(com.ash.drishti.compute.ComputeService.class).getIfAvailable(),
                        m -> placement.computePartHere(m.id()));
        KernelFacts facts = com.ash.drishti.module.runtime.ModuleBoot.current().map(com.ash.drishti.module.runtime.ModuleBoot.Result::facts)
                .orElseGet(KernelFacts::ofKernel);
        return new ModuleNode(facts, placement, mounts, wiring)
                .development(com.ash.drishti.packs.LaunchRoots.devAsked(env),
                        () -> com.ash.drishti.packs.LaunchRoots.devRefusal(env, net.getIfAvailable() != null && net.getIfAvailable().running()))
                .siteSwitch(id -> ctx.getBeanProvider(com.ash.drishti.registry.AccessRegistry.class).getIfAvailable() == null
                        || ctx.getBean(com.ash.drishti.registry.AccessRegistry.class).module(id).map(s -> s.enabled() && s.licensed()).orElse(true));
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
            ModuleNode node = e.getApplicationContext().getBean(ModuleNode.class).start(e.getApplicationContext());
            // the cluster's digest carries what this node booted (content hash per module, the fingerprint): Admin -> Modules compares
            e.getApplicationContext().getBeanProvider(com.ash.drishti.server.net.DigestService.class)
                    .ifAvailable(d -> d.modules(node::identity, node::modulesFingerprint));
        } finally {
            // the modules are in: a connector that named a module's engine class before they loaded starts now (the start's order, not a
            // retry); one whose class is still missing fails in words from here on
            e.getApplicationContext().getBeanProvider(com.ash.drishti.engine.source.SourceRegistry.class).ifAvailable(
                    com.ash.drishti.engine.source.SourceRegistry::modulesLoaded);
            // the packs' watch starts from what the folders hold now that the modules' packs are in place (no reload of unchanged packs)
            e.getApplicationContext().getBeanProvider(com.ash.drishti.server.PackReloader.class).ifAvailable(com.ash.drishti.server.PackReloader::modulesLoaded);
            firstLoad.run();
        }
    }

    @Bean
    public HealthFigures moduleDeployHealth(ModuleNode node) {
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
