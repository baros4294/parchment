[CmdletBinding()]
param(
    [ValidateSet('Review', 'Test', 'Android', 'Final')]
    [string]$Mode = 'Review',
    [string[]]$Tests = @(),
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$AppVersion = '2.0.64',
    [ValidateRange(1, 2100000000)]
    [int]$AppVersionCode = 264,
    [switch]$Preview
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($Mode -eq 'Test' -and ($Tests.Count -eq 0 -or ($Tests | Where-Object { [string]::IsNullOrWhiteSpace($_) -or $_.StartsWith('-') }))) {
    throw 'Test requires explicit class/method filters, e.g. -Tests "*SessionSpendTest". Use Final for the full suite.'
}
if ($Mode -ne 'Test' -and $Tests.Count -gt 0) { throw '-Tests applies only to Test mode.' }

$command = 'git'
$arguments = @('-c', 'core.safecrlf=false', 'diff', '--check')
if ($Mode -ne 'Review') {
    $command = Join-Path $repoRoot 'gradlew.bat'
    $arguments = switch ($Mode) {
        'Test' {
            ':composeApp:desktopTest'
            foreach ($filter in $Tests) { '--tests'; $filter }
        }
        'Android' { ':composeApp:compileDebugKotlinAndroid' }
        'Final' {
            ':composeApp:desktopTest'
            ':composeApp:compileDebugKotlinAndroid'
            ':composeApp:assembleRelease'
            ':composeApp:createDistributable'
        }
    }
    $arguments = @($arguments) + @('--no-daemon', '--max-workers=2', '-Pkotlin.compiler.execution.strategy=in-process',
        "-PappVersion=$AppVersion", "-PappVersionCode=$AppVersionCode", '--console=plain', '--quiet')
}

if ($Preview) {
    $quoted = @($command) + $arguments | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }
    Write-Output ('& ' + ($quoted -join ' '))
    return
}

$savedEnvironment = @{}
try {
    if ($Mode -ne 'Review') {
        $jdkRoot = Join-Path $env:USERPROFILE '.jdks/temurin-21'
        $sdkRoot = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
        if (!(Test-Path -LiteralPath (Join-Path $jdkRoot 'bin/java.exe'))) { throw "JDK 21 unavailable: $jdkRoot" }
        if (!(Test-Path -LiteralPath $sdkRoot)) { throw "Android SDK unavailable: $sdkRoot" }
        if ($Mode -eq 'Final') {
            foreach ($name in @('ANDROID_KEYSTORE_PATH', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS')) {
                if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
                    throw 'Final requires the private signer. Dot-source scripts/load-private-signer.ps1 first.'
                }
            }
        }
        foreach ($name in @('JAVA_HOME', 'PATH', 'ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
            $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        }
        $env:JAVA_HOME = $jdkRoot
        $env:PATH = "$jdkRoot\bin;$env:PATH"
        $env:ANDROID_HOME = $sdkRoot
        $env:ANDROID_SDK_ROOT = $sdkRoot
    }
    Push-Location -LiteralPath $repoRoot
    try {
        & $command @arguments
        if ($LASTEXITCODE -ne 0) { throw "$Mode verification failed (exit $LASTEXITCODE)." }
        Write-Output "$Mode verification passed."
    } finally { Pop-Location }
} finally {
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
}
