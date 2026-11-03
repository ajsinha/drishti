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
# Arrow Flight from Go

The guide: [FLIGHT_GUIDE.md](../FLIGHT_GUIDE.md); every request: [FLIGHT_REFERENCE.md](../FLIGHT_REFERENCE.md).

## Install

`go get github.com/apache/arrow-go/v18/arrow/flight`.

## Connect

```go
creds := credentials.NewClientTLSFromCert(bankPool, "")        // a cert pool holding the bank's CA
client, _ := flight.NewClientWithMiddleware("drishti-1.bank.example:18815", nil, nil, grpc.WithTransportCredentials(creds))
ctx, _ := client.AuthenticateBasicToken(context.Background(), "token", os.Getenv("DRISHTI_TOKEN"))   // the session in ctx
```

## Read

```go
req := []byte(`{"form":"kind","request":{"kind":"trade","paths":["TRADE_ID","PRODUCT_TYPE"]}}`)
info, err := client.GetFlightInfo(ctx, &flight.FlightDescriptor{Type: flight.DescriptorCMD, Cmd: req})
for _, ep := range info.Endpoint {
    stream, _ := client.DoGet(ctx, ep.Ticket)
    rdr, _ := flight.NewRecordReader(stream)
    for rdr.Next() {
        rec := rdr.Record()
        _ = rec // …
    }
    rdr.Release()
}
```

(output from the demo lake: to be filled in the heavy run)

## Stream and errors

`"stream": true` in the request; `rdr.LatestAppMetadata()` holds each batch's sequence (8 bytes, little-endian); `"resume"` after a
break. A refusal is a gRPC status error whose message starts with the code (`DRS-5604: …`).
