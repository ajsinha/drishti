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
# Outbound HTTP proxy for connectors, AI providers and MCP servers

Some sites let a server reach the internet (or a vendor's API) only through a forward proxy. Drishti has **one mechanism** for that,
shared by every HTTP-based connector (REST, feeds, news, yfinance, S3, Delta and Iceberg over S3, the Kafka schema registry), by the AI
providers and by the MCP clients. It is configured the same way everywhere, under the prefix `proxy.`, next to the shared `tls.*`
settings ([TLS.md](TLS.md)). The code is `com.ash.drishti.api.proxy` in the plugin API (Spring-free, like the TLS module); the console's
AI and MCP clients, which are Python, follow the same rules in `core/ai/proxy.py`.

Contents: [1](#1-the-settings) the settings · [2](#2-where-the-effective-proxy-comes-from) where the effective proxy comes from ·
[3](#3-the-site-default) the site default · [4](#4-the-no-proxy-list) the no-proxy list · [5](#5-https-targets-and-tls) https targets and TLS ·
[6](#6-credentials) credentials · [7](#7-what-each-connector-does) what each connector does · [8](#8-seeing-the-proxy-in-admin) seeing it in Admin ·
[9](#9-the-console-ai-and-mcp-clients) the console · [10](#10-what-is-not-covered) what is not covered · [11](#11-troubleshooting) troubleshooting ·
[12](#12-how-it-is-tested) how it is tested

## 1. The settings

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
| `proxy.no-proxy` | none | Hosts that connect directly, bypassing the proxy ([section 4](#4-the-no-proxy-list)). A YAML list or a comma list. |
| `proxy.enabled` | `true` | `false` switches the proxy off for this connector without deleting the block. |
| `proxy: none` | | The bare setting. This connector goes direct whatever the site default says. (`off`, `false` and `direct` mean the same.) |

Ready-made files: [`config/connectors.examples/other/rest-via-proxy.yaml`](../../config/connectors.examples/other/rest-via-proxy.yaml) and [`s3-via-proxy.yaml`](../../config/connectors.examples/other/s3-via-proxy.yaml) (a test keeps every example valid).

The same block works in the files of `config/ai/` and `config/mcp/` ([section 9](#9-the-console-ai-and-mcp-clients)). The settings appear in the
connector form under **Proxy** for every plugin that speaks HTTP, and `drishti.py connector plugins rest` lists them.

The connection **to the proxy** is plain HTTP. A proxy that must itself be reached over TLS (`https://proxy`) is refused with a message that
says so; the JDK's HTTP client and the AWS clients Drishti uses cannot do it. This does not weaken anything for the target: an `https://` target is
tunnelled with `CONNECT`, so TLS is negotiated end to end with the target.

## 2. Where the effective proxy comes from

For each connector, highest first:

1. the connector's own `proxy:` block, or `proxy: none` (direct);
2. the **site default**, `drishti.http.proxy.*` in `application.yaml` ([section 3](#3-the-site-default));
3. the standard environment variables `HTTPS_PROXY`, `HTTP_PROXY` and `NO_PROXY`, only when the site asks with `drishti.http.proxy.from-environment: true`;
4. nothing: direct connections, as before this feature existed.

A connector's block replaces the site default **as a whole** (the settings are not merged); give the block its own `no-proxy`. The environment
variables are read by the target's scheme, as every tool does: `HTTPS_PROXY` for an `https://` address, `HTTP_PROXY` for `http://`. Credentials in
those variables (`http://user:pass@host:port`) are honoured and masked everywhere they could be shown. Admin shows which of the four applies
([section 8](#8-seeing-the-proxy-in-admin)); real values from the library:

```
connector:    {enabled=true, source=connector, sourceLabel=connector, url=http://proxy.corp.example:3128, username=svc-drishti, password=***, noProxy=[intranet.example, 10.0.0.0/8]}
site default: {enabled=true, source=site, sourceLabel=site default, url=http://proxy.corp.example:3128, username=null, password=null, noProxy=[]}
proxy: none:  {enabled=false, source=disabled, sourceLabel=disabled by the connector}
environment:  {enabled=true, source=environment, sourceLabel=environment (HTTPS_PROXY), url=http://envproxy.example:8080, username=bob, password=***, noProxy=[localhost, .local]}
```

## 3. The site default

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

## 4. The no-proxy list

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

## 5. https targets and TLS

An `https://` target (the usual case for a vendor API, an S3 endpoint, an AI provider) is reached by sending `CONNECT host:443` to the proxy and then
speaking TLS **through the tunnel to the target**. So every `tls.*` setting of the connector keeps its meaning, unchanged: the private CA in
`tls.ca-file`, a client certificate for mutual TLS, the protocol versions, host-name verification. The proxy sees the target's name and port, never
the traffic. A target whose certificate is not trusted fails exactly as it does without a proxy (`PKIX path building failed`), which the tests check
through the tunnel. The proxy's own credentials go only to the proxy, in the `CONNECT` request, and are never sent to the target.

A proxy that inspects TLS (re-signs certificates) is a different matter: its signing CA must be in `tls.ca-file` or `tls.truststore`, like any
private CA.

## 6. Credentials

* The proxy password is **never written in a file**. In a connector file it must be `${ENV}` (or `proxy.password-file`); saving a literal in Admin
  or through the API is refused: `settings.proxy.password: a credential is never written into a connector file`. A password inside the URL
  (`http://user:pw@proxy:3128`) is refused too, with a message that does not repeat it.
* Wherever the proxy is shown (the Admin connector page, the editor, the connection test, health, the API, the log) the username is shown and the
  password is `***`. Health strings and error messages carry the proxy's host and port, never a credential. The AI and MCP loaders refuse a literal
  `proxy.password` and say so without echoing it.
* Authentication is **Basic**. NTLM and Kerberos proxies are not supported (section 10). The JDK refuses Basic credentials for a `CONNECT` tunnel by
  default (`jdk.http.auth.tunneling.disabledSchemes=Basic`); Drishti turns that restriction off in the server's JVM when a proxy with a login is
  configured, unless you set the property yourself. If you do set it to include `Basic`, https targets through a proxy that needs a login will fail with 407.

## 7. What each connector does

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

## 8. Seeing the proxy in Admin

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

## 9. The console, AI and MCP clients

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

## 10. What is not covered

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

## 11. Troubleshooting

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

## 12. How it is tested

No test uses the internet. `FakeProxy` (`drishti-testkit`) is a small forward proxy on a local port: it forwards plain HTTP, tunnels `CONNECT`,
counts both, can demand Basic `Proxy-Authorization` (answering 407 otherwise) and can refuse `CONNECT`. Tests for REST, feeds, news, yfinance, S3, Delta
(native S3 reader), Iceberg (S3 file IO and the S3A properties), the Kafka schema registry and the AI and MCP clients (Java and Python) check that
requests go through it with the right credentials, that an https target keeps its own TLS through the tunnel (and an untrusted one still fails), that
the no-proxy list bypasses it, that the site default is inherited and `proxy: none` overrides it, and that a literal password is refused. The server
tests check the masked Admin summary and the connection test; a Chromium test checks the connector page and the editor.
