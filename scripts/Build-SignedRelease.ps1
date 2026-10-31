[CmdletBinding()]
param(
    [string]$SigningDirectory = (Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'CastTrace/signing'),
    [switch]$Bundle
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path $PSScriptRoot -Parent
$key = Join-Path $SigningDirectory 'casttrace-release.p12'
$secret = Join-Path $SigningDirectory 'password.dpapi.xml'
if (!(Test-Path -LiteralPath $key) -or !(Test-Path -LiteralPath $secret)) { throw 'Release signing is not initialized. See CONTRIBUTING.md.' }
$names = @('CASTTRACE_KEYSTORE','CASTTRACE_STORE_PASSWORD','CASTTRACE_KEY_ALIAS','CASTTRACE_KEY_PASSWORD')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$secure = Import-Clixml -LiteralPath $secret
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
Push-Location $root
try {
    $env:CASTTRACE_KEYSTORE = $key
    $env:CASTTRACE_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $env:CASTTRACE_KEY_PASSWORD = $env:CASTTRACE_STORE_PASSWORD
    $env:CASTTRACE_KEY_ALIAS = 'casttrace-release'
    $task = if ($Bundle) { ':app:bundleRelease' } else { ':app:assembleRelease' }
    # Forward slashes avoid Java properties escaping on Windows. No password on the command line.
    $tempDirectory = [IO.Path]::GetTempPath().Replace('\','/').TrimEnd('/')
    & ./gradlew.bat --no-daemon "-Dorg.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$tempDirectory" $task --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Signed release build failed.' }
    if (!$Bundle) {
        $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Android/Sdk' }
        $apksigner = Join-Path $sdk 'build-tools/35.0.0/apksigner.bat'
        if (!(Test-Path -LiteralPath $apksigner)) { throw 'Build Tools 35.0.0 apksigner is required to verify the release.' }
        $signature = & $apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
        if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
        $signature | Write-Output
        $certificate = [regex]::Match(($signature -join [Environment]::NewLine), 'certificate SHA-256 digest:\s*([0-9a-fA-F]+)')
        $expectedCertificate = 'D39B0394EE312DE5B1F25F8A215A0E81CA9C47229835D2F96DD0187694523C57'
        if (!$certificate.Success -or $certificate.Groups[1].Value.ToUpperInvariant() -ne $expectedCertificate) {
            throw 'APK certificate does not match the established CastTrace release identity.'
        }
        Get-FileHash app/build/outputs/apk/release/app-release.apk -Algorithm SHA256
    }
} finally {
    Pop-Location
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
