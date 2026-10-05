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

import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** Sends a rendered message. The only implementation talks SMTP through Spring's {@link JavaMailSender}; tests replace it. */
public interface MailTransport {

    /** Sends; throws a {@link org.springframework.mail.MailException} (or another runtime exception) when it could not. */
    void send(RenderedMail mail, String from);

    /** SMTP, as configured by the standard {@code spring.mail.*} keys. */
    static MailTransport smtp(JavaMailSender sender) {
        return (mail, from) -> {
            try {
                String id = mail.messageId();
                MimeMessage m = new MimeMessage(sender.createMimeMessage().getSession()) {
                    @Override
                    protected void updateMessageID() throws jakarta.mail.MessagingException {
                        setHeader("Message-ID", id);
                    }
                };
                MimeMessageHelper h = new MimeMessageHelper(m, true, "UTF-8");
                h.setFrom(from);
                h.setTo(mail.to());
                h.setSubject(mail.subject());
                h.setText(mail.text(), mail.html());
                if (mail.image() != null) {
                    h.addAttachment("drishti-snapshot.png", new org.springframework.core.io.ByteArrayResource(mail.image()), "image/png");
                }
                m.setHeader("Auto-Submitted", "auto-generated");
                m.setHeader("X-Auto-Response-Suppress", "All");
                sender.send(m);
            } catch (jakarta.mail.MessagingException e) {
                throw new org.springframework.mail.MailPreparationException(e);
            }
        };
    }
}
