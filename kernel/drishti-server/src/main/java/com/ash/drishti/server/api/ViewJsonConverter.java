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

import com.ash.rachana.view.ViewJson;
import com.ash.rachana.view.ViewModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * The API answers a ViewModel with Rachana's canonical writer (SPLIT_PLAN 8.3g, C10): {@link ViewJson#write} as the raw JSON body, the
 * same bytes on every runtime, never Spring's {@code ObjectMapper}. Write only (no endpoint reads a ViewModel). Proven equal to the bytes
 * the server's Jackson wrote before the switch ({@code ViewJsonBytesTest}).
 */
public final class ViewJsonConverter extends AbstractHttpMessageConverter<ViewModel> {

    public ViewJsonConverter() {
        super(StandardCharsets.UTF_8, MediaType.APPLICATION_JSON, new MediaType("application", "*+json"));
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return ViewModel.class.isAssignableFrom(clazz);
    }

    @Override
    protected boolean canRead(MediaType mediaType) {
        return false;
    }

    @Override
    protected ViewModel readInternal(Class<? extends ViewModel> clazz, HttpInputMessage input) {
        throw new HttpMessageNotReadableException("a ViewModel is never read", input);
    }

    @Override
    protected void writeInternal(ViewModel view, HttpOutputMessage output) throws IOException {
        output.getBody().write(ViewJson.write(view).getBytes(StandardCharsets.UTF_8));
    }
}
