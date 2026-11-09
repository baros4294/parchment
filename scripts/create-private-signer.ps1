param([string]$Alias = 'parchment-release')
$ErrorActionPreference = 'Stop'
if ($Alias -notmatch '^[a-zA-Z0-9_-]{1,64}$') { throw 'Invalid signer alias' }
Add-Type -AssemblyName System.Security.Cryptography.ProtectedData
$signerRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../.parchment-local/signing'))
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$signerRoot.StartsWith($workspaceRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Signer directory escapes the workspace.' }
[IO.Directory]::CreateDirectory($signerRoot) | Out-Null
$keyPath = Join-Path $signerRoot 'release.p12'
$passwordPath = Join-Path $signerRoot 'password.dpapi'
$metadataPath = Join-Path $signerRoot 'metadata.json'
if ((Test-Path -LiteralPath $keyPath) -or (Test-Path -LiteralPath $passwordPath) -or (Test-Path -LiteralPath $metadataPath)) {
    throw 'Signer files already exist. Keep the existing identity; do not overwrite it.'
}
$keytoolPath = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
if (!(Test-Path -LiteralPath $keytoolPath)) { throw 'Set JAVA_HOME to the verified JDK before creating a signer.' }
$stagedKey = Join-Path $signerRoot 'release-staged.p12'
if (Test-Path -LiteralPath $stagedKey) { throw 'A staged signer exists; inspect it before retrying.' }
$random = [byte[]]::new(32)
[Security.Cryptography.RandomNumberGenerator]::Fill($random)
$password = [Convert]::ToBase64String($random)
$protected = [Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes($password), $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
try {
    $env:PARCHMENT_SIGNER_PASSWORD = $password
    & $keytoolPath -genkeypair -storetype PKCS12 -keystore $stagedKey -alias $Alias -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 36500 -dname 'CN=Parchment Release,O=Parchment' -storepass:env PARCHMENT_SIGNER_PASSWORD -keypass:env PARCHMENT_SIGNER_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Private signer generation failed.' }
    $certificate = & $keytoolPath '-J-Duser.language=en' '-J-Duser.country=US' -list -v -keystore $stagedKey -alias $Alias -storepass:env PARCHMENT_SIGNER_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Private signer validation failed.' }
    $match = [regex]::Match(($certificate -join "`n"), 'SHA256:\s*([0-9A-Fa-f:]+)')
    $fingerprint = $match.Groups[1].Value.Replace(':','').ToLowerInvariant()
    if ($fingerprint.Length -ne 64 -or $fingerprint -eq 'ed96a0a733e9e6d38633f04a6a182c997fe60d480d9e33e5f078faa2536d26eb') { throw 'Invalid or exposed signer certificate.' }
    [IO.File]::WriteAllBytes($passwordPath, $protected)
    [IO.File]::WriteAllText($metadataPath, (@{alias=$Alias; sha256=$fingerprint} | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    foreach ($target in @($stagedKey, $keyPath)) {
        if (![IO.Path]::GetFullPath($target).StartsWith($signerRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid signer move target.' }
    }
    Move-Item -LiteralPath $stagedKey -Destination $keyPath
    Write-Output "Created protected local signer. Public SHA-256: $fingerprint"
} catch {
    if (!(Test-Path -LiteralPath $passwordPath) -and !(Test-Path -LiteralPath $keyPath) -and (Test-Path -LiteralPath $stagedKey)) {
        if (![IO.Path]::GetFullPath($stagedKey).StartsWith($signerRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid staged signer cleanup target.' }
        Remove-Item -LiteralPath $stagedKey
    }
    throw
} finally {
    $env:PARCHMENT_SIGNER_PASSWORD = $null
    $password = $null
    [Array]::Clear($random, 0, $random.Length)
}
