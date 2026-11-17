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

import com.ash.drishti.kernel.api.module.PersonCache;

/**
 * The kernel's services to a module: one bean in the kernel's context, also {@code ModuleContext.kernel()}. Every answer is what the REST
 * path it replaces answers for the same person (K2's parity test holds it), so a module computes from the same figures a client would
 * read, without an HTTP call to its own server.
 *
 * <p>{@link #person()} is read on the request's own thread; the {@link Person} it gives is a copy and may be used from any thread.
 */
public interface Kernel {

    /**
     * The person of the request being served on this thread.
     *
     * @throws IllegalStateException off a request
     */
    Person person();

    /** Records: a search ({@code /search}) and one record whole ({@code /entities/{kind}/{id}/raw}). */
    Records records();

    /** A field of a record over business days ({@code /history/{kind}/{id}/series}). */
    History history();

    /** The business dates ({@code /business-date}). */
    BusinessDates dates();

    /** A person's own settings ({@code /me/settings}). */
    PersonSettings settings();

    /** A search as a live result set ({@code /live/query}). */
    LiveQueries live();

    /** The pivot engine on rows a module holds ({@code /pivot/inline}). */
    Pivots pivots();

    /** The blocks a module keeps in a lens's file. */
    Lenses lenses();

    /** The doors a module's own reads pass: whether a person may open a kind, and the masks on what they see. */
    Access access();

    /** One module asking another: operations, by the consumer's own interface. */
    Operations operations();

    /**
     * A new {@link PersonCache} for what the module works out per person: at most {@code size} answers, each kept {@code ttlMillis}.
     * Every key holds the person's {@link Person#key()}.
     */
    <V> PersonCache<V> cache(int size, long ttlMillis);

    /**
     * The kernel's refusal of a typed call, with the status, code and words the REST path would have answered: what a panel says when it
     * cannot draw. Replaces {@code KernelCalls.Refused}.
     */
    final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final int status;
        private final String code;

        /**
         * A refusal.
         *
         * @param status the HTTP status the REST path answers ({@code 403}, {@code 404}, {@code 503})
         * @param code   the code ({@code DRS-…}), empty for none
         * @param detail the words
         */
        public Refused(int status, String code, String detail) {
            super(detail == null ? "" : detail);
            this.status = status;
            this.code = code == null ? "" : code;
        }

        /** The HTTP status the REST path answers. */
        public int status() {
            return status;
        }

        /** The code ({@code DRS-…}), empty for none. */
        public String code() {
            return code;
        }

        /** {@code DRS-4040 the words} (the status when there is no code): what a panel says when it cannot draw. */
        public String say() {
            return (code.isEmpty() ? String.valueOf(status) : code) + " " + getMessage();
        }
    }
}
