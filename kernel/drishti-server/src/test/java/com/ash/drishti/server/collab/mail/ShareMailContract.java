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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.identity.collab.OutboxItem;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.MediaType;

/**
 * Email end to end, over the whole stack (security on, banking packs) against an in-process SMTP server (GreenMail, never a real
 * one): a share is queued in the outbox with the share and sent by the worker; each recipient's message is built for their rights
 * (the masked copy of a value is the mask for a trader and plain for a risk officer), carries the id, the note and the link and no
 * figure, escapes what a sender typed, honours a person's opt-out, survives an SMTP outage by retrying, and the administrator sees
 * pending and dead letters and can send a test. Run on both stores.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ShareMailContract extends ShareMailSupport {

    // ---- tests ------------------------------------------------------------------------------------------------------------

    @Test
    void eachRecipientGetsTheNoteAsTheyMayReadItTheIdAndTheLinkButNoFigures() throws Exception {
        smtp().purgeEmailFromAllMailboxes();
        JsonNode res = json.readTree(share(SECRET + " says the curve moved", "ravi", "rng", "noaddr").andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        String link = CONSOLE + "/share/" + res.get("id").asText();

        MimeMessage ravi = mailTo("ravi@desk.test", 1).get(0);
        String text = part(ravi, "text/plain");
        assertThat(ravi.getSubject()).isEqualTo("Drishti: ANN Person shared a view with you");
        assertThat(text).contains("Trade " + TRADE, "••• says the curve moved", link)
                .doesNotContain(SECRET);
        assertThat(part(ravi, "text/html")).contains("•••", link).doesNotContain(SECRET);
        assertThat(ravi.getHeader("Auto-Submitted")).containsExactly("auto-generated");
        assertThat(ravi.getHeader("Message-ID")[0]).matches("<\\d+\\.sh_[0-9A-Za-z]+@localhost>");

        MimeMessage rng = mailTo("rng@desk.test", 1).get(0);
        assertThat(part(rng, "text/plain")).contains(SECRET + " says the curve moved", link);

        assertThat(mailTo("noaddr@desk.test", 0)).as("a person with no address is told in Drishti only").isEmpty();
        assertThat(mailTo("ann@desk.test", 0)).as("never to the sender").isEmpty();
        // no value of the data: the document's own figures and names are not in any message
        for (MimeMessage m : List.of(ravi, rng)) {
            // share ids are random (ULIDs): one may spell "MTM", so they are taken out before looking for the data's words
            String body = (part(m, "text/plain") + part(m, "text/html")).replaceAll("sh_[0-9A-Z]+", "sh_ID");
            assertThat(body).doesNotContain("MTM", "notional", "counterparty");
        }
    }

    @Test
    void whatTheSenderTypedIsEscapedAndNeverExpanded() throws Exception {
        smtp().purgeEmailFromAllMailboxes();
        share("<script>alert(1)</script> ${product} {{x}} <b>", "ravi").andExpect(status().isCreated());
        MimeMessage m = mailTo("ravi@desk.test", 1).get(0);
        assertThat(part(m, "text/html")).contains("&lt;script&gt;alert(1)&lt;/script&gt;", "${product}", "&lt;b&gt;").doesNotContain("<script>");
        assertThat(part(m, "text/plain")).contains("${product}", "{{x}}");
    }

    @Test
    void aPersonWhoTurnedShareMailOffGetsNoneButTheBellStillRings() throws Exception {
        smtp().purgeEmailFromAllMailboxes();
        mvc.perform(patch("/api/v1/me/settings").header("Authorization", as("optout", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"notify\":{\"email\":{\"share\":false}}}")).andExpect(status().isOk());
        share("for both of you", "optout", "ravi").andExpect(status().isCreated());
        assertThat(mailTo("ravi@desk.test", 1)).hasSize(1);
        Thread.sleep(500);
        assertThat(mailTo("optout@desk.test", 0)).isEmpty();
        String inbox = mvc.perform(get("/api/v1/me/inbox").header("Authorization", as("optout", "risk"))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(inbox).contains(TRADE);
    }

    @Test
    void settingsRejectsAnUnknownNotification() throws Exception {
        mvc.perform(patch("/api/v1/me/settings").header("Authorization", as("ravi", "trader")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"notify\":{\"email\":{\"spam\":true}}}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me/settings").header("Authorization", as("ravi", "trader"))).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.notify.email.share").value(true));
    }

    @Test
    void anOutageLeavesTheMessagePendingAndItIsSentWhenTheServerReturns() throws Exception {
        smtp().purgeEmailFromAllMailboxes();
        smtp().stop();
        try {
            share("sent while the mail server is down", "ravi").andExpect(status().isCreated());
            long end = System.currentTimeMillis() + 20_000;
            OutboxItem tried = null;
            while (System.currentTimeMillis() < end && tried == null) {
                tried = outbox.list(OutboxItem.PENDING, 50).stream().filter(i -> i.attempts() >= 1 && "ravi".equals(i.recipient())).findFirst().orElse(null);
                Thread.sleep(50);
            }
            assertThat(tried).as("a failed attempt is recorded and the row stays pending").isNotNull();
            assertThat(tried.lastError()).isNotBlank();
        } finally {
            smtp().start();
        }
        assertThat(mailTo("ravi@desk.test", 1)).hasSize(1);
        assertThat(part(mailTo("ravi@desk.test", 1).get(0), "text/plain")).contains("sent while the mail server is down");
    }

    @Test
    void theAdministratorSeesDeadLettersSendsThemAgainAndMailsATest() throws Exception {
        String boss = as("boss", "admin");
        OutboxItem dead = outbox.add(OutboxItem.pending("email", "ravi", "no-such-template", "x_1", Instant.now()));
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end && !OutboxItem.DEAD.equals(outbox.find(dead.seq()).orElseThrow().state())) {
            Thread.sleep(50);
        }
        JsonNode list = json.readTree(mvc.perform(get("/api/v1/admin/collab/outbox").param("state", "dead").header("Authorization", boss))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(list.get("available").asBoolean()).isTrue();
        assertThat(list.get("counts").get("dead").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(list.get("items").findValuesAsText("seq")).contains(String.valueOf(dead.seq()));
        assertThat(list.toString()).as("rows carry no message content").doesNotContain(SECRET);

        mvc.perform(post("/api/v1/admin/collab/outbox/" + dead.seq() + "/retry").header("Authorization", boss)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/collab/outbox/999999/retry").header("Authorization", boss)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/collab/outbox").header("Authorization", as("ravi", "trader"))).andExpect(status().isForbidden());

        smtp().purgeEmailFromAllMailboxes();
        mvc.perform(post("/api/v1/admin/collab/mail-test").header("Authorization", boss)).andExpect(status().isOk());
        MimeMessage t = mailTo("boss@desk.test", 1).get(0);
        assertThat(t.getSubject()).isEqualTo("Drishti: Test message from Drishti");
        assertThat(part(t, "text/plain")).contains("contains no data");
        mvc.perform(post("/api/v1/admin/collab/mail-test").header("Authorization", as("ravi", "trader"))).andExpect(status().isForbidden());
    }
}
