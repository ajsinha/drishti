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
package com.ash.drishti.server.notify;

import com.ash.drishti.identity.PreferenceStore;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Webhook and text-message channels ({@code drishti.notify.*}), shared by alerts and subscriptions. */
@Configuration(proxyBeanMethods = false)
public class NotifyConfiguration {

    @Bean
    public NotifySettings notifySettings(Environment env) {
        Map<String, String> flat = Binder.get(env).bind("drishti.notify", Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        return NotifySettings.of(flat);
    }

    @Bean
    public NotifyChannels notifyChannels(NotifySettings settings) {
        return new NotifyChannels(settings, Clock.systemUTC());
    }

    @Bean
    public PhoneBook phoneBook(PreferenceStore store) {
        return new PhoneBook(store);
    }

    @Bean
    public NotifyController notifyController(NotifyChannels channels, PhoneBook phones) {
        return new NotifyController(channels, phones);
    }
}
