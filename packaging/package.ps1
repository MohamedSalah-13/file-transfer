<#
.SYNOPSIS
    Builds the installer of this system: a Windows .exe, a Linux .deb or macOS .dmg files. Users
    don't need Java or Maven: every installer has its own runtime.

.DESCRIPTION
    1. mvnw test javafx:jlink makes a runtime image (target/app) with the app's module, JavaFX and
       AtlantaFX. It keeps bin/keytool: the server creates its TLS certificate with it on first start.
       (jpackage's own jlink step would strip it.)
    2. jpackage turns that image into the installer in target/installer:
         Windows: "File Transfer-<version>.exe", one installer with two launchers and two Start menu
                  shortcuts, "File Transfer" (the client) and "File Transfer Server".
         Linux:   FileTransfer-<version>-linux-<arch>.deb, the same two launchers with a menu entry each.
         macOS:   FileTransfer-<version>-macos-<arch>.dmg and FileTransferServer-<version>-macos-<arch>.dmg,
                  one app each: a macOS app has one icon and one entry in Launchpad.

    jpackage only builds for the system it runs on, so the Linux and macOS packages are made by
    .github/workflows/packages.yml on GitHub's Linux and macOS machines. The Windows installer is still
    published from the developer's computer by packaging\release.ps1, which runs this script.

    Every launcher gets -Dfiletransfer.version=<the real version> (client.Updates): macOS refuses a
    package version that starts with 0, so 0.x.y is packaged there as 1.x.y, and the updater must still
    compare the real one. The server's launcher also gets -Dfiletransfer.packaged=true, which keeps its
    data in ~/.file-transfer/server instead of the install folder (ServerConfig.workDir).

    The steps every program's packaging script repeats come from desktop-updater's
    tools/Packaging.psm1, found in a clone of desktop-updater beside this project or where
    DESKTOP_UPDATER_HOME points, as release.ps1 finds it. What is File Transfer's own stays here.

    Needs a JDK 17+ with jpackage (JAVA_HOME, PATH or ~/.jdks). Windows' exe and msi also need the WiX
    Toolset (3.x works with JDK 17+, 4+ needs JDK 24+); Linux's deb needs fakeroot. app-image needs nothing
    extra: it makes a folder you can zip and run without installing. Runs in Windows PowerShell 5.1
    and in PowerShell 7 (pwsh on Linux and macOS). The tests need a desktop session (ClientConnectionTest
    starts JavaFX); on a Linux machine without a screen run the script under xvfb-run, or use -SkipTests.

.EXAMPLE
    .\packaging\package.ps1                  # this system's installer (exe, deb or dmg)
    .\packaging\package.ps1 -Type app-image  # target\installer\File Transfer\ (no WiX needed)
    .\packaging\package.ps1 -SkipTests
#>
param(
    # Empty picks this system's usual installer: exe, deb or dmg.
    [ValidateSet('', 'exe', 'msi', 'deb', 'rpm', 'dmg', 'pkg', 'app-image')]
    [string]$Type = '',
    [switch]$SkipTests
)
# No global ErrorActionPreference=Stop: in Windows PowerShell 5.1 it turns any stderr line from
# mvnw or jpackage (JDK warnings) into a failure. Each step throws on its own failure instead.
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root -ErrorAction Stop

# desktop-updater is looked for beside the repository, wherever in it this script sits.
$updaterHome = $env:DESKTOP_UPDATER_HOME
if (-not $updaterHome) {
    $repository = & git -C $root rev-parse --show-toplevel 2>$null
    if (-not $repository) { $repository = $root }
    $updaterHome = Join-Path (Split-Path -Parent $repository) 'desktop-updater'
}
$packaging = Join-Path $updaterHome 'tools/Packaging.psm1'
if (-not (Test-Path -LiteralPath $packaging)) {
    throw "desktop-updater was not found at $updaterHome. Clone https://github.com/MohamedSalah-13/desktop-updater beside this project, or set DESKTOP_UPDATER_HOME."
}
Import-Module $packaging -Force -ErrorAction Stop

$platform = Get-PackagingPlatform
$Type = Resolve-PackageType -Type $Type -Platform $platform
$iconExt = Get-IconExtension -Platform $platform
$exe = if ($platform -eq 'windows') { '.exe' } else { '' }

$pom = Join-Path $root 'pom.xml'
$versions = Get-PomVersion -Pom $pom -Platform $platform
if ($versions.Raw -notmatch '^\d+\.\d+\.\d+$') { throw "pom.xml version '$($versions.Raw)' must be MAJOR.MINOR.PATCH for an installer" }
$version = $versions.Version
# The program reads its real version from -Dfiletransfer.version (client.Updates), which is what the
# updater compares; on macOS the package version of 0.x.y is 1.x.y.
$packageVersion = $versions.PackageVersion

$jpackage = (Find-JPackage -MinimumVersion 17).Path

# --- 1. runtime image
$goals = if ($SkipTests) { @('-q', 'clean', 'compile', 'javafx:jlink') } else { @('-q', 'clean', 'test', 'javafx:jlink') }
Write-Host "Building the runtime image (version $version$(if (-not $SkipTests) { ', with the tests' }))..."
Invoke-MavenBuild -Pom $pom -Arguments $goals
$runtime = Join-Path $root 'target/app'
if (-not (Test-Path (Join-Path $runtime "bin/keytool$exe"))) {
    throw 'The runtime image has no keytool: the server could not create its certificate'
}

$dest = Join-Path $root 'target/installer'
New-Item -ItemType Directory -Force $dest | Out-Null
$module = 'com.hamza.filetransfer'
$clientMain = "$module/com.hamza.filetransfer.client.ClientLauncher"
$serverMain = "$module/com.hamza.filetransfer.server.ServerLauncher"
$versionOption = "-Dfiletransfer.version=$version"
$serverOptions = "-Dfiletransfer.packaged=true $versionOption"
$common = @(
    '--app-version', $packageVersion,
    '--vendor', 'File Transfer',
    '--description', 'LAN file transfer and chat',
    '--runtime-image', $runtime
)

if ($platform -eq 'macos') {
    # --- 2. macOS: one app per program, each with its own icon, then a .dmg of each
    $arch = Get-PackagingArch
    $apps = @(
        @{ Name = 'File Transfer'; MenuName = 'File Transfer'; Id = 'com.hamza.filetransfer.client'
           Main = $clientMain; Icon = 'client'; Options = @($versionOption); File = 'FileTransfer' },
        # The name in the menu bar must stay under 16 characters.
        @{ Name = 'File Transfer Server'; MenuName = 'Transfer Server'; Id = 'com.hamza.filetransfer.server'
           Main = $serverMain; Icon = 'server'; Options = @('-Dfiletransfer.packaged=true', $versionOption)
           File = 'FileTransferServer' }
    )
    $imageDir = Join-Path $root 'target/app-images'
    foreach ($app in $apps) {
        $icon = Join-Path $PSScriptRoot "$($app.Icon).icns"
        Write-Host "Running jpackage (app-image, $($app.Name))..."
        $imageArgs = @('--type', 'app-image', '--name', $app.Name, '--module', $app.Main, '--icon', $icon,
            '--mac-package-identifier', $app.Id, '--mac-package-name', $app.MenuName, '--dest', $imageDir) + $common
        foreach ($option in $app.Options) { $imageArgs += @('--java-options', $option) }
        Invoke-Native -FilePath $jpackage -ArgumentList $imageArgs
        $image = Join-Path $imageDir "$($app.Name).app"
        if (-not (Test-Path (Join-Path $image 'Contents/runtime/Contents/Home/bin/keytool'))) {
            throw "$image has no keytool: the server could not create its certificate"
        }
        if ($Type -eq 'app-image') {
            Write-Host "Application: $image"
            continue
        }
        Write-Host "Running jpackage ($Type, $($app.Name))..."
        Invoke-Native -FilePath $jpackage -ArgumentList @('--type', $Type, '--app-image', $image, '--name', $app.Name,
            '--app-version', $packageVersion, '--vendor', 'File Transfer', '--icon', $icon, '--dest', $dest)
        # jpackage names it "<name>-<package version>"; the published name carries the real version. Both
        # apps land in one folder, so each is picked by its own name rather than by Rename-PlatformPackage.
        Get-ChildItem -LiteralPath $dest -File -Filter "$($app.Name)-*.$Type" |
            Rename-Item -NewName "$($app.File)-$version-macos-$arch.$Type" -Force
    }
} else {
    # --- 2. Windows and Linux: one installer, the client as the main launcher plus the server's
    # The server's launcher. java-options replaces the main launcher's, so it repeats the version.
    $serverLauncher = Join-Path $root 'target/server-launcher.properties'
    $lines = @(
        "# Second launcher in the installer: ""File Transfer Server"" (made by packaging/package.ps1).",
        "module=$serverMain",
        "java-options=$serverOptions",
        # The client icon with a server badge (made by IconMaker.java). Relative to the project root:
        # a Windows path's backslashes would be escapes in a .properties file.
        "icon=packaging/server.$iconExt"
    )
    if ($platform -eq 'windows') { $lines += @('win-menu=true', 'win-shortcut=true') } else { $lines += 'linux-shortcut=true' }
    # ASCII: no byte-order mark, which Windows PowerShell 5.1's UTF8 would write and jpackage would read as text.
    Set-Content -LiteralPath $serverLauncher -Value $lines -Encoding Ascii

    $jpArgs = @(
        '--type', $Type,
        '--name', 'File Transfer',
        '--module', $clientMain,
        '--java-options', $versionOption,
        '--icon', (Join-Path $PSScriptRoot "client.$iconExt"),
        '--add-launcher', "File Transfer Server=$serverLauncher",
        '--dest', $dest
    ) + $common
    if ($Type -ne 'app-image' -and $platform -eq 'windows') {
        [void](Find-WiX)
        # Per-user: no admin rights needed, it installs into the user's AppData. Never change the upgrade
        # UUID: it lets a newer installer replace an older version in place.
        $jpArgs += @(Get-WinInstallerArgument -PerUser -DirChooser -Menu -MenuGroup 'File Transfer' -Shortcut `
            -UpgradeUuid 'cb2471e0-5c11-47ed-8163-f2ec81a5127c')
    } elseif ($Type -ne 'app-image') {
        $jpArgs += @(
            '--linux-package-name', 'file-transfer',
            '--linux-shortcut',
            '--linux-menu-group', 'Network',
            '--linux-app-category', 'net'
        )
    }
    Write-Host "Running jpackage ($Type)..."
    Invoke-Native -FilePath $jpackage -ArgumentList $jpArgs

    # The Windows installer keeps jpackage's name: packaging\release.ps1 names it when it is published.
    if ($platform -ne 'windows' -and $Type -ne 'app-image') {
        if ($Type -eq 'deb') {
            $package = Get-ChildItem -LiteralPath $dest -File -Filter '*.deb' | Select-Object -First 1
            $listing = & dpkg-deb -c $package.FullName
            if (-not ($listing -match '/bin/keytool$')) { throw "$($package.Name) has no keytool: the server could not create its certificate" }
        }
        [void](Rename-PlatformPackage -Directory $dest -Type $Type -Name 'FileTransfer' -Version $version -Platform $platform)
    }
}
# jpackage leaves its installer read-only, and the next "mvnw clean" could not delete it.
[void](Clear-ReadOnly -Path $dest)
Get-ChildItem $dest | Format-Table Name, LastWriteTime, Length
