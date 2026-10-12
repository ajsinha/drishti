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

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Alerts on measures, fields and board tiles ({@code drishti.alerts.measure}).
 *
 * @param enabled false stops the evaluator and refuses new alerts and board notifications (stored alerts are kept)
 * @param perUser most alerts one person owns
 * @param tick how often scheduled alerts, "no data" deadlines and the wait after a data load are looked at
 * @param loadDelay after a data load, wait this long before the alerts that follow loads are evaluated, so the BI layers rebuild first
 * @param livePoll how often the live tables behind live alerts are looked at for a change
 * @param liveDebounce default quiet time a live alert waits after the last change before it evaluates (the alert may set its own)
 * @param minDebounce shortest debounce an alert may ask for
 * @param liveMaxWait while changes keep arriving, a live alert still evaluates at least this often
 * @param minGap shortest time between two evaluations of one alert for one person, whatever the trigger
 * @param cooldown default time after an alert fires before the same alert may fire again for the same person
 * @param keepHistory events kept in an alert's history
 * @param boards false turns board tile notifications off
 * @param maxAudience most people one board tile notifies (the board's readers); over it the tile notifies nobody and says so
 * @param email false stops alert email whatever an alert asks for
 * @param bridges false stops chat bridge posts whatever an alert asks for
 * @param maxAttempts a failed delivery by email, webhook or text is tried this many times in all, with the shared retry policy ({@code drishti.retry.alerts.*}) between tries
 * @param webhooks false stops webhook posts whatever an alert asks for
 * @param sms false stops text messages whatever an alert asks for
 */
@ConfigurationProperties("drishti.alerts.measure")
public record MeasureAlertProperties(Boolean enabled, Integer perUser, Duration tick, Duration loadDelay, Duration livePoll, Duration liveDebounce, Duration minDebounce,
        Duration liveMaxWait, Duration minGap, Duration cooldown, Integer keepHistory, Boolean boards, Integer maxAudience, Boolean email, Boolean bridges, Integer maxAttempts, Boolean webhooks, Boolean sms) {

    public MeasureAlertProperties(Boolean enabled, Integer perUser, Duration tick, Duration loadDelay, Duration livePoll, Duration liveDebounce, Duration minDebounce,
            Duration liveMaxWait, Duration minGap, Duration cooldown, Integer keepHistory, Boolean boards, Integer maxAudience, Boolean email, Boolean bridges) {
        this(enabled, perUser, tick, loadDelay, livePoll, liveDebounce, minDebounce, liveMaxWait, minGap, cooldown, keepHistory, boards, maxAudience, email, bridges, null, null, null);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public MeasureAlertProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        perUser = perUser == null ? 20 : Math.max(1, perUser);
        tick = tick == null ? Duration.ofSeconds(15) : tick;
        loadDelay = loadDelay == null ? Duration.ofSeconds(5) : loadDelay;
        livePoll = livePoll == null ? Duration.ofSeconds(2) : livePoll;
        liveDebounce = liveDebounce == null ? Duration.ofSeconds(10) : liveDebounce;
        minDebounce = minDebounce == null ? Duration.ofSeconds(1) : minDebounce;
        liveMaxWait = liveMaxWait == null ? Duration.ofMinutes(2) : liveMaxWait;
        minGap = minGap == null ? Duration.ofSeconds(5) : minGap;
        cooldown = cooldown == null ? Duration.ofMinutes(30) : cooldown;
        keepHistory = keepHistory == null ? 50 : Math.max(5, keepHistory);
        boards = boards == null ? Boolean.TRUE : boards;
        maxAudience = maxAudience == null ? 300 : Math.max(1, maxAudience);
        email = email == null ? Boolean.TRUE : email;
        bridges = bridges == null ? Boolean.TRUE : bridges;
        maxAttempts = maxAttempts == null ? 4 : Math.max(1, maxAttempts);
        webhooks = webhooks == null ? Boolean.TRUE : webhooks;
        sms = sms == null ? Boolean.TRUE : sms;
    }

    /** All defaults. */
    public static MeasureAlertProperties defaults() {
        return new MeasureAlertProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
