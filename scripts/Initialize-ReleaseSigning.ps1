[CmdletBinding()]
param(
    [string]$SigningDirectory = (Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'CastTrace/signing'),
    [Parameter(Mandatory = $true)][string]$BackupDirectory
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
function Protect-Directory([string]$Path) {
    $null = New-Item -ItemType Directory -Path $Path -Force
    $acl = Get-Acl -LiteralPath $Path
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($rule in @($acl.Access)) { $null = $acl.RemoveAccessRuleSpecific($rule) }
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new('S-1-5-18'), 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
    Set-Acl -LiteralPath $Path -AclObject $acl
}
$keytool = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
if (!(Test-Path -LiteralPath $keytool)) { throw 'Set JAVA_HOME to a JDK before initializing signing.' }
$key = Join-Path $SigningDirectory 'casttrace-release.p12'
$secret = Join-Path $SigningDirectory 'password.dpapi.xml'
if (Test-Path -LiteralPath $key) {
    if (!(Test-Path -LiteralPath $secret)) { throw 'Existing key found without local credentials. Restore credentials; never replace the key.' }
    Write-Output "Existing key retained: $key"
    return
}
if ((Test-Path -LiteralPath $SigningDirectory) -and (Get-ChildItem -LiteralPath $SigningDirectory -Force | Select-Object -First 1)) {
    throw 'Signing directory is not empty. Inspect it before creating a key.'
}
if (Test-Path -LiteralPath (Join-Path $BackupDirectory 'casttrace-release.p12')) { throw 'A backup already exists. Restore it instead of creating another identity.' }
Protect-Directory $SigningDirectory
Protect-Directory $BackupDirectory
$bytes = New-Object byte[] 48
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
$password = [Convert]::ToBase64String($bytes)
try {
    $env:CASTTRACE_INIT_PASSWORD = $password
    & $keytool -genkeypair -noprompt -keystore $key -storetype PKCS12 -alias casttrace-release -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 -dname 'CN=CastTrace Android, O=CastTrace' -storepass:env CASTTRACE_INIT_PASSWORD -keypass:env CASTTRACE_INIT_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed. Inspect the private directory before retrying.' }
    ConvertTo-SecureString $password -AsPlainText -Force | Export-Clixml -LiteralPath $secret
    # Portable recovery is intentionally private; DPAPI alone cannot survive a Windows reinstall.
    [IO.File]::WriteAllText((Join-Path $SigningDirectory 'RECOVERY-PASSWORD.txt'), $password, [Text.UTF8Encoding]::new($false))
    & $keytool -exportcert -rfc -keystore $key -alias casttrace-release -storepass:env CASTTRACE_INIT_PASSWORD -file (Join-Path $SigningDirectory 'release-certificate.pem')
    if ($LASTEXITCODE -ne 0) { throw 'Certificate export failed.' }
    $certificateInfo = & $keytool -list -v -keystore $key -alias casttrace-release -storepass:env CASTTRACE_INIT_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Key verification failed.' }
    $certificateInfo | Set-Content -LiteralPath (Join-Path $SigningDirectory 'certificate-info.txt') -Encoding utf8
    Copy-Item -LiteralPath $key -Destination (Join-Path $BackupDirectory 'casttrace-release.p12')
    Copy-Item -LiteralPath (Join-Path $SigningDirectory 'release-certificate.pem') -Destination $BackupDirectory
    if ((Get-FileHash -LiteralPath $key).Hash -ne (Get-FileHash -LiteralPath (Join-Path $BackupDirectory 'casttrace-release.p12')).Hash) { throw 'Backup hash mismatch.' }
    Write-Output "Created and verified encrypted PKCS12 key: $key"
    Write-Output "Encrypted key backup: $BackupDirectory"
    Write-Output "Local build credentials: $secret (Windows user protected)"
    Write-Output "Portable password is in the private signing directory. Store it in your password manager, separately from the key backup."
} finally {
    Remove-Item Env:CASTTRACE_INIT_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
    [Array]::Clear($bytes, 0, $bytes.Length)
}
