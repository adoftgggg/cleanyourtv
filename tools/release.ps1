<#
.SYNOPSIS
    Publishes a launcher update for this fork (adoftgggg/cleanyourtv), the way the box's updater expects it.

.DESCRIPTION
    Two runs, so nothing goes public without a second, deliberate command:

      1. Prepare:  tools\release.ps1 -Version 3.4.9 -VersionCode 22 -Notes "What changed"
         Checks the checkout, bumps launcher/app/build.gradle, commits and pushes develop (never forced),
         builds with tests and lint, checks the APK (aapt: package, versionCode, versionName; apksigner: the
         expected certificate), writes app-release.apk.sha256 (lower-case hash, two spaces, name, CRLF), creates
         the Release as a DRAFT whose description starts with "versionCode: N", then downloads the draft's files
         and checks them again.

      2. Publish:  tools\release.ps1 -Version 3.4.9 -Publish
         Checks the draft once more (description, files, develop unchanged since the draft) and publishes it as
         the latest Release.

    Stops at the first check that fails. Never touches the keystore, other Releases or the upstream remote.
    Needs: gh signed in with write access to the fork, the Android SDK in launcher/local.properties, JDK 17+.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
    [int]$VersionCode,
    [string]$Notes,
    [switch]$Publish
)

# git, gh and apksigner write progress to stderr; under 'Stop' Windows PowerShell 5.1 would turn that into an
# error. Every native call checks $LASTEXITCODE instead, and file cmdlets pass -ErrorAction Stop themselves.
$ErrorActionPreference = 'Continue'
$Repo = 'adoftgggg/cleanyourtv'
$Package = 'com.gathin.tvhome'
$ExpectedCert = '87641a43f3840a667fae1f5fa5ff7a45b25a159886cad429b0416085c5630771'
$Tag = "v$Version"
$Root = Split-Path -Parent $PSScriptRoot
$Gradle = Join-Path $Root 'launcher\app\build.gradle'
$OutDir = Join-Path $Root "launcher\app\build\release-$Version"

function Step([string]$text) { Write-Host "`n== $text" -ForegroundColor Cyan }
function Ok([string]$text) { Write-Host "   OK  $text" -ForegroundColor Green }
function Fail([string]$text) { Write-Host "   STOP  $text" -ForegroundColor Red; exit 1 }
function Invoke-Git {
    $out = & git -C $Root @args 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { Fail "git $($args -join ' '): $($out -join ' ')" }
    return $out
}

function Get-BuildTools {
    $props = Join-Path $Root 'launcher\local.properties'
    if (-not (Test-Path $props)) { Fail "launcher\local.properties is missing (sdk.dir)" }
    $line = Get-Content $props | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
    if (-not $line) { Fail "sdk.dir is not set in launcher\local.properties" }
    $sdk = ($line.Substring(8) -replace '\\:', ':' -replace '\\\\', '\')
    $bt = Join-Path $sdk 'build-tools\34.0.0'
    if (-not (Test-Path (Join-Path $bt 'aapt.exe'))) { Fail "build-tools 34.0.0 not found under $sdk" }
    return $bt
}

# Checks one APK and its .sha256; returns the hash. Every failure stops the script.
function Test-ReleaseFiles([string]$dir, [int]$code, [string]$name) {
    $bt = Get-BuildTools
    $apk = Join-Path $dir 'app-release.apk'
    $sumFile = Join-Path $dir 'app-release.apk.sha256'
    foreach ($f in $apk, $sumFile) { if (-not (Test-Path $f)) { Fail "missing $f" } }

    $badging = (& (Join-Path $bt 'aapt.exe') dump badging $apk) | Where-Object { "$_" -like 'package:*' } | Select-Object -First 1
    $want = "package: name='$Package' versionCode='$code' versionName='$name'"
    if (-not "$badging".StartsWith($want)) { Fail "aapt: expected `"$want`", got `"$badging`"" }
    Ok "aapt: $Package, versionCode $code, versionName $name"

    $signer = (& (Join-Path $bt 'apksigner.bat') verify --print-certs -v $apk 2>&1) | ForEach-Object { "$_" }
    if (-not ($signer -contains 'Verifies')) { Fail "apksigner: the signature does not verify" }
    $certs = @($signer | Where-Object { $_ -match '^Signer #\d+ certificate SHA-256 digest: ' })
    if ($certs.Count -ne 1 -or -not $certs[0].EndsWith($ExpectedCert)) { Fail "apksigner: unexpected certificate: $($certs -join '; ')" }
    Ok "apksigner: verifies, certificate $ExpectedCert"

    $hash = (Get-FileHash -Algorithm SHA256 $apk).Hash.ToLower()
    $bytes = [IO.File]::ReadAllBytes($sumFile)
    $text = [Text.Encoding]::ASCII.GetString($bytes)
    if ($text -ne "$hash  app-release.apk`r`n") { Fail "app-release.apk.sha256 does not match the APK, or is not in the '<hash>  app-release.apk' + CRLF format" }
    Ok "sha256: $hash ($($bytes.Length) bytes)"
    return $hash
}

# ---- checks common to both runs -------------------------------------------------------------------------------

Step "Checkout and GitHub"
$branch = (Invoke-Git rev-parse --abbrev-ref HEAD) | Select-Object -First 1
if ($branch -ne 'develop') { Fail "the checkout is on '$branch', not develop" }
$upstreamPush = & git -C $Root remote get-url --push upstream 2>$null
if ($upstreamPush -and $upstreamPush -ne 'DISABLED') { Fail "pushing to upstream is not disabled ($upstreamPush)" }
$origin = (Invoke-Git remote get-url origin) | Select-Object -First 1
if ($origin -notmatch 'adoftgggg/cleanyourtv') { Fail "origin is $origin, not $Repo" }
& gh auth status 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "gh is not signed in (gh auth login)" }
Invoke-Git fetch origin --quiet | Out-Null
Ok "develop, origin $Repo, upstream push disabled, gh signed in"

if ($Publish) {
    # ---- run 2: publish the checked draft ------------------------------------------------------------------
    Step "Draft $Tag"
    $rel = gh release view $Tag --repo $Repo --json isDraft,body,targetCommitish | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or -not $rel) { Fail "no Release $Tag" }
    if (-not $rel.isDraft) { Fail "$Tag is already published" }
    if ($rel.body -notmatch '(?m)^versionCode: (\d+)') { Fail "the description does not start with 'versionCode: N'" }
    $code = [int]$Matches[1]
    $check = Join-Path $env:TEMP "cleanyourtv-publish-$Version"
    if (Test-Path $check) { Remove-Item -Recurse -Force $check -ErrorAction Stop }
    New-Item -ItemType Directory $check -ErrorAction Stop | Out-Null
    gh release download $Tag --repo $Repo --dir $check
    if ($LASTEXITCODE -ne 0) { Fail "could not download the draft's files" }
    Test-ReleaseFiles $check $code $Version | Out-Null

    $head = ((Invoke-Git ls-remote origin refs/heads/develop) -split '\s+')[0]
    $bumped = ((Invoke-Git log -1 --format=%H origin/develop -- launcher/app/build.gradle) | Select-Object -First 1)
    if ($head -ne $bumped) { Fail "develop moved since the version commit ($bumped -> $head): the tag would land on other code" }
    Ok "develop is still on the version commit $head"

    Step "Publishing $Tag"
    gh release edit $Tag --repo $Repo --draft=false --latest
    if ($LASTEXITCODE -ne 0) { Fail "gh release edit failed" }
    $latest = gh api "repos/$Repo/releases/latest" --jq .tag_name
    if ($latest -ne $Tag) { Fail "the latest Release is $latest, not $Tag" }
    Ok "$Tag is published and is the latest Release"
    exit 0
}

# ---- run 1: prepare the draft ----------------------------------------------------------------------------------

if (-not $VersionCode) { Fail "-VersionCode is required to prepare a Release" }
if (-not $Notes) { Fail "-Notes is required to prepare a Release" }

Step "Before the version commit"
$dirty = Invoke-Git status --porcelain
if ($dirty) { Fail "the checkout has uncommitted changes:`n$($dirty -join "`n")" }
$local = (Invoke-Git rev-parse HEAD) | Select-Object -First 1
$remote = (Invoke-Git rev-parse origin/develop) | Select-Object -First 1
if ($local -ne $remote) { Fail "develop ($local) is not origin/develop ($remote)" }
& gh release view $Tag --repo $Repo 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) { Fail "a Release $Tag already exists" }
$gradleText = [IO.File]::ReadAllText($Gradle)
if ($gradleText -notmatch 'versionCode (\d+)') { Fail "versionCode not found in build.gradle" }
$current = [int]$Matches[1]
if ($VersionCode -le $current) { Fail "versionCode $VersionCode is not above the current ${current}: the box would refuse it" }
Ok "clean, in step with origin, $Tag is free, versionCode $current -> $VersionCode"

Step "Version commit"
$gradleText = $gradleText -replace 'versionCode \d+', "versionCode $VersionCode" -replace "versionName '[^']*'", "versionName '$Version'"
[IO.File]::WriteAllText($Gradle, $gradleText)
$author = (Invoke-Git log -1 --format='%an|%ae') | Select-Object -First 1
$name, $mail = $author -split '\|', 2
Invoke-Git -c "user.name=$name" -c "user.email=$mail" commit -q launcher/app/build.gradle -m "Launcher: $Version (versionCode $VersionCode)" | Out-Null
Invoke-Git push origin develop | Out-Null
$sha = (Invoke-Git rev-parse HEAD) | Select-Object -First 1
Ok "committed and pushed $sha"

Step "Build (tests, lint, release APK)"
Push-Location (Join-Path $Root 'launcher')
try {
    & .\gradlew.bat --no-daemon clean testReleaseUnitTest lintRelease assembleRelease
    if ($LASTEXITCODE -ne 0) { Fail "the build failed" }
} finally { Pop-Location }
if (Test-Path $OutDir) { Remove-Item -Recurse -Force $OutDir -ErrorAction Stop }
New-Item -ItemType Directory $OutDir -ErrorAction Stop | Out-Null
Copy-Item (Join-Path $Root 'launcher\app\build\outputs\apk\release\app-release.apk') (Join-Path $OutDir 'app-release.apk') -ErrorAction Stop
$hash = (Get-FileHash -Algorithm SHA256 (Join-Path $OutDir 'app-release.apk')).Hash.ToLower()
[IO.File]::WriteAllText((Join-Path $OutDir 'app-release.apk.sha256'), "$hash  app-release.apk`r`n")
Test-ReleaseFiles $OutDir $VersionCode $Version | Out-Null

Step "Draft Release $Tag"
$body = "versionCode: $VersionCode`n`n$Notes`n`nSHA-256 : $hash"
gh release create $Tag (Join-Path $OutDir 'app-release.apk') (Join-Path $OutDir 'app-release.apk.sha256') `
    --repo $Repo --target develop --draft --title $Tag --notes $body
if ($LASTEXITCODE -ne 0) { Fail "gh release create failed" }
$check = Join-Path $env:TEMP "cleanyourtv-draft-$Version"
if (Test-Path $check) { Remove-Item -Recurse -Force $check -ErrorAction Stop }
New-Item -ItemType Directory $check -ErrorAction Stop | Out-Null
gh release download $Tag --repo $Repo --dir $check
if ($LASTEXITCODE -ne 0) { Fail "could not download the draft's files" }
Test-ReleaseFiles $check $VersionCode $Version | Out-Null
Ok "the draft's files check out"

Write-Host "`nDraft ready. Publish it with:  tools\release.ps1 -Version $Version -Publish" -ForegroundColor Yellow
