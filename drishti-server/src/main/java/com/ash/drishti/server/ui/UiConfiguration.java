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
package com.ash.drishti.server.ui;

import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.server.bi.BiAccess;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** The UI Board's Chip Store and saved boards (UI_BOARD.md, phase P2), over the content store. */
@Configuration
public class UiConfiguration {

    @Bean
    public UiSettings uiSettings(Environment env) {
        return UiSettings.load(Path.of(env.getProperty("drishti.ui.dir", "./config/ui")));
    }

    @Bean
    public UiAccess uiAccess(Entitlements entitlements, BiAccess bi, ObjectProvider<BiService> biService, ObjectProvider<UserService> users) {
        return new UiAccess() {
            @Override
            public boolean mayView(Principal p) {
                return p != null;
            }

            @Override
            public boolean maySave(Principal p) {
                return entitlements.mayLayout(p) || entitlements.isAdmin(p);
            }

            @Override
            public boolean mayAuthor(Principal p) {
                return entitlements.mayAuthor(p) || entitlements.isAdmin(p);
            }

            @Override
            public boolean mayReview(Principal p) {
                return entitlements.mayApprove(p);
            }

            @Override
            public boolean mayCertify(Principal p) {
                return bi.has(p, BiAccess.STEWARD) || entitlements.isAdmin(p);
            }

            @Override
            public boolean isAdmin(Principal p) {
                return entitlements.isAdmin(p);
            }

            @Override
            public String workspaceRole(String workspace, Principal p) {
                BiService s = biService.getIfAvailable();
                return s == null ? null : s.workspaceRole(workspace, p);
            }

            @Override
            public void audit(Principal p, String action, String subject, String detail) {
                UserService u = users.getIfAvailable();
                if (u != null) {
                    u.recordAudit(p.user(), action, subject, detail);
                }
            }
        };
    }

    @Bean
    public ChipStore chipStore(ContentStore content, UiAccess access, UiSettings settings) {
        return new ChipStore(content, access, settings.chipsDir());
    }

    @Bean
    public BoardStore boardStore(ContentStore content, ChipStore chips, UiAccess access, UiSettings settings) {
        return new BoardStore(content, chips, access, settings);
    }
}
