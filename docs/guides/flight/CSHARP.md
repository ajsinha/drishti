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
# Arrow Flight from C# (.NET)

The guide: [FLIGHT_GUIDE.md](../FLIGHT_GUIDE.md); every request: [FLIGHT_REFERENCE.md](../FLIGHT_REFERENCE.md).

## Install

NuGet: `Apache.Arrow.Flight` and `Grpc.Net.Client`.

## Connect

```csharp
var handler = new HttpClientHandler();               // trust the bank's CA (or install it in the machine's store)
handler.ServerCertificateCustomValidationCallback = (msg, cert, chain, errors) => TrustBankCa(cert, chain);
var channel = GrpcChannel.ForAddress("https://drishti-1.bank.example:18815", new GrpcChannelOptions { HttpHandler = handler });
var client = new FlightClient(channel);
var headers = new Metadata { { "authorization", "Bearer " + Environment.GetEnvironmentVariable("DRISHTI_TOKEN") } };
```

The first call (the handshake) answers `authorization: Bearer dfs_…`; send that session from then on (or the token on every call, which
the service checks each time).

## Read

```csharp
var request = "{\"form\":\"kind\",\"request\":{\"kind\":\"trade\",\"paths\":[\"TRADE_ID\",\"PRODUCT_TYPE\"]}}";
var info = await client.GetInfo(FlightDescriptor.CreateCommandDescriptor(request), headers);
foreach (var endpoint in info.Endpoints) {
    var stream = client.GetStream(endpoint.Ticket, headers);
    while (await stream.ResponseStream.MoveNext()) {
        RecordBatch batch = stream.ResponseStream.Current;
        // …
    }
}
```

(output from the demo lake: to be filled in the heavy run)

## Stream and errors

As in the [Python page](PYTHON.md): `"stream": true`, the sequence in each batch's app metadata (8 bytes, little-endian), `"resume"` after a
break. A refusal is an `RpcException` whose `Status.Detail` starts with the code (`DRS-5604: …`).
