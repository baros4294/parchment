# Dot-source this file in the same shell as the release build. No passwords are printed or persisted as plaintext.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Security.Cryptography.ProtectedData
$signerRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../.parchment-local/signing'))
$metadata = Get-Content -LiteralPath (Join-Path $signerRoot 'metadata.json') -Raw | ConvertFrom-Json
$protected = [IO.File]::ReadAllBytes((Join-Path $signerRoot 'password.dpapi'))
$bytes = [Security.Cryptography.ProtectedData]::Unprotect($protected, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
try {
    $env:ANDROID_KEYSTORE_PATH = Join-Path $signerRoot 'release.p12'
    if (!(Test-Path -LiteralPath $env:ANDROID_KEYSTORE_PATH)) { throw 'Private signer file is unavailable.' }
    $env:ANDROID_KEYSTORE_PASSWORD = [Text.Encoding]::UTF8.GetString($bytes)
    $env:ANDROID_KEY_PASSWORD = $env:ANDROID_KEYSTORE_PASSWORD
    $env:ANDROID_KEY_ALIAS = $metadata.alias
    Write-Output "Loaded local signer. Public SHA-256: $($metadata.sha256)"
} finally { [Array]::Clear($bytes, 0, $bytes.Length) }
