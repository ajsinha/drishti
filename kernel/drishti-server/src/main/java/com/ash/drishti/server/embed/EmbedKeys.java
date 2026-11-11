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
package com.ash.drishti.server.embed;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The server's own ES256 key for embed tokens (not the shared HS256 secret of the console), so the console can verify a token
 * without being able to make one. Loaded from a PEM file ({@code drishti.embed.signing-key}) with a PKCS#8 {@code PRIVATE KEY} and a
 * {@code PUBLIC KEY} block, or generated at start. Its {@code kid} follows the public key, so it is the same after a restart.
 */
public final class EmbedKeys {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ECPrivateKey priv;
    private final ECPublicKey pub;
    private final String kid;
    private final boolean generated;

    private EmbedKeys(KeyPair pair, boolean generated) {
        this.priv = (ECPrivateKey) pair.getPrivate();
        this.pub = (ECPublicKey) pair.getPublic();
        this.generated = generated;
        this.kid = "emb-" + hex(sha256(pub.getEncoded())).substring(0, 12);
    }

    /** The key in {@code pemFile}, or a new one when it is empty. */
    public static EmbedKeys load(String pemFile) {
        try {
            if (pemFile == null || pemFile.isBlank()) {
                KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
                g.initialize(new ECGenParameterSpec("secp256r1"));
                return new EmbedKeys(g.generateKeyPair(), true);
            }
            String pem = Files.readString(Path.of(pemFile), StandardCharsets.US_ASCII);
            byte[] privateDer = block(pem, "PRIVATE KEY");
            byte[] publicDer = block(pem, "PUBLIC KEY");
            KeyFactory kf = KeyFactory.getInstance("EC");
            return new EmbedKeys(new KeyPair(kf.generatePublic(new X509EncodedKeySpec(publicDer)), kf.generatePrivate(new PKCS8EncodedKeySpec(privateDer))), false);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("drishti.embed.signing-key " + pemFile + " is not a PEM file with a PKCS#8 EC PRIVATE KEY and a PUBLIC KEY block"
                    + " (openssl ecparam -name prime256v1 -genkey | openssl pkcs8 -topk8 -nocrypt, then openssl ec -pubout): " + e.getMessage(), e);
        }
    }

    private static byte[] block(String pem, String label) {
        String begin = "-----BEGIN " + label + "-----";
        int a = pem.indexOf(begin);
        int b = pem.indexOf("-----END " + label + "-----");
        if (a < 0 || b < a) {
            throw new IllegalArgumentException("no " + label + " block");
        }
        return Base64.getMimeDecoder().decode(pem.substring(a + begin.length(), b).trim());
    }

    public String kid() {
        return kid;
    }

    /** True when the key was made at start (the tokens do not survive a restart). */
    public boolean generated() {
        return generated;
    }

    /** A compact JWT with the given claims, signed ES256 with this key; the header carries {@code typ} and {@code kid}. */
    public String sign(String typ, Map<String, Object> claims) {
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "ES256");
            header.put("typ", typ);
            header.put("kid", kid);
            String input = b64(JSON.writeValueAsBytes(header)) + "." + b64(JSON.writeValueAsBytes(claims));
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initSign(priv);
            s.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + b64(s.sign());
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("could not sign an embed token", e);
        }
    }

    /** Whether the signature of a parsed token is this key's. */
    boolean verify(Jws.Parsed t) {
        if (!"ES256".equals(t.header().path("alg").asText())) {
            return false;
        }
        if (t.header().hasNonNull("kid") && !kid.equals(t.header().get("kid").asText())) {
            return false;
        }
        try {
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initVerify(pub);
            s.update(t.signingInput().getBytes(StandardCharsets.US_ASCII));
            return s.verify(t.signature());
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** The public key as a JWK set: what the console fetches to verify tokens. */
    public Map<String, Object> jwks() {
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("kty", "EC");
        k.put("crv", "P-256");
        k.put("alg", "ES256");
        k.put("use", "sig");
        k.put("kid", kid);
        k.put("x", b64(fixed(pub.getW().getAffineX())));
        k.put("y", b64(fixed(pub.getW().getAffineY())));
        return Map.of("keys", List.of(k));
    }

    private static byte[] fixed(BigInteger n) {
        byte[] raw = n.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        return java.util.HexFormat.of().formatHex(b);
    }
}
