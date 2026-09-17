param(
    [string]$KeytoolPath = "",
    [string]$SigningDirectory = "$env:LOCALAPPDATA\CodexTools\android-vlm-ledger\signing",
    [string]$Alias = "ledger-release"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($KeytoolPath)) {
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        throw "JAVA_HOME is required when -KeytoolPath is not supplied"
    }
    $KeytoolPath = Join-Path $env:JAVA_HOME "bin\keytool.exe"
}

$keytool = [System.IO.Path]::GetFullPath($KeytoolPath)
$signingRoot = [System.IO.Path]::GetFullPath($SigningDirectory)
$keystorePath = Join-Path $signingRoot "android-cloud-ledger-release.p12"
$propertiesPath = Join-Path $signingRoot "release-signing.properties"

if (-not (Test-Path -LiteralPath $keytool -PathType Leaf)) {
    throw "keytool was not found at the requested path"
}
if (Test-Path -LiteralPath $keystorePath -PathType Leaf) {
    throw "A release keystore already exists; refusing to overwrite it"
}
if (Test-Path -LiteralPath $propertiesPath -PathType Leaf) {
    throw "Release signing properties already exist; refusing to overwrite them"
}

[System.IO.Directory]::CreateDirectory($signingRoot) | Out-Null
$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$directoryAcl = [System.Security.AccessControl.DirectorySecurity]::new()
$directoryAcl.SetAccessRuleProtection($true, $false)
$directoryRule = [System.Security.AccessControl.FileSystemAccessRule]::new(
    $identity,
    [System.Security.AccessControl.FileSystemRights]::FullControl,
    [System.Security.AccessControl.InheritanceFlags]"ContainerInherit, ObjectInherit",
    [System.Security.AccessControl.PropagationFlags]::None,
    [System.Security.AccessControl.AccessControlType]::Allow
)
$directoryAcl.AddAccessRule($directoryRule)
Set-Acl -LiteralPath $signingRoot -AclObject $directoryAcl

$randomBytes = [byte[]]::new(32)
[System.Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
$password = [Convert]::ToBase64String($randomBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
[Array]::Clear($randomBytes, 0, $randomBytes.Length)

try {
    & $keytool `
        -genkeypair `
        -noprompt `
        -storetype PKCS12 `
        -keystore $keystorePath `
        -storepass $password `
        -keypass $password `
        -alias $Alias `
        -keyalg RSA `
        -keysize 4096 `
        -sigalg SHA256withRSA `
        -validity 36500 `
        -dname "CN=Android Cloud Ledger, O=VibrateZ, C=CN"
    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed with exit code $LASTEXITCODE"
    }

    $escapedPath = $keystorePath.Replace("\", "\\")
    $properties = @(
        "storeFile=$escapedPath"
        "storePassword=$password"
        "keyAlias=$Alias"
        "keyPassword=$password"
    ) -join [Environment]::NewLine
    [System.IO.File]::WriteAllText(
        $propertiesPath,
        $properties + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false)
    )

    $fileAcl = [System.Security.AccessControl.FileSecurity]::new()
    $fileAcl.SetAccessRuleProtection($true, $false)
    $fileRule = [System.Security.AccessControl.FileSystemAccessRule]::new(
        $identity,
        [System.Security.AccessControl.FileSystemRights]::FullControl,
        [System.Security.AccessControl.AccessControlType]::Allow
    )
    $fileAcl.AddAccessRule($fileRule)
    Set-Acl -LiteralPath $keystorePath -AclObject $fileAcl
    Set-Acl -LiteralPath $propertiesPath -AclObject $fileAcl

    Write-Output "Release signing material was created outside the workspace."
    Write-Output "Keystore: $keystorePath"
    Write-Output "Properties: $propertiesPath"
    Write-Output "Back up both files together; losing either prevents future app updates."
} catch {
    if (Test-Path -LiteralPath $keystorePath -PathType Leaf) {
        Remove-Item -LiteralPath $keystorePath -Force
    }
    if (Test-Path -LiteralPath $propertiesPath -PathType Leaf) {
        Remove-Item -LiteralPath $propertiesPath -Force
    }
    throw
} finally {
    $password = $null
}
