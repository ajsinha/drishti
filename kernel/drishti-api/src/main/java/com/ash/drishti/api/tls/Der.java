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
package com.ash.drishti.api.tls;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HexFormat;

/** The little DER needed to wrap a PKCS#1 or SEC1 key into PKCS#8. */
final class Der {

    private static final byte[] RSA_OID = HexFormat.of().parseHex("2a864886f70d010101");
    private static final byte[] EC_OID = HexFormat.of().parseHex("2a8648ce3d0201");

    private Der() {}

    static byte[] tlv(int tag, byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (len < 128) {
            out.write(len);
        } else if (len < 256) {
            out.write(0x81);
            out.write(len);
        } else if (len < 65536) {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xff);
        } else {
            out.write(0x83);
            out.write(len >> 16);
            out.write((len >> 8) & 0xff);
            out.write(len & 0xff);
        }
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    /** PrivateKeyInfo for an RSAPrivateKey (PKCS#1). */
    static byte[] rsaToPkcs8(byte[] pkcs1) {
        byte[] alg = tlv(0x30, tlv(0x06, RSA_OID), new byte[] {0x05, 0x00});
        return tlv(0x30, new byte[] {0x02, 0x01, 0x00}, alg, tlv(0x04, pkcs1));
    }

    /** PrivateKeyInfo for a SEC1 ECPrivateKey: the curve is taken from its parameters. */
    static byte[] sec1ToPkcs8(byte[] sec1, String what) {
        try {
            int p = header(sec1, 0)[0];                        // inside the SEQUENCE
            p = skip(sec1, p);                                 // version
            p = skip(sec1, p);                                 // private key octets
            if ((sec1[p] & 0xff) != 0xa0) {
                throw new TlsException(what + ": the EC key carries no curve parameters; convert it with: "
                        + "openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem");
            }
            int[] ctx = header(sec1, p);
            int[] oid = header(sec1, ctx[0]);                  // OBJECT IDENTIFIER of the curve
            byte[] curve = tlv(0x06, Arrays.copyOfRange(sec1, oid[0], oid[0] + oid[1]));
            byte[] alg = tlv(0x30, tlv(0x06, EC_OID), curve);
            return tlv(0x30, new byte[] {0x02, 0x01, 0x00}, alg, tlv(0x04, sec1));
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new TlsException(what + ": the EC private key is damaged");
        }
    }

    /** {content start, content length} of the element at {@code at}. */
    private static int[] header(byte[] b, int at) {
        int len = b[at + 1] & 0xff;
        int start = at + 2;
        if (len >= 0x80) {
            int n = len & 0x7f;
            len = 0;
            for (int i = 0; i < n; i++) {
                len = (len << 8) | (b[start + i] & 0xff);
            }
            start += n;
        }
        return new int[] {start, len};
    }

    private static int skip(byte[] b, int at) {
        int[] h = header(b, at);
        return h[0] + h[1];
    }
}
