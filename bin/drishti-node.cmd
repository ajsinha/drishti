@echo off
rem Project Drishti. Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. PROPRIETARY AND CONFIDENTIAL.
rem See the LICENSE file in the root of this repository for the full terms.
rem Runs bin\drishti-node.ps1 from cmd or a double-click (Windows PowerShell 5.1, no change to the execution policy); the same options.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0drishti-node.ps1" %*
exit /b %ERRORLEVEL%
