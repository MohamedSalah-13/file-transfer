<#
.SYNOPSIS
    Builds a Windows installer with two Start menu shortcuts: "File Transfer" (the client)
    and "File Transfer Server". Users don't need Java or Maven: the installer has its own runtime.

.DESCRIPTION
    1. javafx:jlink makes a runtime image (target/app) with the app's module, JavaFX and AtlantaFX.
       It keeps bin\keytool.exe: the server creates its TLS certificate with it on first start.
       (jpackage's own jlink step would strip it.)
    2. jpackage turns that image into an installer in target/installer.

    Needs a JDK 17+ with jpackage (JAVA_HOME or PATH). The exe and msi types also need the
    WiX Toolset (3.x works with JDK 17+, 4+ needs JDK 24+). app-image needs nothing extra: it
    makes a folder you can zip and run without installing.

.EXAMPLE
    .\packaging\package.ps1                  # target\installer\File Transfer-<version>.exe
    .\packaging\package.ps1 -Type app-image  # target\installer\File Transfer\ (no WiX needed)
#>
param(
    [ValidateSet('exe', 'msi', 'app-image')]
    [string]$Type = 'exe'
)
# No global ErrorActionPreference=Stop: in Windows PowerShell 5.1 it turns any stderr line from
# mvnw or jpackage (JDK warnings) into a failure. Each step checks its exit code instead.
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root -ErrorAction Stop

$version = ([xml](Get-Content -Raw -Encoding UTF8 pom.xml)).project.version
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw "pom.xml version '$version' must be MAJOR.MINOR.PATCH for an installer" }

$jpackage = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\jpackage.exe'))) {
    Join-Path $env:JAVA_HOME 'bin\jpackage.exe'
} else {
    (Get-Command jpackage -ErrorAction SilentlyContinue).Source
}
if (-not $jpackage) { throw 'jpackage not found: install a JDK 17+ and set JAVA_HOME' }

Write-Host "Building the runtime image (version $version)..."
& .\mvnw.cmd -q clean compile javafx:jlink
if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' }
if (-not (Test-Path target\app\bin\keytool.exe)) { throw 'The runtime image has no keytool.exe: the server could not create its certificate' }

$dest = 'target\installer'
New-Item -ItemType Directory -Force $dest | Out-Null

$jpArgs = @(
    '--type', $Type,
    '--name', 'File Transfer',
    '--app-version', $version,
    '--vendor', 'File Transfer',
    '--description', 'LAN file transfer and chat',
    '--runtime-image', 'target\app',
    '--module', 'com.hamza.filetransfer/com.hamza.filetransfer.client.ClientLauncher',
    '--add-launcher', 'File Transfer Server=packaging\server-launcher.properties',
    '--dest', $dest
)
if ($Type -ne 'app-image') {
    $jpArgs += @(
        '--win-menu', '--win-menu-group', 'File Transfer',
        '--win-shortcut',
        '--win-dir-chooser',
        # No admin rights needed: installs into the user's AppData.
        '--win-per-user-install',
        # Never change this: it lets a newer installer replace an older version in place.
        '--win-upgrade-uuid', 'cb2471e0-5c11-47ed-8163-f2ec81a5127c'
    )
}

Write-Host "Running jpackage ($Type)..."
& $jpackage @jpArgs
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }
Get-ChildItem $dest | Format-Table Name, LastWriteTime, Length
