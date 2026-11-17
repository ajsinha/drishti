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
package com.ash.drishti.kernel.api.services;

import com.ash.drishti.api.AsOf;
import java.time.LocalDate;
import java.util.List;

/**
 * The person a typed call is made for: who they are, their roles, the lens they work in and their date box. A copy of what the request
 * carried, usable from any thread; nothing of it is held in a static or a cache shared between people (the session-isolation rule).
 * Every kernel service takes it explicitly; none reads a thread local.
 */
public interface Person {

    /** The person's user name. */
    String user();

    /** Their display name as the identity store holds it ("" when it holds none). */
    String display();

    /** Whether the server's role definitions make them an administrator. */
    boolean admin();

    /** Their roles, as the request carried them. */
    List<String> roles();

    /** The lens they work in ("" for none). */
    String lens();

    /** Their date box: the business date and, when set, the known-at instant. */
    AsOf asOf();

    /**
     * What tells one person's answers from another's and one view of the data from another (user, roles, lens, date box, known-at): a
     * cache keyed by it ({@code PersonCache}) is never shared between people.
     */
    String key();

    /** The same person with their date box on another business date (the day before, a week ago). */
    Person onDate(LocalDate day);
}
