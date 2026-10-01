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
package com.ash.drishti.deltalake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The path rules for Windows and for Linux, checked as strings on any machine. */
class LocalPathsTest {

    @Test
    void kernelUrisBecomeWindowsPaths() {
        assertThat(LocalPaths.toLocal("file:/C:/data/delta/banking/trade", true)).isEqualTo("C:\\data\\delta\\banking\\trade");
        assertThat(LocalPaths.toLocal("file:///C:/data/delta/trade/_delta_log/00000000000000000000.json", true))
                .isEqualTo("C:\\data\\delta\\trade\\_delta_log\\00000000000000000000.json");
        assertThat(LocalPaths.toLocal("file://C:/data/x", true)).isEqualTo("C:\\data\\x");
        assertThat(LocalPaths.toLocal("file:/D:/My Lake/trade/business_date=2026-09-30/part-0.parquet", true))
                .isEqualTo("D:\\My Lake\\trade\\business_date=2026-09-30\\part-0.parquet");
        assertThat(LocalPaths.toLocal("file:/c:/", true)).isEqualTo("c:\\");
        assertThat(LocalPaths.toLocal("file:/C:", true)).isEqualTo("C:\\");
    }

    @Test
    void plainWindowsPathsStayWindowsPaths() {
        assertThat(LocalPaths.toLocal("C:\\data\\delta", true)).isEqualTo("C:\\data\\delta");
        assertThat(LocalPaths.toLocal("C:/data/delta", true)).isEqualTo("C:\\data\\delta");
        assertThat(LocalPaths.toLocal("data\\delta", true)).isEqualTo("data\\delta");
        assertThat(LocalPaths.toLocal("./data/delta", true)).isEqualTo(".\\data\\delta");
        assertThat(LocalPaths.toLocal("\\\\fileserver\\lakes\\banking", true)).isEqualTo("\\\\fileserver\\lakes\\banking");
    }

    @Test
    void uncSharesRoundTrip() {
        assertThat(LocalPaths.toLocal("file://fileserver/lakes/banking/trade", true)).isEqualTo("\\\\fileserver\\lakes\\banking\\trade");
        assertThat(LocalPaths.toUri("\\\\fileserver\\lakes\\banking\\trade", true)).isEqualTo("file://fileserver/lakes/banking/trade");
        assertThat(LocalPaths.toLocal(LocalPaths.toUri("\\\\fs\\share\\x", true), true)).isEqualTo("\\\\fs\\share\\x");
    }

    @Test
    void windowsPathsBecomeKernelUris() {
        assertThat(LocalPaths.toUri("C:\\data\\delta\\banking\\trade", true)).isEqualTo("file:/C:/data/delta/banking/trade");
        assertThat(LocalPaths.toUri("C:\\data\\delta\\", true)).isEqualTo("file:/C:/data/delta");
        assertThat(LocalPaths.toUri("C:\\", true)).isEqualTo("file:/C:/");
        assertThat(LocalPaths.toUri("C:\\Users\\John Smith\\drishti", true)).isEqualTo("file:/C:/Users/John Smith/drishti");
        // what Kernel then builds from the root is opened as the same file
        String child = LocalPaths.toUri("C:\\lake\\trade", true) + "/_delta_log/00000000000000000001.json";
        assertThat(LocalPaths.toLocal(child, true)).isEqualTo("C:\\lake\\trade\\_delta_log\\00000000000000000001.json");
        assertThatThrownBy(() -> LocalPaths.toUri("lake\\trade", true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encodedUrisFromJavaNioAreDecoded() {
        assertThat(LocalPaths.decodeUri("file:///C:/Users/John%20Smith/lake/trade/")).isEqualTo("file:/C:/Users/John Smith/lake/trade/");
        assertThat(LocalPaths.toLocal(LocalPaths.decodeUri("file:///C:/Users/John%20Smith/lake/"), true))
                .isEqualTo("C:\\Users\\John Smith\\lake\\");
        assertThat(LocalPaths.decodeUri("file:/home/me/100%/lake")).isEqualTo("file:/home/me/100%/lake");   // not encoded
        assertThat(LocalPaths.decodeUri("file:/C:/plain")).isEqualTo("file:/C:/plain");
        assertThat(LocalPaths.decodeUri("s3a://bucket/a%20b")).isEqualTo("s3a://bucket/a%20b");
    }

    @Test
    void linuxPathsAndUris() {
        assertThat(LocalPaths.toLocal("file:/home/me/lake/trade", false)).isEqualTo("/home/me/lake/trade");
        assertThat(LocalPaths.toLocal("file:///home/me/lake", false)).isEqualTo("/home/me/lake");
        assertThat(LocalPaths.toLocal("file://localhost/home/me/lake", false)).isEqualTo("/home/me/lake");
        assertThat(LocalPaths.toLocal("/home/me/lake", false)).isEqualTo("/home/me/lake");
        assertThat(LocalPaths.toUri("/home/me/lake/trade/", false)).isEqualTo("file:/home/me/lake/trade");
        assertThat(LocalPaths.toUri("/", false)).isEqualTo("file:/");
    }

    @Test
    void schemesAndDrivesAreTold() {
        assertThat(LocalPaths.isLocal("C:\\lake")).isTrue();
        assertThat(LocalPaths.isLocal("c:/lake")).isTrue();
        assertThat(LocalPaths.isLocal("file:/C:/lake")).isTrue();
        assertThat(LocalPaths.isLocal("FILE:///home/me")).isTrue();
        assertThat(LocalPaths.isLocal("./data/delta")).isTrue();
        assertThat(LocalPaths.isLocal("lake/with:colon")).isTrue();
        assertThat(LocalPaths.isLocal("s3a://bucket/lake")).isFalse();
        assertThat(LocalPaths.scheme("s3a://bucket/lake")).isEqualTo("s3a");
        assertThat(LocalPaths.scheme("S3://bucket/lake")).isEqualTo("s3");
        assertThat(LocalPaths.scheme("abfs://c@acct.dfs.core.windows.net/lake")).isEqualTo("abfs");
        assertThat(LocalPaths.scheme("D:\\lake")).isEqualTo("file");
        assertThat(NativeEngine.supports("s3a://b/l")).isTrue();
        assertThat(NativeEngine.supports("C:\\lake")).isTrue();
        assertThat(NativeEngine.supports("abfs://c@a/l")).isFalse();
    }

    @Test
    void s3LocationsParse() {
        S3Storage.Location l = S3Storage.Location.parse("s3a://lakes/banking/desk/trade/");
        assertThat(l.bucket()).isEqualTo("lakes");
        assertThat(l.key()).isEqualTo("banking/desk/trade");
        assertThat(l.uri("banking/x")).isEqualTo("s3a://lakes/banking/x");
        assertThat(S3Storage.Location.parse("s3://lakes").key()).isEmpty();
        assertThatThrownBy(() -> S3Storage.Location.parse("s3:///x")).isInstanceOf(IllegalArgumentException.class);
    }
}
