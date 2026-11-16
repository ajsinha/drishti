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

/**
 * A message ready to send: one recipient address, a one-line subject, a text part and an HTML part, its {@code Message-ID}, for a share with a
 * picture the PNG to attach (null otherwise) and, for a subscription, files to attach (a PDF or a workbook).
 */
public record RenderedMail(String to, String subject, String text, String html, String messageId, byte[] image, java.util.List<Attachment> files) {

    /** A file attached to the message. */
    public record Attachment(String name, String type, byte[] bytes) {}

    public RenderedMail {
        files = files == null ? java.util.List.of() : java.util.List.copyOf(files);
    }

    public RenderedMail(String to, String subject, String text, String html, String messageId, byte[] image) {
        this(to, subject, text, html, messageId, image, null);
    }

    public RenderedMail(String to, String subject, String text, String html, String messageId) {
        this(to, subject, text, html, messageId, null, null);
    }
}
