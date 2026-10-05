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

import com.ash.drishti.identity.collab.OutboxItem;
import com.ash.drishti.server.security.Principal;

/**
 * Turns one outbox row of a template into a message, at send time, for the recipient's rights then. A new kind of email (a mention,
 * a reply) is a new bean of this type; the dispatcher needs no change.
 */
public interface ItemRenderer {

    /** Thrown when the message must not be sent (the share is gone, the recipient can no longer reach it): the row is cancelled. */
    final class Skip extends RuntimeException {
        public Skip(String reason) {
            super(reason, null, false, false);
        }
    }

    /** The outbox template this renders: {@code share}, {@code test}, ... */
    String template();

    /** The content for the recipient. {@code who} holds the recipient's roles now. Throws {@link Skip} when nothing should be sent. */
    MailRenderer.Content content(OutboxItem item, Principal who);
}
