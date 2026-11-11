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
package com.ash.drishti.server.dist;

import com.ash.drishti.common.RetryPolicy;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.report.BiReportService;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.collab.mail.MailRenderer;
import com.ash.drishti.server.collab.mail.MailTemplates;
import com.ash.drishti.server.collab.mail.MailTransport;
import com.ash.drishti.server.loads.LoadService;
import com.ash.drishti.server.security.Entitlements;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;

/** Export and subscriptions ({@code drishti.distribution}): the services and their wiring to the directory, mail and the data-load signal. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DistProperties.class)
public class DistConfiguration {

    @Bean
    public BiExporter biExporter(BiReportService reports, DistProperties props) {
        return new BiExporter(reports, props.export());
    }

    @Bean
    public ViewExporter viewExporter(ViewPipeline pipeline, Entitlements entitlements) {
        return new ViewExporter(pipeline, entitlements);
    }

    @Bean
    public StatementService statementService(BiReportService reports, BiExporter bi, DistProperties props, BusinessDates dates) {
        return new StatementService(reports, bi, props.statements(), Clock.systemUTC(), dates.zone(), dates::current);
    }

    @Bean(destroyMethod = "close")
    public ContentExporter contentExporter(BiExporter bi, ViewExporter views, DistProperties props, BusinessDates dates, CollabProperties collab, StatementService statements) {
        return new ContentExporter(bi, views, props.export(), Clock.systemUTC(), dates.zone(), collab.consoleUrl()).with(statements);
    }

    @Bean(destroyMethod = "close")
    @SuppressWarnings("java:S107")
    public SubscriptionService subscriptionService(PreferenceStore store, ContentExporter exports, UserService users, Principals principals, Entitlements entitlements,
            BiService bi, AuditLog audit, ObjectProvider<JavaMailSender> sender, CollabProperties collab, DistProperties props, Environment env, BusinessDates dates,
            LoadService loads, ObjectProvider<com.ash.drishti.server.bi.ai.BiAiService> ai, com.ash.drishti.server.notify.NotifyChannels notify,
            com.ash.drishti.server.notify.PhoneBook phones, @Value("${drishti.branding.product:}") String product) {
        String from = collab.email().from();
        String host = from.contains("@") ? from.substring(from.indexOf('@') + 1) : "localhost";
        JavaMailSender smtp = sender.getIfAvailable();
        MailTransport transport = smtp != null ? MailTransport.smtp(smtp) : (m, f) -> {
            throw new IllegalStateException("no SMTP host: set spring.mail.host");
        };
        SubscriptionService.Directory directory = new SubscriptionService.Directory() {
            @Override
            public Optional<SubscriptionService.Person> person(String username) {
                return users.find(username).map(this::of);
            }

            @Override
            public List<SubscriptionService.Person> members(String role) {
                return users.list(null).stream().filter(u -> u.enabled() && u.roles().contains(role)).map(this::of).toList();
            }

            @Override
            public boolean mayAddOthers(String username) {
                var p = principals.of(username);
                return entitlements.isAdmin(p) || bi.mayAuthor(p);
            }

            private SubscriptionService.Person of(User u) {
                return new SubscriptionService.Person(u.username(), u.displayName() == null || u.displayName().isBlank() ? u.username() : u.displayName(), u.email(),
                        u.enabled(), principals.of(u.username()));
            }
        };
        SubscriptionService s = new SubscriptionService(store, exports::render, exports::check, directory, audit,
                new MailRenderer(new MailTemplates(collab.email().templatesDir()), product, host), transport, from, collab.consoleUrl(), props.subscriptions(),
                RetryPolicy.of(env::getProperty, "distribution"), dates, Clock.systemUTC(), true);
        com.ash.drishti.server.bi.ai.BiAiService aiService = ai.getIfAvailable();
        s.with(aiService == null ? null : new AiNarrator(aiService, props.subscriptions().narrativeRows()), new SubscriptionService.Outbound() {
            @Override
            public List<String> webhookNames() {
                return notify.webhookNames();
            }

            @Override
            public boolean smsConfigured() {
                return notify.smsConfigured();
            }

            @Override
            public Optional<String> phone(String username) {
                return phones.phone(username);
            }

            @Override
            public void webhook(String name, com.fasterxml.jackson.databind.node.ObjectNode payload) {
                notify.webhook(name, "subscription", payload);
            }

            @Override
            public void sms(String phone, String text) {
                notify.sms(phone, text);
            }
        });
        loads.onReady(r -> s.onLoad(r.kind(), r.businessDate()));          // the data-load signal: "after-load" subscriptions run shortly after
        return s;
    }
}
