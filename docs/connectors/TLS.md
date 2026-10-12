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
# TLS and proxy for connectors

Every connector that talks to a server over the network reads its TLS configuration from the same keys, under the
prefix `tls.`, and builds its secure connection with the same code (`com.ash.drishti.api.tls` in the plugin API). You
learn the settings once: a private CA, a PKCS12 or JKS store, a PEM file pair for mutual TLS, hostname verification and
the rest work the same for Kafka, ActiveMQ and RabbitMQ, and for each connector that has taken the module on since.

Behind a forward proxy, the same connectors also take the shared `proxy.*` settings ([TLS.md (proxy)](#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers)); an `https://` target is tunnelled with `CONNECT` and keeps every `tls.*` setting described here.

This is the reference. The connector documents show complete configurations for their own protocols:
[Kafka](KAFKA_CONNECTOR.md#11-security-tls-sasl-and-confluent) (TLS, SASL, Confluent, Schema Registry),
[ActiveMQ](ACTIVEMQ_CONNECTOR.md#14-security) (`ssl://`), [RabbitMQ](RABBITMQ_CONNECTOR.md#12-security) (`amqps://`,
client-certificate login), and for the rest see the [matrix](#connector-matrix) below, whose rows lead to each connector's Security
section. A plugin author who wants the module in a new connector reads
[the developer guide](CONNECTOR_DEVELOPER_GUIDE.md#tls-for-a-connector-the-shared-module).

This page has two parts: [TLS](#connector-matrix), the shared `tls.*` settings, and the [outbound HTTP proxy](#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers), the shared `proxy:` block.

## Contents

[Connector matrix](#connector-matrix)

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

## Connector matrix

Every connector with a network client reads these settings. This is what each does with them, and how that was checked.

| Connector | TLS is on when | Client certificate | `protocols` / `cipher-suites` | `verify-hostname: false` | Expiry in health | Checked against |
|---|---|---|---|---|---|---|
| [Kafka](KAFKA_CONNECTOR.md#11-security-tls-sasl-and-confluent) | `security.protocol: SSL` / `SASL_SSL` | yes | yes | yes | yes | a Kafka broker in Docker |
| [ActiveMQ](ACTIVEMQ_CONNECTOR.md#14-security) | `broker-url` has `ssl://` | yes | checked, not narrowed (JVM's) | yes | yes | ActiveMQ Classic in Docker |
| [RabbitMQ](RABBITMQ_CONNECTOR.md#12-security) | `amqps://` | yes (also the log-in, `auth-mechanism: external`) | yes | yes | yes | RabbitMQ 4.1 in Docker |
| [Redis](REDIS_CONNECTOR.md#security-tls-and-credentials) | `rediss://` | yes | yes | yes (chain only) | yes | Redis 8.2 in Docker, `tls-auth-clients yes` |
| [REST](REST_CONNECTOR.md#tls-for-the-rest-connector) | `https://` `base-url` | yes | yes | yes | yes | an HTTPS server in the test process |
| [Feeds](FEEDS_CONNECTOR.md#10-security) (a mirror) | `https://` `url` | yes | yes | yes | yes | an HTTPS server in the test process |
| [News](NEWS.md) (a mirror or proxy) | `https://` `base-url` | yes | yes | yes | no | no TLS-specific test: it applies the shared `TlsHttp` as REST and Feeds do |
| [yfinance](YFINANCE.md) (a mirror or proxy) | `https://` `base-url` | yes | yes | yes | no | as News |
| [S3](S3_CONNECTOR.md#tls-for-the-s3-connector) | `https://` `endpoint` | yes | checked, not narrowed | **refused** (the AWS client always checks) | yes | an HTTPS stand-in S3 in the test process |
| [MongoDB](MONGODB_CONNECTOR.md#security-tls-certificate-log-in-and-credentials) | `tls.enabled` or `tls=true` in the `uri` | yes (also the log-in, `auth-mechanism: x509`) | checked, not narrowed | yes | yes | MongoDB 7 in Docker, `requireTLS`, x.509 user |
| [PostgreSQL](POSTGRES_CONNECTOR.md#security-tls-and-credentials) (`jdbc:postgresql:`) | `tls.enabled` | yes (also the log-in, `cert` in `pg_hba.conf`) | yes | yes (`sslmode=require`) | yes | PostgreSQL 18 in Docker, `ssl=on` |
| [AI providers](../architecture/AI_PROVIDERS.md#119-tls-the-same-block-as-the-connectors) (every `type:` in `config/ai`, the console's AI and MCP clients) | `https://` `base-url` | yes | yes (the console: `TLSv1.2` / `TLSv1.3`, OpenSSL cipher names; Bedrock: no) | yes (Bedrock: **refused**) | yes (Admin → AI) | fake HTTPS servers with a test CA, mutual TLS and a CONNECT proxy tunnel, in Java and Python; the console reads PEM and PKCS12, not JKS |
| other JDBC drivers | not mapped: the driver's own options in the URL | | | | | documented only |
| [Aerospike](AEROSPIKE_CONNECTOR.md#security-tls-certificate-log-in-and-credentials) | `tls.enabled` | yes (also the log-in, `auth-mode: pki`) | yes | yes (no TLS name is given) | yes | unit tests of the wiring; the live check is a manual procedure (the server's TLS is Enterprise Edition) |
| [Delta](DELTA_CONNECTOR.md#security-tls-and-credentials) (native engine) | `https://` `s3.endpoint` | yes | yes | yes | yes | an HTTPS stand-in S3 in the test process; refused with `engine: hadoop` |
| [Iceberg](ICEBERG_CONNECTOR.md#security-tls-and-credentials) (REST catalog's object store) | `https://` `s3.endpoint` | yes | checked, not narrowed | **refused** | yes | an HTTPS stand-in S3 in the test process; refused with `catalog: hadoop` |

The rule is the same everywhere: `tls.*` against an address that is not TLS is a start-up error, never a silent downgrade, and a
wrong file or password stops the connector at start naming the setting and the file. Where a library has no hook for a
private CA, the connector says so at start and names the JVM truststore as the way out; those places are the Hadoop S3A
engine and path catalogs (Delta, Iceberg), and the REST catalog's own connection (Iceberg).

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

## Outbound HTTP proxy for connectors, AI providers and MCP servers

Some sites let a server reach the internet (or a vendor's API) only through a forward proxy. Drishti has **one mechanism** for that,
shared by every HTTP-based connector (REST, feeds, news, yfinance, S3, Delta and Iceberg over S3, the Kafka schema registry), by the AI
providers and by the MCP clients. It is configured the same way everywhere, under the prefix `proxy.`, next to the shared `tls.*`
settings ([TLS.md](TLS.md)). The code is `com.ash.drishti.api.proxy` in the plugin API (Spring-free, like the TLS module); the console's
AI and MCP clients, which are Python, follow the same rules in `core/ai/proxy.py`.

Contents: [1](#proxy-1-the-settings) the settings · [2](#proxy-2-where-the-effective-proxy-comes-from) where the effective proxy comes from ·
[3](#proxy-3-the-site-default) the site default · [4](#proxy-4-the-no-proxy-list) the no-proxy list · [5](#proxy-5-https-targets-and-tls) https targets and TLS ·
[6](#proxy-6-credentials) credentials · [7](#proxy-7-what-each-connector-does) what each connector does · [8](#proxy-8-seeing-the-proxy-in-admin) seeing it in Admin ·
[9](#proxy-9-the-console-ai-and-mcp-clients) the console · [10](#proxy-10-what-is-not-covered) what is not covered · [11](#proxy-11-troubleshooting) troubleshooting ·
[12](#proxy-12-how-it-is-tested) how it is tested

### Proxy 1. The settings

In a connector file the proxy is a `proxy:` block inside `settings:` (the file format flattens it to `proxy.url`, `proxy.username`, ...):

```yaml
# config/connectors/vendor-quotes.yaml
plugin: rest
kinds: [quote]
settings:
  base-url: https://api.vendor.example/v1
  proxy:
    url: http://proxy.corp.example:3128
    username: svc-drishti
    password: ${PROXY_PASSWORD}          # only an ${ENV} placeholder, or password-file: /run/secrets/proxy
    no-proxy: [intranet.example, .corp.example, 10.0.0.0/8]
```

| Setting | Default | Meaning |
|---|---|---|
| `proxy.url` | none | The proxy, `http://host:port` (a bare `host:port` means `http://`). Plain `http://` targets are forwarded through it; `https://` targets are tunnelled with `CONNECT`. Required when any other `proxy.*` key is given. |
| `proxy.username`, `proxy.password` | none | Basic proxy authentication. Give both or neither. The password is a secret: `${ENV}` placeholder only (a literal is refused, section 6); `proxy.password-file` reads the first line of a file. |
| `proxy.no-proxy` | none | Hosts that connect directly, bypassing the proxy ([section 4](#proxy-4-the-no-proxy-list)). A YAML list or a comma list. |
| `proxy.enabled` | `true` | `false` switches the proxy off for this connector without deleting the block. |
| `proxy: none` | | The bare setting. This connector goes direct whatever the site default says. (`off`, `false` and `direct` mean the same.) |

Ready-made files: [`config/connectors.examples/other/rest-via-proxy.yaml`](../../config/connectors.examples/other/rest-via-proxy.yaml) and [`s3-via-proxy.yaml`](../../config/connectors.examples/other/s3-via-proxy.yaml) (a test keeps every example valid).

The same block works in the files of `config/ai/` and `config/mcp/` ([section 9](#proxy-9-the-console-ai-and-mcp-clients)). The settings appear in the
connector form under **Proxy** for every plugin that speaks HTTP, and `drishti.py connector plugins rest` lists them.

The connection **to the proxy** is plain HTTP. A proxy that must itself be reached over TLS (`https://proxy`) is refused with a message that
says so; the JDK's HTTP client and the AWS clients Drishti uses cannot do it. This does not weaken anything for the target: an `https://` target is
tunnelled with `CONNECT`, so TLS is negotiated end to end with the target.

### Proxy 2. Where the effective proxy comes from

For each connector, highest first:

1. the connector's own `proxy:` block, or `proxy: none` (direct);
2. the **site default**, `drishti.http.proxy.*` in `application.yaml` ([section 3](#proxy-3-the-site-default));
3. the standard environment variables `HTTPS_PROXY`, `HTTP_PROXY` and `NO_PROXY`, only when the site asks with `drishti.http.proxy.from-environment: true`;
4. nothing: direct connections, as before this feature existed.

A connector's block replaces the site default **as a whole** (the settings are not merged); give the block its own `no-proxy`. The environment
variables are read by the target's scheme, as every tool does: `HTTPS_PROXY` for an `https://` address, `HTTP_PROXY` for `http://`. Credentials in
those variables (`http://user:pass@host:port`) are honoured and masked everywhere they could be shown. Admin shows which of the four applies
([section 8](#proxy-8-seeing-the-proxy-in-admin)); real values from the library:

```
connector:    {enabled=true, source=connector, sourceLabel=connector, url=http://proxy.corp.example:3128, username=svc-drishti, password=***, noProxy=[intranet.example, 10.0.0.0/8]}
site default: {enabled=true, source=site, sourceLabel=site default, url=http://proxy.corp.example:3128, username=null, password=null, noProxy=[]}
proxy: none:  {enabled=false, source=disabled, sourceLabel=disabled by the connector}
environment:  {enabled=true, source=environment, sourceLabel=environment (HTTPS_PROXY), url=http://envproxy.example:8080, username=bob, password=***, noProxy=[localhost, .local]}
```

### Proxy 3. The site default

`application.yaml` (or the environment variables Spring maps to it) carries the site's proxy. It is **off** unless `url` is set or
`from-environment` is true:

```yaml
drishti:
  http:
    proxy:
      url: http://proxy.corp.example:3128       # DRISHTI_HTTP_PROXY_URL
      username: svc-drishti                     # DRISHTI_HTTP_PROXY_USERNAME
      password: ${DRISHTI_HTTP_PROXY_PASSWORD:}  # a placeholder (with an empty default); a literal in the file stops the server at start
      no-proxy: [localhost, .corp.example, 10.0.0.0/8]
      enabled: true                             # false keeps the settings but switches the proxy off
      from-environment: false                   # true: with no url, honour HTTPS_PROXY / HTTP_PROXY / NO_PROXY
```

| Setting | Default | Meaning |
|---|---|---|
| `drishti.http.proxy.url` | empty (off) | The site's proxy, `http://host:port`. Every connector, AI provider and MCP server inherits it unless it has its own `proxy:` or `proxy: none`. |
| `drishti.http.proxy.username`, `.password` | empty | Basic authentication. The password is an `${ENV}` placeholder or the environment variable `DRISHTI_HTTP_PROXY_PASSWORD` itself; a literal password written in a configuration file stops the server at start. |
| `drishti.http.proxy.no-proxy` | empty list | Hosts that go direct under the site default. |
| `drishti.http.proxy.enabled` | `true` | `false` switches the site default off without removing it. |
| `drishti.http.proxy.from-environment` | `false` | When no `url` is set, use `HTTPS_PROXY` / `HTTP_PROXY` / `NO_PROXY`. |

The site default is read once, when the server starts; a change needs a restart. A connector's own `proxy:` block is live like any other
connector setting (the file is applied within seconds). The server logs one line at start: `site HTTP proxy: ProxySettings[site default, proxy.corp.example:3128, user=svc-drishti, password=***]`
(or `off`). The console reads the same `DRISHTI_HTTP_PROXY_*` environment variables for its own AI and MCP clients.

### Proxy 4. The no-proxy list

An entry is one of:

| Entry | Matches |
|---|---|
| `*` | every host (the proxy is never used) |
| `intranet.example` | that host and its sub-domains (`db.intranet.example`) |
| `.corp.example` or `*.corp.example` | the sub-domains, and the bare domain too (as `curl` does) |
| `10.1.2.3` | that address |
| `10.0.0.0/8`, `fd00::/8` | a block of addresses |
| `svc:8080` | the host `svc` (a port is ignored) |

Matching is case-insensitive. An address or block matches a target that is written as an address; **a host name is never resolved** to check it
against a block, because resolving would itself need the network. List the names too. The standard `NO_PROXY` variable uses the same entries, split on
commas.

### Proxy 5. https targets and TLS

An `https://` target (the usual case for a vendor API, an S3 endpoint, an AI provider) is reached by sending `CONNECT host:443` to the proxy and then
speaking TLS **through the tunnel to the target**. So every `tls.*` setting of the connector keeps its meaning, unchanged: the private CA in
`tls.ca-file`, a client certificate for mutual TLS, the protocol versions, host-name verification. The proxy sees the target's name and port, never
the traffic. A target whose certificate is not trusted fails exactly as it does without a proxy (`PKIX path building failed`), which the tests check
through the tunnel. The proxy's own credentials go only to the proxy, in the `CONNECT` request, and are never sent to the target.

A proxy that inspects TLS (re-signs certificates) is a different matter: its signing CA must be in `tls.ca-file` or `tls.truststore`, like any
private CA.

### Proxy 6. Credentials

* The proxy password is **never written in a file**. In a connector file it must be `${ENV}` (or `proxy.password-file`); saving a literal in Admin
  or through the API is refused: `settings.proxy.password: a credential is never written into a connector file`. A password inside the URL
  (`http://user:pw@proxy:3128`) is refused too, with a message that does not repeat it.
* Wherever the proxy is shown (the Admin connector page, the editor, the connection test, health, the API, the log) the username is shown and the
  password is `***`. Health strings and error messages carry the proxy's host and port, never a credential. The AI and MCP loaders refuse a literal
  `proxy.password` and say so without echoing it.
* Authentication is **Basic**. NTLM and Kerberos proxies are not supported (section 10). The JDK refuses Basic credentials for a `CONNECT` tunnel by
  default (`jdk.http.auth.tunneling.disabledSchemes=Basic`); Drishti turns that restriction off in the server's JVM when a proxy with a login is
  configured, unless you set the property yourself. If you do set it to include `Basic`, https targets through a proxy that needs a login will fail with 407.

### Proxy 7. What each connector does

| Connector | Through the proxy | How |
|---|---|---|
| [REST](REST_CONNECTOR.md#outbound-proxy) | yes | the JDK HTTP client's proxy selector and authenticator |
| [Feeds](FEEDS_CONNECTOR.md#outbound-proxy) | yes, built-in feeds and a mirror `url` | same |
| [News](NEWS.md#outbound-proxy) | yes | same |
| [yfinance](YFINANCE.md#outbound-proxy) | yes | same |
| [S3](S3_CONNECTOR.md#outbound-proxy) | yes, AWS and S3-compatible endpoints | the AWS SDK's proxy configuration; with a proxy the Apache HTTP client is used (the URL-connection client cannot answer a proxy's login when it tunnels https) |
| [Delta](DELTA_CONNECTOR.md#outbound-proxy) over S3 | yes, both engines | native engine: the SDK's proxy configuration; Hadoop engine: `fs.s3a.proxy.*` |
| [Iceberg](ICEBERG_CONNECTOR.md#outbound-proxy) over S3 / REST catalog | yes | the REST catalog's own client (`rest.client.proxy.*`), the S3 file IO (SDK proxy configuration), Hadoop `fs.s3a.proxy.*` for path tables |
| [Kafka](KAFKA_CONNECTOR.md#outbound-proxy) | the **schema registry** only (HTTP) | `HttpURLConnection` with the proxy and a per-connection authenticator. Brokers speak the Kafka protocol, not HTTP, and are not proxied |
| AI providers: OpenAI-compatible, Anthropic ([AI_PROVIDERS.md](../architecture/AI_PROVIDERS.md#outbound-proxy)) | yes | the JDK HTTP client (server), `httpx` (console) |
| MCP Streamable HTTP clients ([MCP_CLIENT.md](../architecture/MCP_CLIENT.md#outbound-proxy)) | yes | same |
| JDBC, Redis, MongoDB, Aerospike, ActiveMQ, RabbitMQ, files, DuckDB | no | not HTTP: they speak their own protocols (use a TCP-level route if the site needs one) |

When a connector's target is on the no-proxy list its health says so instead of claiming the proxy:
`UP (proxy proxy.corp.example:3128 not used for db.intranet.example: no-proxy)`. Otherwise it ends `... via proxy proxy.corp.example:3128 (connector)`
(or `(site default)`, `(environment)`).

### Proxy 8. Seeing the proxy in Admin

**Admin → Connectors → a connector** has a **Proxy** row for every plugin that speaks HTTP: the effective proxy (`http://host:port`), the
username and `password ***`, the no-proxy list, and where it came from (*connector*, *site default*, *environment (HTTPS_PROXY)*, or *disabled by the
connector*). When the connector's target is on the no-proxy list it says the connector goes direct. A malformed block shows the problem in red.

The **editor** has a **Proxy** section in the Form tab with the same fields as the file, and a line above them with the effective proxy now, for example
*Effective proxy: http://proxy.corp.example:3128, from the site default; user svc-drishti, password \*\*\*; direct for .corp.example.* A literal
password is refused in the field before anything is saved, like any credential.

**Test connection** starts a throwaway instance with the proxy the connector would use, so the test goes through it, and says so: *The test went
through the connector proxy proxy.corp.example:3128.* (or *The test went direct: the proxy is configured but ... is on the no-proxy list*). A
failure of the proxy itself comes with a plain-words hint (407, refused CONNECT, unreachable). The API shape (`GET /api/v1/admin/connectors/{name}`
and `POST .../test`):

```json
"proxy": { "enabled": true, "source": "connector", "sourceLabel": "connector", "url": "http://proxy.corp.example:3128",
           "username": "svc-drishti", "password": "***", "noProxy": ["intranet.example", "10.0.0.0/8"],
           "target": "api.vendor.example", "viaProxy": true }
```

### Proxy 9. The console, AI and MCP clients

`config/ai/*.yaml` (providers) and `config/mcp/*.yaml` (MCP servers) take the same block, read by both the server's Java clients and the console's
Python clients:

```yaml
# config/ai/openai.yaml
type: openai
base-url: https://api.openai.com
api-key: ${OPENAI_API_KEY}
proxy:
  url: http://proxy.corp.example:3128
  username: svc-drishti
  password: ${PROXY_PASSWORD}
  no-proxy: [localhost, .corp.example]
models:
  - name: fast
    id: gpt-4o-mini
```

`proxy: none` and the site default work as for connectors. In the server the site default is `drishti.http.proxy.*`; in the console it is the
environment variables `DRISHTI_HTTP_PROXY_URL`, `_USERNAME`, `_PASSWORD`, `_NO_PROXY`, `_ENABLED` and `_FROM_ENVIRONMENT` (the same ones Spring maps to
`drishti.http.proxy.*`, so one set configures both). **Change:** the console's `httpx` clients used to pick up `HTTP_PROXY` / `HTTPS_PROXY` from the
environment by themselves (`trust_env`); they no longer do, so that one setting controls it: set `DRISHTI_HTTP_PROXY_FROM_ENVIRONMENT=true` to keep
the old behaviour.

### Proxy 10. What is not covered

* **A proxy reached over TLS** (`https://proxy`): refused with a message. The JDK client and the AWS SDK clients do not support it.
* **NTLM, Kerberos, Digest** proxy authentication: Basic only. (The Apache-based SDK clients could do NTLM; it is not wired.)
* **SOCKS** proxies: `http://` proxies only.
* **PAC files / automatic proxy discovery**.
* **Kafka brokers, JDBC, Redis, MongoDB, Aerospike, ActiveMQ, RabbitMQ**: not HTTP.
* **The server's own other HTTP clients** (the pack registry client, report e-mail, OIDC discovery and JWKS fetch, the collaboration bridge): they are
  not connectors; they use the JDK default (direct) and can be sent through a proxy only with the JVM's own `-Dhttps.proxyHost` family of options
  for those that honour them. Say if a site needs them on the shared mechanism.
* The Admin → AI and MCP pages do not yet display the effective proxy (the connector pages do).
* Hadoop's S3A has no no-proxy list: the S3A proxy is set only when the endpoint is not on the connector's `no-proxy` list, and S3A reads `fs.s3a.proxy.*` once.
* Iceberg's REST catalog client takes a host and port from `rest.client.proxy.*` and has no no-proxy list; Drishti applies the proxy to the catalog only
  when its address is not on the connector's `no-proxy` list.

### Proxy 11. Troubleshooting

| You see | It means | Fix |
|---|---|---|
| `the proxy proxy.corp.example:3128 refused the credentials (HTTP 407 Proxy Authentication Required): check proxy.username and proxy.password` | The proxy asked for a login and the one given was wrong (or none was given). JDK clients say `too many authentication attempts` after three tries; tunnelled https says `Unable to tunnel through proxy. Proxy returns "HTTP/1.1 407 Proxy Authentication Required"` | Check the username, and the environment variable or file behind `proxy.password`. Test it with `curl -x http://proxy:3128 --proxy-user svc:... https://target`. |
| `the proxy ... refused to open a tunnel (CONNECT) to the target: Unable to tunnel through proxy. Proxy returns "HTTP/1.1 403 Forbidden"` | The proxy does not allow `CONNECT` to that host or port | Ask the proxy's owner to allow the target, usually port 443; check for a custom port in the connector's address. |
| `cannot connect to the proxy 10.1.2.3:3128 (Connection refused)` | Nothing listens there, or a firewall drops it | Check `proxy.url` (host, port, `http://`), that the proxy is up and reachable from the Drishti server. |
| `cannot resolve the name proxy.corp.example (DNS)` | The server cannot resolve the proxy's name | Use the proxy's address, or fix the server's DNS. If the target's name is the one the proxy cannot resolve, the proxy returns 502 and the message names the target. |
| `PKIX path building failed` through a proxy | The target's certificate (or the proxy's re-signing certificate) is not trusted | `tls.ca-file` with the issuer of what the server presents; [TLS.md](TLS.md). |
| `proxy.url: a proxy reached over TLS (https://) is not supported ...` | `proxy.url` starts with `https://` | Use `http://host:port`; https targets are still tunnelled. |
| `proxy.password must be an ${ENV_NAME} placeholder` / `a credential is never written into a connector file` | A literal password | Put it in an environment variable and write `${NAME}`. |
| A connector still connects directly | It is on a `no-proxy` list, says `proxy: none`, or its plugin does not speak HTTP | Admin → Connectors → the connector → **Proxy** row says which. |

Commands to try from the server's host: `curl -v -x http://proxy.corp.example:3128 https://api.vendor.example/` (a `407` here is the same 407),
`curl -v --proxytunnel -x http://proxy.corp.example:3128 https://api.vendor.example/` (the `CONNECT`).

### Proxy 12. How it is tested

No test uses the internet. `FakeProxy` (`drishti-testkit`) is a small forward proxy on a local port: it forwards plain HTTP, tunnels `CONNECT`,
counts both, can demand Basic `Proxy-Authorization` (answering 407 otherwise) and can refuse `CONNECT`. Tests for REST, feeds, news, yfinance, S3, Delta
(native S3 reader), Iceberg (S3 file IO and the S3A properties), the Kafka schema registry and the AI and MCP clients (Java and Python) check that
requests go through it with the right credentials, that an https target keeps its own TLS through the tunnel (and an untrusted one still fails), that
the no-proxy list bypasses it, that the site default is inherited and `proxy: none` overrides it, and that a literal password is refused. The server
tests check the masked Admin summary and the connection test; a Chromium test checks the connector page and the editor.
