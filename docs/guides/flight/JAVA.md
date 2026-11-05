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
# Arrow Flight from Java

The guide: [FLIGHT_GUIDE.md](../FLIGHT_GUIDE.md); every request: [FLIGHT_REFERENCE.md](../FLIGHT_REFERENCE.md).

## Install

Maven: `org.apache.arrow:flight-core` and `org.apache.arrow:arrow-memory-netty`, version 19.0.0 or newer (the server's own). On Java 17
and later start the JVM with `--add-opens=java.base/java.nio=ALL-UNNAMED` (Arrow's memory module needs it).

## Connect

```java
BufferAllocator alloc = new RootAllocator();
FlightClient client = FlightClient.builder(alloc, Location.forGrpcTls("drishti-1.bank.example", 18815))
        .trustedCertificates(Files.newInputStream(Path.of("bank-ca.pem"))).build();
CallOption session = client.authenticateBasicToken("token", System.getenv("DRISHTI_TOKEN")).orElseThrow();
```

An application acting for a person adds the header at the handshake:
`client.authenticateBasicToken("token", token, new HeaderCallOption(headers))` with `x-drishti-on-behalf-of: alice` in `headers`.

## Read

```java
byte[] request = """
    {"form": "measure", "request": {"measure": "var.scenarios", "scope": "DESK:RATES"}}""".getBytes(StandardCharsets.UTF_8);
FlightInfo info = client.getInfo(FlightDescriptor.command(request), session);
for (FlightEndpoint e : info.getEndpoints()) {          // each part, from the first of its nodes (try the next when one is down)
    try (FlightStream s = client.getStream(e.getTicket(), session)) {
        while (s.next()) {
            VectorSchemaRoot root = s.getRoot();
            Float8Vector pnl = (Float8Vector) root.getVector("PNL");
            // …
        }
    }
}
```

Run as written (the elided body printing each batch's rows, its first P&L and the worst; Java 21; the test data of 2026-09-30, the
server's own port 18825):

```text
1 endpoint(s)
a batch of 509 rows from grpc+tls://192.168.2.77:18825; PNL[0] = 11,293,350.00; the worst = -119,535,289.00
metadata: {drishti.scope=DESK:RATES, drishti.var=7.463368E7, drishti.es=7.5377415E7, drishti.measure=var.scenarios, drishti.date=2026-09-30, drishti.currency=USD, drishti.intraday=0}
```

Java 17 to 21: on Java 25 Arrow 19's memory module does not start (`ExceptionInInitializerError` in `DefaultAllocationManagerFactory`).

A part on another node needs a client for that node's location (`e.getLocations().get(0)`), authenticated the same way.

## Stream

Add `"stream": true` to the request and read the ticket with `getStream`; each batch's sequence is
`s.getLatestMetadata().getLong(0)`. Ask again with `"resume": <sequence>` after a break.

## Example

`ReadKindAndVarPnl.java`, one file run with the source launcher (a kind, then a desk's VaR scenario P&L with the engine's VaR and
ES, each part from the first node that answers): [examples/clients/java](../../../examples/clients/java/README.md).

## Errors

A refusal is a `FlightRuntimeException`; `e.getMessage()` starts with the code (`DRS-5604: …`) and `e.status().metadata().get("drishti-code")`
carries it alone.
