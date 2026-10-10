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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Reads PEM: certificate bundles and private keys. A key may be PKCS#8 ({@code BEGIN PRIVATE KEY}), encrypted PKCS#8
 * ({@code BEGIN ENCRYPTED PRIVATE KEY}, with a password), PKCS#1 RSA ({@code BEGIN RSA PRIVATE KEY}) or SEC1 EC
 * ({@code BEGIN EC PRIVATE KEY}). The old OpenSSL-encrypted PEM ({@code Proc-Type: 4,ENCRYPTED}) is refused with the
 * command that converts it.
 */
public final class PemReader {

    private static final Pattern BLOCK = Pattern.compile("-----BEGIN ([A-Z0-9 ]+)-----(.*?)-----END \\1-----", Pattern.DOTALL);

    private PemReader() {}

    /** One PEM block: its label, decoded bytes and any header lines ({@code Proc-Type: ...}). */
    public record Block(String label, byte[] der, String header) {}

    /** A path's text, or the value itself when it is PEM text. {@code setting} names it in errors. */
    public static String text(String pathOrPem, String setting) {
        if (pathOrPem.contains("-----BEGIN ")) {
            return pathOrPem;
        }
        try {
            return Files.readString(Path.of(pathOrPem), StandardCharsets.ISO_8859_1);
        } catch (NoSuchFileException e) {
            throw new TlsException(setting + " '" + pathOrPem + "': file not found");
        } catch (IOException e) {
            throw new TlsException(setting + " '" + pathOrPem + "': cannot read the file (" + e.getMessage() + ")", e);
        }
    }

    public static List<Block> blocks(String text) {
        List<Block> out = new ArrayList<>();
        Matcher m = BLOCK.matcher(text);
        while (m.find()) {
            StringBuilder header = new StringBuilder();
            StringBuilder b64 = new StringBuilder();
            for (String line : m.group(2).split("\\R")) {
                String l = line.strip();
                if (l.isEmpty()) {
                    continue;
                }
                if (l.contains(":")) {
                    header.append(l).append('\n');
                } else {
                    b64.append(l);
                }
            }
            try {
                out.add(new Block(m.group(1), Base64.getDecoder().decode(b64.toString()), header.toString()));
            } catch (IllegalArgumentException e) {
                throw new TlsException("a PEM block '" + m.group(1) + "' is not valid base64 (" + e.getMessage() + ")");
            }
        }
        return out;
    }

    private static String where(String pathOrPem) {
        return pathOrPem.contains("-----BEGIN ") ? "inline PEM" : "'" + pathOrPem + "'";
    }

    /** Every certificate in a PEM bundle (a path or PEM text), in order. */
    public static List<X509Certificate> certificates(String pathOrPem, String setting) {
        String text = text(pathOrPem, setting);
        List<X509Certificate> out = new ArrayList<>();
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (Block b : blocks(text)) {
                if (b.label().equals("CERTIFICATE") || b.label().equals("TRUSTED CERTIFICATE") || b.label().equals("X509 CERTIFICATE")) {
                    out.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(b.der())));
                }
            }
        } catch (GeneralSecurityException e) {
            throw new TlsException(setting + " " + where(pathOrPem) + ": not a valid certificate (" + e.getMessage() + ")", e);
        }
        if (out.isEmpty()) {
            throw new TlsException(setting + " " + where(pathOrPem) + ": no certificate found (expected -----BEGIN CERTIFICATE-----)");
        }
        return out;
    }

    /** The private key in a PEM file (a path or PEM text). */
    public static PrivateKey privateKey(String pathOrPem, String password, String setting) {
        String text = text(pathOrPem, setting);
        String where = where(pathOrPem);
        for (Block b : blocks(text)) {
            switch (b.label()) {
                case "PRIVATE KEY":
                    return decode(b.der(), where, setting);
                case "RSA PRIVATE KEY":
                    if (b.header().contains("ENCRYPTED")) {
                        throw legacyEncrypted(setting, where);
                    }
                    return decode(Der.rsaToPkcs8(b.der()), where, setting);
                case "EC PRIVATE KEY":
                    if (b.header().contains("ENCRYPTED")) {
                        throw legacyEncrypted(setting, where);
                    }
                    return decode(Der.sec1ToPkcs8(b.der(), setting + " " + where), where, setting);
                case "ENCRYPTED PRIVATE KEY":
                    return decrypt(b.der(), password, where, setting);
                default:
                    break;
            }
        }
        throw new TlsException(setting + " " + where + ": no private key found (expected PRIVATE KEY, ENCRYPTED PRIVATE KEY, "
                + "RSA PRIVATE KEY or EC PRIVATE KEY)");
    }

    private static TlsException legacyEncrypted(String setting, String where) {
        return new TlsException(setting + " " + where + ": an OpenSSL-encrypted traditional key (Proc-Type: 4,ENCRYPTED) is not "
                + "supported; convert it with: openssl pkcs8 -topk8 -in key.pem -out key-pkcs8.pem");
    }

    private static PrivateKey decrypt(byte[] der, String password, String where, String setting) {
        if (password == null) {
            throw new TlsException(setting + " " + where + ": the key is encrypted; set the key password (key-password or key-password-file)");
        }
        try {
            EncryptedPrivateKeyInfo info = new EncryptedPrivateKeyInfo(der);
            Cipher cipher = Cipher.getInstance(info.getAlgName());
            cipher.init(Cipher.DECRYPT_MODE, SecretKeyFactory.getInstance(info.getAlgName())
                    .generateSecret(new PBEKeySpec(password.toCharArray())), info.getAlgParameters());
            return decode(info.getKeySpec(cipher).getEncoded(), where, setting);
        } catch (IOException | GeneralSecurityException e) {
            throw new TlsException(setting + " " + where + ": cannot decrypt the key: wrong key password, or an unsupported "
                    + "encryption (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private static PrivateKey decode(byte[] pkcs8, String where, String setting) {
        for (String alg : new String[] {"RSA", "EC", "Ed25519", "RSASSA-PSS", "DSA"}) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            } catch (GeneralSecurityException ignored) {
                // next algorithm
            }
        }
        throw new TlsException(setting + " " + where + ": the private key is not RSA, EC, Ed25519 or DSA, or is damaged");
    }

    /** PEM text for one block. */
    public static String pem(String label, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + b64 + "\n-----END " + label + "-----\n";
    }
}
