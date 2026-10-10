<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->
# TLS for connectors: one set of `tls.*` settings

Every connector that talks to a server over the network reads its TLS configuration from the same keys, under the
prefix `tls.`, and builds its secure connection with the same code (`com.ash.drishti.api.tls` in the plugin API). You
learn the settings once: a private CA, a PKCS12 or JKS store, a PEM file pair for mutual TLS, hostname verification and
the rest work the same for Kafka, ActiveMQ and RabbitMQ, and for each connector that has taken the module on since.

This is the reference. The connector documents show complete configurations for their own protocols:
[Kafka](KAFKA_CONNECTOR.md#11-security-tls-sasl-and-confluent) (TLS, SASL, Confluent, Schema Registry),
[ActiveMQ](ACTIVEMQ_CONNECTOR.md#14-security) (`ssl://`), [RabbitMQ](RABBITMQ_CONNECTOR.md#12-security) (`amqps://`,
client-certificate login). A plugin author who wants the module in a new connector reads
[the developer guide](CONNECTOR_DEVELOPER_GUIDE.md#tls-for-a-connector-the-shared-module).

## Contents

1. [The settings](#1-the-settings)
2. [What can be trusted](#2-what-can-be-trusted)
3. [A client identity: mutual TLS](#3-a-client-identity-mutual-tls)
4. [Passwords never go in a document](#4-passwords-never-go-in-a-document)
5. [Protocols, cipher suites, hostname verification](#5-protocols-cipher-suites-hostname-verification)
6. [Insecure trust-all, for development only](#6-insecure-trust-all-for-development-only)
7. [Making and converting certificates and keys](#7-making-and-converting-certificates-and-keys)
8. [Start-up checks and their messages](#8-start-up-checks-and-their-messages)
9. [Certificate expiry](#9-certificate-expiry)
10. [Rotation](#10-rotation)
11. [Troubleshooting](#11-troubleshooting)

---

## 1. The settings

All keys are connector settings (`settings:` of a pack connector or a site source), written with the prefix `tls.`.
Every one is optional.

| Key | Default | Meaning |
|---|---|---|
| `tls.enabled` | `false` | Use TLS. (A connector whose address already says so, such as `amqps://` or `ssl://`, or Kafka's `security.protocol: SSL`, uses TLS without it. Setting it to `true` against a plain address is a start-up error, not a silent downgrade.) |
| `tls.ca-file` | | Trust: a PEM file holding one or many CA certificates, or the PEM text itself (a value that contains `-----BEGIN` is taken as text, which suits a secret passed in an environment variable). |
| `tls.truststore` | | Trust: a PKCS12 or JKS truststore, by path. |
| `tls.truststore-password` / `tls.truststore-password-file` | none | Its password. |
| `tls.truststore-type` | detected | `PKCS12` or `JKS`; detected from the file when absent. |
| `tls.trust-jvm-default` | `true` when neither `ca-file` nor `truststore` is given, else `false` | Also trust the authorities the JVM trusts (the public CAs). `true` beside a `ca-file` or `truststore` merges them. |
| `tls.cert-file` | | Client identity: a PEM file with the certificate chain, leaf first. |
| `tls.key-file` | | Client identity: the PEM private key. PKCS#8, PKCS#1 RSA, SEC1 EC, or encrypted PKCS#8. |
| `tls.key-password` / `tls.key-password-file` | none | The password of an encrypted PEM key, or of the key inside a keystore (it defaults to the keystore password). |
| `tls.keystore` | | Client identity: a PKCS12 or JKS keystore, by path. Use this **or** `cert-file` with `key-file`, not both. |
| `tls.keystore-password` / `tls.keystore-password-file` | none | Its password. |
| `tls.keystore-type` | detected | `PKCS12` or `JKS`. |
| `tls.key-alias` | the only key | Which key of the keystore to present. |
| `tls.protocols` | `TLSv1.3,TLSv1.2` | Protocol versions offered, comma separated. A version the JVM does not support is refused at start. |
| `tls.cipher-suites` | the JVM's | Cipher suites, comma separated. |
| `tls.verify-hostname` | `true` | Check the server's name against its certificate. `false` logs a warning at every start. |
| `tls.insecure-trust-all` | `false` | Do not check the server certificate at all. Refused unless `DRISHTI_ALLOW_INSECURE_TLS=true` is in the environment. |

A connector with a second TLS endpoint of its own reads it under another prefix with the same keys (the Kafka
connector's Schema Registry uses `schema-registry.tls.*`).

---

## 2. What can be trusted

The connector trusts the **union** of whatever you give it.

| You give | The connector trusts |
|---|---|
| nothing | the JVM's authorities (the public CAs, plus anything in `javax.net.ssl.trustStore` / `-Djavax.net.ssl.trustStore`) |
| `tls.ca-file` | the certificates in that file only |
| `tls.truststore` | the certificates in that store only |
| `tls.ca-file` and `tls.truststore` | both, merged |
| any of the above and `tls.trust-jvm-default: true` | the above and the JVM's authorities |

Once you name your own authority, the public CAs are **not** trusted unless you add `trust-jvm-default: true`. That is
deliberate: a broker on a private CA should not be impersonated by any public one.

A CA bundle may hold one certificate or many, and intermediate authorities as well as roots:

```yaml
settings:
  tls.ca-file: /etc/drishti/tls/ca-bundle.pem      # several -----BEGIN CERTIFICATE----- blocks
```

The server certificate must chain to a certificate in the trusted set. Trusting the issuing (intermediate) CA is enough;
you do not need the root.

---

## 3. A client identity: mutual TLS

When the server asks the client for a certificate, give the connector an identity in one of two forms.

**PEM files** (the usual form with a private CA, cert-manager, Vault, Let's Encrypt):

```yaml
settings:
  tls.cert-file: /etc/drishti/tls/client.pem       # certificate, then any intermediates
  tls.key-file: /etc/drishti/tls/client.key        # the private key
  tls.key-password: "${CLIENT_KEY_PASSWORD}"       # only if the key is encrypted
```

The key may be written as any of these (the first line of the file says which):

| First line | Format | Made by |
|---|---|---|
| `-----BEGIN PRIVATE KEY-----` | PKCS#8, any algorithm | `openssl genpkey`, `openssl pkcs8 -topk8 -nocrypt`, cert-manager |
| `-----BEGIN ENCRYPTED PRIVATE KEY-----` | encrypted PKCS#8 (PBES2: PBKDF2 with AES-CBC, or the older PKCS#5/12 schemes the JVM knows) | `openssl pkcs8 -topk8 -passout …` |
| `-----BEGIN RSA PRIVATE KEY-----` | PKCS#1 RSA | `openssl rsa -traditional`, older tools |
| `-----BEGIN EC PRIVATE KEY-----` | SEC1 EC, with its curve | `openssl ecparam -genkey` |

An old OpenSSL-encrypted traditional key (it has a `Proc-Type: 4,ENCRYPTED` header) is not supported; the error shows the
command that converts it ([section 7](#7-making-and-converting-certificates-and-keys)).

**A keystore** (the usual form on a Java estate):

```yaml
settings:
  tls.keystore: /etc/drishti/tls/client.p12
  tls.keystore-password: "${CLIENT_KEYSTORE_PASSWORD}"
  tls.key-alias: drishti                           # only if the store holds more than one key
```

The connector checks that the key belongs to the certificate (it signs a challenge with the key and verifies it with the
certificate) before it connects, so a mismatched pair fails at start with both file names, not later with an opaque
handshake alert.

---

## 4. Passwords never go in a document

A password is read in one of two ways, and a connector document or a site YAML never contains the secret itself:

- **An environment placeholder:** `tls.keystore-password: "${CLIENT_KEYSTORE_PASSWORD}"`. A reference to a variable that is
  not set is a start-up error naming the variable (`${NAME:default}` gives a default).
- **A password file:** `tls.keystore-password-file: /run/secrets/keystore-password` reads the first line of the file, which
  is how Kubernetes and Docker secrets arrive. A missing file is an error naming the file.

Every `…-password` key has a `…-password-file` twin. The settings are not shown by the server's source list or by
Health.

---

## 5. Protocols, cipher suites, hostname verification

**Protocols.** `TLSv1.3,TLSv1.2` by default. Narrow it with `tls.protocols: TLSv1.3`. TLS 1.0 and 1.1 are disabled in
current JVMs and the connector does not re-enable them.

**Cipher suites.** The JVM's strong defaults are used unless you list suites in `tls.cipher-suites`. A suite the JVM does not
support is a start-up error, not silently ignored.

**Hostname verification** is on. The connector checks that the name you connect to (`kafka1.bank.example`) is in the
certificate's subject alternative names (SAN). A certificate with only a Common Name and no SAN is not accepted by a
current JVM. The usual fixes, in order of preference: re-issue the broker certificate with the right names; connect by a
name the certificate carries; and last, `tls.verify-hostname: false`.

`tls.verify-hostname: false` keeps the chain check but not the name check, so anyone holding a certificate from a trusted
authority can impersonate the server. Drishti logs a warning at **every start** while it is off:

```
WARNING: TLS: tls.verify-hostname is false: the server's name is NOT checked against its certificate.
Anyone holding a certificate from a trusted authority can impersonate the server.
```

---

## 6. Insecure trust-all, for development only

`tls.insecure-trust-all: true` accepts any certificate. It exists for a laptop and a self-signed throwaway broker, and it
is refused unless the process environment says so:

```
tls.insecure-trust-all is refused: it switches certificate checking off, so anyone on the network can impersonate the
server. For development only, start the server with DRISHTI_ALLOW_INSECURE_TLS=true
```

With `DRISHTI_ALLOW_INSECURE_TLS=true` set it works and logs `TLS: tls.insecure-trust-all is ON: the server certificate
is NOT checked. Development only.` at every start. Never set the variable in production. (The Kafka connector does not
offer it, because the Kafka client builds its own TLS: trust the broker's CA with `tls.ca-file` instead.)

---

## 7. Making and converting certificates and keys

These are the commands used to produce the files the connectors read; the output below was captured from them.

**A private CA and a client certificate**

```
$ openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.pem -subj "/CN=Example Internal CA" -days 3650
$ openssl req -newkey rsa:2048 -nodes -keyout client-pkcs8.key -out client.csr -subj "/CN=drishti"
$ openssl x509 -req -in client.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out client.pem -days 365
  Certificate request self-signature ok
  subject=CN=drishti
```

A **server** certificate needs its names as SAN, or hostname verification fails:

```
$ openssl req -newkey rsa:2048 -nodes -keyout broker.key -out broker.csr -subj "/CN=kafka1.bank.example" \
    -addext "subjectAltName=DNS:kafka1.bank.example,DNS:kafka2.bank.example,IP:10.0.0.11"
$ openssl x509 -req -in broker.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out broker.pem -days 365 \
    -copy_extensions copy
```

**Between the key formats**

```
$ head -1 client-pkcs8.key
-----BEGIN PRIVATE KEY-----
$ openssl rsa -in client-pkcs8.key -traditional -out client-pkcs1.key        # PKCS#8 -> PKCS#1
writing RSA key          # first line: -----BEGIN RSA PRIVATE KEY-----
$ openssl pkcs8 -topk8 -in client-pkcs1.key -nocrypt -out client-pkcs8.key   # PKCS#1 -> PKCS#8
$ openssl pkcs8 -topk8 -in client-pkcs8.key -out client-enc.key -passout pass:s3cret   # encrypt
                         # first line: -----BEGIN ENCRYPTED PRIVATE KEY-----
$ openssl pkcs8 -topk8 -in legacy-encrypted.key -out client-pkcs8.key        # a Proc-Type: 4,ENCRYPTED key, converted
```

**PEM to PKCS12 and JKS**

```
$ openssl pkcs12 -export -in client.pem -inkey client-pkcs8.key -certfile ca.pem -name drishti -out client.p12 -passout pass:changeit
$ keytool -importkeystore -srckeystore client.p12 -srcstoretype PKCS12 -srcstorepass changeit \
    -destkeystore client.jks -deststoretype JKS -deststorepass changeit
$ keytool -list -keystore client.p12 -storepass changeit
Keystore type: PKCS12
Keystore provider: SUN

Your keystore contains 1 entry

drishti, Oct 10, 2026, PrivateKeyEntry,
Certificate fingerprint (SHA-256): 74:80:89:22:77:07:13:4B:6F:9A:72:7F:69:72:EC:08:73:74:72:C7:E9:45:7F:1A:91:C9:4E:B1:BA:C3:41:8F
```

A truststore from the CA certificate:

```
$ keytool -importcert -alias ca -file ca.pem -keystore truststore.p12 -storetype PKCS12 -storepass changeit -noprompt
Certificate was added to keystore
```

**PKCS12 back to PEM**

```
$ openssl pkcs12 -in client.p12 -passin pass:changeit -nokeys -out client-from-p12.pem    # the certificates
$ openssl pkcs12 -in client.p12 -passin pass:changeit -nocerts -nodes -out key-from-p12.pem   # the key (unencrypted)
```

(`-nodes` writes the key unencrypted; protect the file, `chmod 600`.) A JKS store becomes PKCS12 with `keytool
-importkeystore … -deststoretype PKCS12`, then the line above.

**Checking a pair and an expiry**

```
$ openssl x509 -in client.pem -noout -subject -enddate
subject=CN=drishti
notAfter=Oct 10 13:41:44 2027 GMT
$ openssl x509 -in client.pem -noout -pubkey | openssl md5        # the two digests must be equal
MD5(stdin)= dda642610a340a3c2c142705ec9765a7
$ openssl pkey -in client-pkcs8.key -pubout | openssl md5
MD5(stdin)= dda642610a340a3c2c142705ec9765a7
```

---

## 8. Start-up checks and their messages

The connector builds its TLS context when it starts, so a wrong file or password is a start-up error that names the
setting, the file and the reason, rather than an opaque failure at the first connection. Captured from a real run:

| Mistake | Message |
|---|---|
| a file that does not exist | `tls.ca-file '/etc/drishti/tls/nope.pem': file not found` |
| a PEM file with no certificate | `tls.ca-file '…': no certificate found (expected -----BEGIN CERTIFICATE-----)` |
| wrong keystore or truststore password | `tls.keystore '/etc/drishti/tls/client.p12': wrong password (tls.keystore-password), or the file is damaged` |
| a file that is not a key store | `tls.keystore '…': not a readable key store (tried PKCS12 and JKS): …` |
| an encrypted key without its password | `tls.key-file '…': the key is encrypted; set the key password (key-password or key-password-file)` |
| a wrong key password | `tls.key-file '…': cannot decrypt the key: wrong key password, or an unsupported encryption (…)` |
| a wrong key password inside a keystore | `tls.keystore '…': cannot recover the key 'drishti': wrong key password (tls.key-password; it defaults to the keystore password)` |
| a key for another certificate | `tls.key-file '…/ca.key' does not match the certificate in tls.cert-file '…/client.pem' (its first certificate, CN=drishti): the key is for another certificate. Compare: openssl x509 -noout -pubkey -in cert.pem \| openssl md5; openssl pkey -pubout -in key.pem \| openssl md5` |
| only one of the pair | `tls.cert-file is set but tls.key-file is not: a client identity needs both` |
| two identities | `keystore and tls.cert-file/tls.key-file both set: give the client identity one way` |
| an alias that is not there | `tls.key-alias 'zzz' is not in the keystore (aliases: [drishti])` |
| a truststore with no certificates | `tls.truststore '…': holds no certificates, so nothing would be trusted` |
| an unsupported protocol or cipher | `tls.protocols: 'SSLv9' is not supported by this JVM (supported: […])` |
| an unset environment variable | `tls.keystore-password: the environment variable CLIENT_KEYSTORE_PASSWORD is not set` |
| a missing password file | `tls.keystore-password-file '…': cannot read the password file (file not found)` |

These are not `DRS-` coded: they are connector start-up messages, shown in the log, in Admin → Packs → Data source →
Test connection and in the source's health text, like any other connector's configuration error.

---

## 9. Certificate expiry

At start the connector reads the expiry of every certificate it was given (the client chain and the CA bundle or
truststore; not the JVM's own authorities) and warns:

```
WARNING: TLS: certificate CN=drishti (tls.cert-file) EXPIRED on 2026-09-30; connections will fail
WARNING: TLS: certificate CN=drishti (tls.cert-file) expires in 12 days (2026-10-22)
WARNING: TLS: certificate CN=drishti (tls.cert-file) is not valid before 2026-12-01
```

The warning also reaches the operator without anyone reading the log: while the connector is up, the **health text** of the
source (Admin → Health and Admin → Packs → Data source) carries the soonest expiry once it is less than 30 days away:

```
UP (TLS certificate CN=drishti (tls.cert-file) expires in 12 days (2026-10-22))
```

It still starts with `UP`, so the source counts as up; the sentence is the nudge to rotate. An expired certificate is
warned about but not refused at start: the handshake will refuse it, with the server's reason.

---

## 10. Rotation

The files are read when the connector **starts**. To rotate a certificate, key, CA bundle or store:

1. Write the new files in place (or to new paths and change the setting). Keep the old CA in the bundle until every server
   has switched, so both the old and the new certificate are trusted during the changeover.
2. Restart the source: re-deploy the pack, or restart the server. The connector reconnects with the new material. The
   Kafka and message-queue connectors resume where they stopped (Kafka from its saved offsets; the queues from their own
   state store).
3. Check Health: `UP`, and no expiry sentence.

Do not delete the old files before the restart: a connector that reconnects after a network blip in the meantime reuses
what it loaded at start, but a start with a missing file is the error in [section 8](#8-start-up-checks-and-their-messages).

---

## 11. Troubleshooting

**Look at what the server sends** from the machine Drishti runs on:

```
$ openssl s_client -connect kafka1.bank.example:9093 -servername kafka1.bank.example -showcerts </dev/null
$ openssl s_client -connect kafka1.bank.example:9093 -CAfile ca.pem -verify_return_error -verify_hostname kafka1.bank.example </dev/null | grep -E "Verif|subject|issuer"
$ openssl s_client -connect kafka1.bank.example:9093 -CAfile ca.pem -cert client.pem -key client-pkcs8.key </dev/null   # mutual TLS
```

`Verification: OK` with `-verify_hostname` means the chain and the name both pass. `Verify return code: 20 (unable to get
local issuer certificate)` means the CA you gave is not the issuer (or the chain is missing an intermediate).

| What you see | What it means | Fix |
|---|---|---|
| `PKIX path building failed: … unable to find valid certification path to requested target` | the server's certificate does not chain to anything trusted | add the issuing CA to `tls.ca-file` or the truststore; remember that naming your own CA drops the public ones (`trust-jvm-default: true` keeps them) |
| `No subject alternative DNS name matching kafka1.bank.example found` (or `No subject alternative names present`) | the certificate does not carry the name you connect to | re-issue it with that name as a SAN, or connect by a name it carries; last resort `tls.verify-hostname: false` |
| `certificate_unknown` / `bad_certificate` from the server | the server does not trust the client certificate (mutual TLS) | the client certificate's CA must be in the server's truststore; check `tls.cert-file` includes the intermediates |
| `Received fatal alert: handshake_failure` | no protocol or cipher in common | check `tls.protocols` and `tls.cipher-suites` against the server's; a JVM older than the server's TLS 1.3-only setting |
| `Received fatal alert: certificate_required` / `peer not authenticated` | the server demands a client certificate and none was sent | set `tls.cert-file` and `tls.key-file`, or `tls.keystore` |
| `certificate has expired` / `NotAfter` | a certificate in the chain is past its date | rotate ([section 10](#10-rotation)); the start-up warning named it |
| `Connection reset` right after connecting | the server expects TLS and the client sent plain text, or the reverse | check the address scheme and port (Kafka SSL listener, ActiveMQ `ssl://`, RabbitMQ 5671) |
| the start-up messages of [section 8](#8-start-up-checks-and-their-messages) | a file or a password | fix the named setting |

The JVM can log the whole handshake: start the server with `-Djavax.net.debug=ssl:handshake` for one run (it is verbose; do
not leave it on).

---

For plugin authors: the module is `com.ash.drishti.api.tls` (`TlsSettings`, `TlsContexts`, `TlsMaterial`, `Secrets`) in
`drishti-api`; no Spring, no dependencies beyond the JDK. See
[CONNECTOR_DEVELOPER_GUIDE.md](CONNECTOR_DEVELOPER_GUIDE.md#tls-for-a-connector-the-shared-module).
