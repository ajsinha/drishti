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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a person may pick for alerts and subscriptions besides mail: {@code GET /api/v1/notify/options} lists the webhooks an administrator defined (names only)
 * and whether a text-message gateway is set up and the caller has a number; {@code PUT /api/v1/me/phone} sets or clears the caller's own number.
 */
@RestController
public class NotifyController {

    private final NotifyChannels channels;
    private final PhoneBook phones;

    public NotifyController(NotifyChannels channels, PhoneBook phones) {
        this.channels = channels;
        this.phones = phones;
    }

    @GetMapping("/api/v1/notify/options")
    public Map<String, Object> options(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("webhooks", channels.webhookNames());
        Map<String, Object> sms = new LinkedHashMap<>();
        sms.put("configured", channels.smsConfigured());
        sms.put("maxChars", channels.settings().sms().maxChars());
        sms.put("phone", phones.phone(p.user()).map(PhoneBook::hide).orElse(""));
        m.put("sms", sms);
        return m;
    }

    /** Body {@code {phone}}: the caller's own number for texts; blank removes it. */
    @PutMapping("/api/v1/me/phone")
    public Map<String, Object> phone(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        try {
            phones.set(p.user(), body.path("phone").asText(""));
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        return Map.of("phone", phones.phone(p.user()).map(PhoneBook::hide).orElse(""));
    }
}
