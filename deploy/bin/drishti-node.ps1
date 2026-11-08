# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

<#
.SYNOPSIS
  One Drishti server as a node of a cluster on Windows: its drain before a stop and its readiness (the PowerShell form of
  deploy/bin/drishti-node.sh; docs/guides/CLUSTER_GUIDE.md sections 4.6, 9.1 and 10.1, CD1 and CD2).

.DESCRIPTION
  drain       drains the node (forwarded work and VaR shards go to other members, readiness OUT_OF_SERVICE) and waits until it is
              safe to stop (exit 0) or -Wait seconds pass (exit 3). Run it before stopping the server's service or process.
  undrain     takes work again.
  status      the node's readiness and drain, as JSON.
  wait-ready  waits until /actuator/health/readiness answers UP (exit 0) or -Wait seconds pass (exit 3).
  The token is an administrator's personal API token with the node:drain scope: -TokenFile, else DRISHTI_DRAIN_TOKEN,
  DRISHTI_DRAIN_TOKEN_FILE or DRISHTI_TOKEN. With sign-in off none is needed. It is never printed.

.EXAMPLE
  .\deploy\bin\drishti-node.ps1 drain -Wait 240
.EXAMPLE
  .\deploy\bin\drishti-node.ps1 wait-ready -Url http://10.0.4.21:18480
#>
param(
    [Parameter(Position = 0)][ValidateSet('drain', 'undrain', 'status', 'wait-ready')][string]$Command = 'status',
    # The server.
    [string]$Url = $(if ($env:DRISHTI_NODE_URL) { $env:DRISHTI_NODE_URL } else { "http://localhost:$(if ($env:DRISHTI_PORT) { $env:DRISHTI_PORT } else { 18480 })" }),
    # Seconds to wait (drain: for safeToStop, default DRISHTI_DRAIN_WAIT or 90; wait-ready: 600).
    [int]$Wait = 0,
    # A file holding the token.
    [string]$TokenFile = ''
)
$ErrorActionPreference = 'Stop'
$Url = $Url.TrimEnd('/')

function Get-Token {
    if ($TokenFile) { return (Get-Content -Raw $TokenFile).Trim() }
    if ($env:DRISHTI_DRAIN_TOKEN) { return $env:DRISHTI_DRAIN_TOKEN }
    if ($env:DRISHTI_DRAIN_TOKEN_FILE -and (Test-Path $env:DRISHTI_DRAIN_TOKEN_FILE)) { return (Get-Content -Raw $env:DRISHTI_DRAIN_TOKEN_FILE).Trim() }
    return $env:DRISHTI_TOKEN
}

function Invoke-Node([string]$Method, [string]$Path, $Body = $null) {
    $headers = @{}
    $t = Get-Token
    if ($t) { $headers['Authorization'] = "Bearer $t" }
    $req = @{ Method = $Method; Uri = "$Url$Path"; Headers = $headers; TimeoutSec = 10 }
    if ($null -ne $Body) { $req['Body'] = ($Body | ConvertTo-Json -Compress); $req['ContentType'] = 'application/json' }
    return Invoke-RestMethod @req
}

switch ($Command) {
    'drain' {
        if ($Wait -le 0) { $Wait = if ($env:DRISHTI_DRAIN_WAIT) { [int]$env:DRISHTI_DRAIN_WAIT } else { 90 } }
        try { $a = Invoke-Node 'POST' '/api/v1/admin/node/drain' @{ on = $true } }
        catch { Write-Warning "drishti-node: the drain was refused or $Url did not answer: $($_.Exception.Message)"; exit 3 }
        Write-Host "drishti-node: draining $Url, waiting up to $Wait s for safeToStop"
        $end = (Get-Date).AddSeconds($Wait)
        while (-not $a.safeToStop) {
            if ((Get-Date) -ge $end) { Write-Warning "drishti-node: not safe to stop after $Wait s"; exit 3 }
            Start-Sleep -Seconds 2
            try { $a = Invoke-Node 'GET' '/api/v1/admin/node' } catch { $a = @{ safeToStop = $false } }
        }
        Write-Host 'drishti-node: safe to stop'
    }
    'undrain' { Invoke-Node 'POST' '/api/v1/admin/node/drain' @{ on = $false } | Out-Null; Write-Host 'drishti-node: undrained: takes work again' }
    'status' { Invoke-Node 'GET' '/api/v1/admin/node' | ConvertTo-Json -Depth 8 }
    'wait-ready' {
        if ($Wait -le 0) { $Wait = 600 }
        $end = (Get-Date).AddSeconds($Wait)
        while ($true) {
            try { Invoke-RestMethod -Uri "$Url/actuator/health/readiness" -TimeoutSec 5 | Out-Null; Write-Host 'drishti-node: ready'; break }
            catch { if ((Get-Date) -ge $end) { Write-Warning "drishti-node: not ready after $Wait s"; exit 3 }; Start-Sleep -Seconds 3 }
        }
    }
}
