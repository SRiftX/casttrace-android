[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$signing = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'CastTrace/signing'
$previous = [Environment]::GetEnvironmentVariable('CASTTRACE_QA_PASSWORD', 'Process')
$secure = Import-Clixml -LiteralPath (Join-Path $signing 'password.dpapi.xml')
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
Push-Location $root
try {
    $env:CASTTRACE_QA_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    if (!$env:ANDROID_HOME) {
        $env:ANDROID_HOME = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Android/Sdk'
    }
    $env:TEMP = 'C:/tmp'
    $env:TMP = 'C:/tmp'
    & ./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'QA build failed' }
    $out = Join-Path $root 'build/qa-danmaku'
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    $signer = Join-Path $env:ANDROID_HOME 'build-tools/35.0.0/apksigner.bat'
    foreach ($item in @(
        @{Source='app/build/outputs/apk/debug/app-debug.apk'; Name='app-qa.apk'},
        @{Source='app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'; Name='app-qa-test.apk'}
    )) {
        & $signer sign --ks (Join-Path $signing 'casttrace-release.p12') --ks-key-alias casttrace-release --ks-pass env:CASTTRACE_QA_PASSWORD --key-pass env:CASTTRACE_QA_PASSWORD --out (Join-Path $out $item.Name) $item.Source
        if ($LASTEXITCODE -ne 0) { throw 'QA signing failed' }
        & $signer verify --print-certs (Join-Path $out $item.Name)
        if ($LASTEXITCODE -ne 0) { throw 'QA signature verification failed' }
    }
} finally {
    Pop-Location
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    [Environment]::SetEnvironmentVariable('CASTTRACE_QA_PASSWORD', $previous, 'Process')
}
