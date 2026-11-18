# Retakes the README screenshots (docs/images/*-ar.png and *-en.png) from the real app.
# Run from anywhere: .\docs\images\screenshots.ps1
# Needs a desktop session (it opens the server and client windows for a few seconds) and a free port 5000,
# because the port is visible in the pictures.
param([int]$Port = 5000)
$ErrorActionPreference = "Stop"

$repo = Resolve-Path "$PSScriptRoot\..\.."
$images = "$repo\docs\images"

if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "Port $Port is in use. Stop the server running on it first."
}

Push-Location $repo
try {
    $ErrorActionPreference = "Continue" # Maven and JavaFX print harmless warnings on stderr
    & .\mvnw.cmd -q test-compile dependency:build-classpath "-Dmdep.outputFile=target\screenshots-cp.txt" 2>&1 |
        ForEach-Object { "$_" } | Where-Object { $_ -match 'ERROR' }
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
} finally {
    $ErrorActionPreference = "Stop"
    Pop-Location
}
$cp = (Get-Content "$repo\target\screenshots-cp.txt" -Raw).Trim() + ";$repo\target\classes;$repo\target\test-classes"

foreach ($lang in "ar", "en") {
    # A fresh home and working directory per run: nothing of the real user's setup is read or changed.
    $run = Join-Path $env:TEMP ("ft-screenshots-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
    New-Item -ItemType Directory -Force "$run\home", "$run\work" | Out-Null
    Push-Location "$run\work"
    try {
        $ErrorActionPreference = "Continue" # JavaFX logs harmless warnings on stderr
        & java "-Duser.home=$run\home" -cp $cp com.hamza.filetransfer.client.ReadmeScreenshots $lang $images $Port 2>&1 |
            ForEach-Object { "$_" } | Where-Object { $_ -match '^saved |Exception' }
        if ($LASTEXITCODE -ne 0) { throw "Screenshots for '$lang' failed ($LASTEXITCODE)" }
    } finally {
        $ErrorActionPreference = "Stop"
        Pop-Location
        Remove-Item -Recurse -Force $run -ErrorAction SilentlyContinue
    }
}
