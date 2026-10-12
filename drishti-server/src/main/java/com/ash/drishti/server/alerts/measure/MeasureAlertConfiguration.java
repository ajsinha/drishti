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
package com.ash.drishti.server.alerts.measure;

import com.ash.drishti.engine.live.TopicHub;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.DatasetService;
import com.ash.drishti.server.bi.board.BiBoardService;
import com.ash.drishti.server.bi.report.BiReportService;
import com.ash.drishti.server.collab.InboxHub;
import com.ash.drishti.server.collab.InboxService;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.collab.bridge.BridgeItemRenderer;
import com.ash.drishti.server.collab.bridge.BridgeMessage;
import com.ash.drishti.server.collab.bridge.BridgeNotifier;
import com.ash.drishti.server.collab.mail.MailRenderer;
import com.ash.drishti.server.collab.mail.MailTemplates;
import com.ash.drishti.server.collab.mail.MailTransport;
import com.ash.drishti.server.dist.SubscriptionService.Person;
import com.ash.drishti.server.loads.LoadService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.SecurityProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/** Alerts on measures, fields and board tiles ({@code drishti.alerts.measure}): the service and its wiring to the BI engine, the inbox, mail, the chat bridges and the load signal. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MeasureAlertProperties.class)
public class MeasureAlertConfiguration {

    @Bean
    public FieldValueReader fieldValueReader(SourceRouter router, ElCompiler el, Formats formats, Entitlements entitlements) {
        return new FieldValueReader(router, el, formats, entitlements);
    }

    @Bean
    public PythonAlertReader pythonAlertReader(DatasetService datasets, BiReportService reports, com.ash.drishti.server.bi.python.PythonItems items,
            com.ash.drishti.server.bi.transform.TransformRunner runner, com.ash.drishti.server.bi.report.BiReportProperties report) {
        return new PythonAlertReader(datasets, reports, items, runner, report.pythonRows());
    }

    @Bean
    public BiValueReader biValueReader(BiReportService reports, DatasetService datasets, BiBoardService boards, PythonAlertReader python,
            com.ash.drishti.server.bi.mq.ModelCatalog models, com.ash.drishti.server.bi.mq.MqService mq) {
        return new BiValueReader(reports, datasets, boards, python).withModels(models, mq);
    }

    @Bean(destroyMethod = "close")
    @SuppressWarnings("java:S107")
    public ServerAlertSources serverAlertSources(BiReportService reports, DatasetService datasets, BiBoardService boards, BiService bi, Entitlements entitlements,
            FieldValueReader fields, SourceRouter router, TopicHub hub, com.ash.drishti.server.bi.mq.ModelCatalog models, com.ash.drishti.server.bi.mq.MqService mq) {
        return new ServerAlertSources(reports, datasets, boards, bi, entitlements, fields, router, hub, Clock.systemUTC()).withModels(models, mq);
    }

    @Bean
    public BoardAlertSource boardAlertSource(BiService bi, UserService users, Principals principals, MeasureAlertProperties props) {
        return new BoardAlertSource(bi, users, principals, props, Clock.systemUTC());
    }

    @Bean
    @SuppressWarnings("java:S107")
    public AlertChannels alertChannels(InboxService inbox, InboxHub hub, ObjectProvider<JavaMailSender> sender, CollabProperties collab, ObjectProvider<BridgeNotifier> bridges,
            com.ash.drishti.server.notify.NotifyChannels notify, com.ash.drishti.server.notify.PhoneBook phones,
            @Value("${drishti.branding.product:Drishti}") String product) {
        String from = collab.email().from();
        String host = from.contains("@") ? from.substring(from.indexOf('@') + 1) : "localhost";
        JavaMailSender smtp = sender.getIfAvailable();
        MailTransport transport = smtp != null ? MailTransport.smtp(smtp) : (m, f) -> {
            throw new IllegalStateException("no SMTP host: set spring.mail.host");
        };
        return new ServerAlertChannels(inbox, hub, new MailRenderer(new MailTemplates(collab.email().templatesDir()), product, host), transport, from, collab.consoleUrl(),
                bridges.getIfAvailable()).with(notify, phones);
    }

    @Bean(destroyMethod = "close")
    @SuppressWarnings("java:S107")
    public MeasureAlertService measureAlertService(PreferenceStore store, BiValueReader bi, FieldValueReader fields, ServerAlertSources sources, BoardAlertSource boards,
            AlertChannels channels, UserService users, Principals principals, SecurityProperties security, AuditLog audit, MeasureAlertProperties props, BusinessDates dates, LoadService loads,
            ObjectProvider<BridgeItemRenderer> renderer, CollabProperties collab, org.springframework.core.env.Environment env, @Value("${drishti.branding.product:Drishti}") String product) {
        ValueReader reader = (spec, who, date) -> spec.source().type().equals("field") ? fields.read(spec, who, date) : bi.read(spec, who, date);
        // with security off the signed-in name need not be a user of the directory: it is still a person to alert (no email address, the rights security-off gives)
        AlertEnvironment.People people = name -> users.find(name)
                .map(u -> new Person(u.username(), u.displayName() == null || u.displayName().isBlank() ? u.username() : u.displayName(), u.email(), u.enabled(),
                        principals.of(u.username())))
                .or(() -> security.enabled() ? Optional.empty() : Optional.of(new Person(name, name, "", true, principals.of(name))));
        MeasureAlertService s = new MeasureAlertService(store, reader, sources, people, sources, boards, channels, audit, props, dates, Clock.systemUTC(), true);
        s.retryWith(com.ash.drishti.common.RetryPolicy.of(env::getProperty, "alerts"));       // drishti.retry.alerts.*
        loads.onReady(r -> s.onLoad(r.kind(), r.businessDate()));                      // the data-load signal
        BridgeItemRenderer br = renderer.getIfAvailable();
        if (br != null) {
            String base = collab.consoleUrl() == null ? "" : collab.consoleUrl().replaceAll("/+$", "");
            br.alertPosts(ref -> s.bridgeFacts(ref).map(f -> new BridgeMessage("alert", ref.substring(ref.lastIndexOf('|') + 1), product, "Alert fired: " + f.get("title"),
                    "Alert", null, f.get("label"), null, "Condition: " + f.get("condition") + " · severity " + f.get("severity") + " · " + f.get("owner") + ". Open it to see the number you are allowed to see.",
                    base + f.get("link"), Instant.now())));
        }
        return s;
    }
}
