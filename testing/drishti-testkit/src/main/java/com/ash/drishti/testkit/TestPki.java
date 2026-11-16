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
package com.ash.drishti.testkit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;

/**
 * Certificates for tests, made at test time with the JDK alone: a certificate authority and certificates it signs (RSA or
 * EC, with a chosen validity, with {@code localhost} / {@code 127.0.0.1} names), written as PEM (PKCS#8, PKCS#1, SEC1 or
 * encrypted PKCS#8 keys), PKCS12 or JKS. No keytool or openssl, so the tests run anywhere.
 */
public final class TestPki {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TestPki() {}

    /** The private key formats a PEM file can hold. */
    public enum KeyFormat { PKCS8, PKCS1_RSA, SEC1_EC, ENCRYPTED_PKCS8 }

    /** A certificate and its key (a CA or a leaf). */
    public record Identity(X509Certificate cert, PrivateKey key, List<X509Certificate> chain) {

        public void writeCertPem(Path file) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (X509Certificate c : chain) {
                sb.append(pem("CERTIFICATE", derOf(c)));
            }
            Files.writeString(file, sb.toString(), StandardCharsets.US_ASCII);
        }

        /** The key; {@code password} only for {@link KeyFormat#ENCRYPTED_PKCS8}. */
        public void writeKeyPem(Path file, KeyFormat format, String password) throws IOException {
            Files.writeString(file, keyPem(key, format, password), StandardCharsets.US_ASCII);
        }

        public void writeKeystore(Path file, String type, String password, String alias, String keyPassword) throws IOException {
            try {
                KeyStore ks = KeyStore.getInstance(type);
                ks.load(null, null);
                ks.setKeyEntry(alias, key, keyPassword.toCharArray(), chain.toArray(new Certificate[0]));
                try (var out = Files.newOutputStream(file)) {
                    ks.store(out, password.toCharArray());
                }
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
        }
    }

    /** A new certificate authority, valid for ten years. */
    public static Identity ca(String commonName) {
        try {
            KeyPair kp = keys("RSA");
            Instant now = Instant.now();
            X509Certificate c = sign(kp.getPublic().getEncoded(), commonName, kp.getPrivate(), commonName, now.minusSeconds(3600),
                    now.plusSeconds(10L * 365 * 86400), true);
            return new Identity(c, kp.getPrivate(), List.of(c));
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A certificate for {@code localhost} and {@code 127.0.0.1} signed by {@code ca}, valid from now for {@code validDays}. */
    public static Identity issue(Identity ca, String commonName, String keyAlgorithm, long validDays) {
        Instant now = Instant.now();
        return issue(ca, commonName, keyAlgorithm, now.minusSeconds(3600), now.plusSeconds(validDays * 86400));
    }

    public static Identity issue(Identity ca, String commonName, String keyAlgorithm, Instant notBefore, Instant notAfter) {
        try {
            KeyPair kp = keys(keyAlgorithm);
            X509Certificate c = sign(kp.getPublic().getEncoded(), commonName, ca.key(), ca.cert().getSubjectX500Principal().getName().replaceFirst("^CN=", ""),
                    notBefore, notAfter, false);
            return new Identity(c, kp.getPrivate(), List.of(c, ca.cert()));
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A truststore holding {@code certs} as trusted entries. */
    public static void writeTruststore(Path file, String type, String password, X509Certificate... certs) throws IOException {
        try {
            KeyStore ks = KeyStore.getInstance(type);
            ks.load(null, null);
            int i = 0;
            for (X509Certificate c : certs) {
                ks.setCertificateEntry("ca-" + i++, c);
            }
            try (var out = Files.newOutputStream(file)) {
                ks.store(out, password.toCharArray());
            }
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    // ---------------------------------------------------------------- keys and PEM

    private static KeyPair keys(String algorithm) throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance(algorithm);
        if ("EC".equals(algorithm)) {
            g.initialize(new ECGenParameterSpec("secp256r1"));
        } else {
            g.initialize(2048);
        }
        return g.generateKeyPair();
    }

    public static String keyPem(PrivateKey key, KeyFormat format, String password) {
        try {
            switch (format) {
                case PKCS8:
                    return pem("PRIVATE KEY", key.getEncoded());
                case PKCS1_RSA: {
                    RSAPrivateCrtKey k = (RSAPrivateCrtKey) key;
                    return pem("RSA PRIVATE KEY", tlv(0x30, integer(BigInteger.ZERO), integer(k.getModulus()), integer(k.getPublicExponent()),
                            integer(k.getPrivateExponent()), integer(k.getPrimeP()), integer(k.getPrimeQ()), integer(k.getPrimeExponentP()),
                            integer(k.getPrimeExponentQ()), integer(k.getCrtCoefficient())));
                }
                case SEC1_EC: {
                    ECPrivateKey k = (ECPrivateKey) key;
                    byte[] s = fixed(k.getS(), 32);
                    byte[] oid = tlv(0x06, hex("2a8648ce3d030107"));          // prime256v1
                    return pem("EC PRIVATE KEY", tlv(0x30, new byte[] {2, 1, 1}, tlv(0x04, s), tlv(0xa0, oid)));
                }
                case ENCRYPTED_PKCS8: {
                    byte[] salt = new byte[16];
                    byte[] iv = new byte[16];
                    RANDOM.nextBytes(salt);
                    RANDOM.nextBytes(iv);
                    // PBES2: PBKDF2-HMAC-SHA256 and AES-256-CBC, as `openssl pkcs8 -topk8` writes it
                    SecretKey sk = new javax.crypto.spec.SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                            .generateSecret(new PBEKeySpec(password.toCharArray(), salt, 10_000, 256)).getEncoded(), "AES");
                    Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    c.init(Cipher.ENCRYPT_MODE, sk, new IvParameterSpec(iv));
                    byte[] enc = c.doFinal(key.getEncoded());
                    byte[] kdf = tlv(0x30, tlv(0x06, hex("2a864886f70d01050c")),
                            tlv(0x30, tlv(0x04, salt), integer(BigInteger.valueOf(10_000)),
                                    tlv(0x30, tlv(0x06, hex("2a864886f70d0209")), new byte[] {5, 0})));
                    byte[] cipher = tlv(0x30, tlv(0x06, hex("60864801650304012a")), tlv(0x04, iv));
                    byte[] alg = tlv(0x30, tlv(0x06, hex("2a864886f70d01050d")), tlv(0x30, kdf, cipher));
                    return pem("ENCRYPTED PRIVATE KEY", tlv(0x30, alg, tlv(0x04, enc)));
                }
                default:
                    throw new IllegalArgumentException();
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] fixed(BigInteger v, int len) {
        byte[] b = v.toByteArray();
        byte[] out = new byte[len];
        int n = Math.min(b.length, len);
        System.arraycopy(b, b.length - n, out, len - n, n);
        return out;
    }

    public static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }

    private static byte[] derOf(X509Certificate c) {
        try {
            return c.getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- X.509

    private static X509Certificate sign(byte[] spki, String subjectCn, PrivateKey signer, String issuerCn, Instant from, Instant to,
                                        boolean ca) throws GeneralSecurityException, IOException {
        byte[] sigAlg = tlv(0x30, tlv(0x06, hex("2a864886f70d01010b")), new byte[] {5, 0});      // sha256WithRSAEncryption
        byte[] ext;
        if (ca) {
            ext = tlv(0x30, extension("551d13", true, tlv(0x30, new byte[] {1, 1, (byte) 0xff})));
        } else {
            byte[] san = tlv(0x30, tlv(0x82, "localhost".getBytes(StandardCharsets.US_ASCII)), tlv(0x87, new byte[] {127, 0, 0, 1}));
            ext = tlv(0x30, extension("551d11", false, san));
        }
        byte[] tbs = tlv(0x30,
                tlv(0xa0, new byte[] {2, 1, 2}),
                integer(new BigInteger(64, RANDOM).add(BigInteger.ONE)),
                sigAlg,
                name(issuerCn),
                tlv(0x30, time(from), time(to)),
                name(subjectCn),
                spki,
                tlv(0xa3, ext));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(signer);
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] cert = tlv(0x30, tbs, sigAlg, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert));
    }

    private static byte[] extension(String oidHex, boolean critical, byte[] value) {
        return critical
                ? tlv(0x30, tlv(0x06, hex(oidHex)), new byte[] {1, 1, (byte) 0xff}, tlv(0x04, value))
                : tlv(0x30, tlv(0x06, hex(oidHex)), tlv(0x04, value));
    }

    private static byte[] name(String cn) {
        return tlv(0x30, tlv(0x31, tlv(0x30, tlv(0x06, hex("550403")), tlv(0x0c, cn.getBytes(StandardCharsets.UTF_8)))));
    }

    private static byte[] time(Instant t) {
        // UTCTime covers 1950-2049, which the tests stay inside
        return tlv(0x17, DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC).format(t).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] integer(BigInteger v) {
        return tlv(0x02, v.toByteArray());
    }

    private static byte[] hex(String h) {
        return java.util.HexFormat.of().parseHex(h);
    }

    private static byte[] tlv(int tag, byte[]... parts) {
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
        } else {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xff);
        }
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}
