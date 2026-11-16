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
package com.ash.drishti.server.collab.mail;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.identity.collab.OutboxStore;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Users, the share call and reading what GreenMail received, for the email tests. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ShareMailSupport {

    static final String TRADE = "IRS-48213";
    static final String SECRET = "A. Shah";
    static final String CONSOLE = "https://drishti.test";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired OutboxStore outbox;
    final ObjectMapper json = new ObjectMapper();

    abstract GreenMailExtension smtp();

    static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    void user(String name, List<String> roles, String email) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("username", name, "displayName", name.toUpperCase() + " Person", "roles", roles,
                "packs", List.of("finance"), "password", "long-enough-pass-1"));
        if (email != null) {
            body.put("email", email);
        }
        mvc.perform(post("/api/v1/admin/users").header("Authorization", as("drishti-dev-admin", "admin")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isCreated());
    }

    @BeforeAll
    void users() throws Exception {
        user("ann", List.of("risk"), "ann@desk.test");
        user("ravi", List.of("trader"), "ravi@desk.test");
        user("rng", List.of("risk"), "rng@desk.test");
        user("optout", List.of("risk"), "optout@desk.test");
        user("noaddr", List.of("risk"), null);
        user("boss", List.of("admin"), "boss@desk.test");
    }

    ResultActions share(String note, String... to) throws Exception {
        return mvc.perform(post("/api/v1/shares").header("Authorization", as("ann", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kind", "trade", "id", TRADE, "note", note, "generation", 0, "channels",
                        Map.of("inApp", true, "email", true), "to", Map.of("users", List.of(to), "roles", List.of())))));
    }

    // ---- reading what arrived ---------------------------------------------------------------------------------------------

    static String part(Part p, String type) throws Exception {
        if (p.isMimeType(type)) {
            return String.valueOf(p.getContent());
        }
        if (p.isMimeType("multipart/*")) {
            Multipart m = (Multipart) p.getContent();
            for (int i = 0; i < m.getCount(); i++) {
                String r = part(m.getBodyPart(i), type);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** The messages for this address, waiting up to 20 s for {@code n}. */
    List<MimeMessage> mailTo(String address, int n) throws Exception {
        long end = System.currentTimeMillis() + 20_000;
        while (true) {
            List<MimeMessage> got = new ArrayList<>();
            for (MimeMessage m : smtp().getReceivedMessages()) {
                if (m.getAllRecipients() != null && m.getAllRecipients()[0].toString().equals(address)) {
                    got.add(m);
                }
            }
            if (got.size() >= n || System.currentTimeMillis() > end) {
                return got;
            }
            Thread.sleep(50);
        }
    }

}
