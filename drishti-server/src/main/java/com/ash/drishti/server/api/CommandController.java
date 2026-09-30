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
package com.ash.drishti.server.api;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.command.CommandParser;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.engine.command.Suggestion;
import com.ash.drishti.engine.command.SuggestionService;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The command line: parsing {@code <MNEMONIC> <ID> <GO>} and the type-ahead dropdown. */
@RestController
@RequestMapping("/api/v1/command")
public class CommandController {

    private final CommandParser parser;
    private final SuggestionService suggestions;
    private final Mnemonics mnemonics;
    private final Entitlements entitlements;

    public CommandController(CommandParser parser, SuggestionService suggestions, Mnemonics mnemonics, Entitlements entitlements) {
        this.parser = parser;
        this.suggestions = suggestions;
        this.mnemonics = mnemonics;
        this.entitlements = entitlements;
    }

    @PostMapping
    public ApiDtos.CommandResponse command(@RequestBody ApiDtos.CommandRequest req,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        EntityRef ref = parser.require(req.text());
        entitlements.requireOpen(principal, ref.kind());
        return new ApiDtos.CommandResponse(new ViewModel.Ref(ref.kind(), ref.id()), mnemonics.codeFor(ref.kind()));
    }

    @GetMapping("/suggest")
    public List<Suggestion> suggest(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) Integer limit, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        return entitlements.filter(principal, suggestions.suggest(q, principal.user(), limit, asOf));
    }
}
