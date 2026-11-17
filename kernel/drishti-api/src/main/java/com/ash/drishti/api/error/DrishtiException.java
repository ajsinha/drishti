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
package com.ash.drishti.api.error;

/** The single exception type crossing module boundaries. It always carries an {@link ErrorCode}. */
public class DrishtiException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final transient ErrorCode errorCode;

    /** An error of the given code with its message. */
    public DrishtiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** An error of the given code with its message and the cause. */
    public DrishtiException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** The error code this exception carries. */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** The code, a space and the message. */
    @Override
    public String getMessage() {
        return errorCode.code() + " " + super.getMessage();
    }
}
