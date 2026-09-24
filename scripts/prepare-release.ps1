#!/usr/bin/env pwsh
#Requires -Version 7.0
<#
.SYNOPSIS
  Dry-run fork release metadata preparation (no publish, no tracked file changes).

.DESCRIPTION
  1. Require clean git tree
  2. Invoke validate-release.ps1 (clears out/, builds artifacts)
  3. Create out/release-prep/ and write manifest/summary
  4. With -Tag: also write update.json.next when Tag matches packaged product version

  Post-7D acceptance: pwsh scripts/prepare-release.ps1 -Tag v3.2.0-oss.1
#>
[CmdletBinding()]
param(
    [string]$Tag,
    [string]$Repository = 'xStoikk/TrickyStoreOSS'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $RepoRoot

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Assert-CleanGitTree {
    $porcelain = @(git status --porcelain)
    if ($porcelain.Count -gt 0) {
        Write-Host ($porcelain -join [Environment]::NewLine)
        throw 'Git working tree is not clean. Commit or stash changes before release preparation.'
    }
}

function Find-ExactlyOneZip {
    param(
        [Parameter(Mandatory)]
        [string]$Pattern,
        [Parameter(Mandatory)]
        [string]$Label
    )
    $OutDir = Join-Path $RepoRoot 'out'
    $matches = @(Get-ChildItem -Path $OutDir -Filter $Pattern -File -ErrorAction SilentlyContinue)
    if ($matches.Count -ne 1) {
        $names = ($matches | ForEach-Object { $_.Name }) -join ', '
        throw "Expected exactly 1 $Label ZIP in out/; found $($matches.Count): $names"
    }
    return $matches[0]
}

function Get-FileSha256Hex([string]$Path) {
    return (Get-FileHash -Path $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-ZipEntrySha256Hex([string]$ZipPath, [string]$EntryName) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    $temp = $null
    try {
        $entry = $zip.GetEntry($EntryName)
        if ($null -eq $entry) {
            throw "Entry '$EntryName' not found in $ZipPath"
        }
        $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("ts-prep-" + [Guid]::NewGuid().ToString('N'))
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $temp, $true)
        return Get-FileSha256Hex $temp
    }
    finally {
        $zip.Dispose()
        if ($null -ne $temp -and (Test-Path $temp)) {
            Remove-Item $temp -Force -ErrorAction SilentlyContinue
        }
    }
}

function Read-ZipTextEntry([string]$ZipPath, [string]$EntryName) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $entry = $zip.GetEntry($EntryName)
        if ($null -eq $entry) {
            throw "Entry '$EntryName' not found in $ZipPath"
        }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try {
            return $reader.ReadToEnd()
        }
        finally {
            $reader.Dispose()
        }
    }
    finally {
        $zip.Dispose()
    }
}

function Parse-ModuleProp([string]$Text) {
    $map = @{}
    foreach ($line in ($Text -split "`n")) {
        $trimmed = $line.Trim()
        if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
        $idx = $trimmed.IndexOf('=')
        if ($idx -lt 1) { continue }
        $key = $trimmed.Substring(0, $idx).Trim()
        $value = $trimmed.Substring($idx + 1).Trim()
        $map[$key] = $value
    }
    return $map
}

function Get-PackagedProductVersion([string]$ModuleVersionLine) {
    if ($ModuleVersionLine -match '^(.+?)\s+\(\d+-[0-9a-fA-F]+-release\)$') {
        return $Matches[1].Trim()
    }
    throw "Cannot derive packaged product version from module.prop version line: $ModuleVersionLine"
}

function Assert-ReleaseTag([string]$Value) {
    if ($Value -notmatch '^v[A-Za-z0-9._-]+$') {
        throw "Release tag '$Value' is invalid. Expected pattern: v[A-Za-z0-9._-]+"
    }
}

function Get-VersionCodeEpochFromGradleProperties {
    $gradleProps = Join-Path $RepoRoot 'gradle.properties'
    if (-not (Test-Path $gradleProps)) {
        throw "Missing gradle.properties at $gradleProps"
    }
    $matches = @(Select-String -Path $gradleProps -Pattern '^\s*trickyStoreVersionCodeEpoch\s*=\s*(.+)\s*$')
    if ($matches.Count -ne 1) {
        throw "gradle.properties must define exactly one trickyStoreVersionCodeEpoch property (found $($matches.Count))"
    }
    $raw = $matches[0].Matches[0].Groups[1].Value.Trim()
    if ($raw -notmatch '^\d+$') {
        throw "trickyStoreVersionCodeEpoch must be a non-negative integer in gradle.properties: '$raw'"
    }
    $epoch = [int]$raw
    if ($epoch -lt 0) {
        throw "trickyStoreVersionCodeEpoch must be >= 0 in gradle.properties: $epoch"
    }
    return $epoch
}

function Get-TeeBuildPhaseFromGeneratedInfo {
    $teeBuildInfo = Join-Path $RepoRoot 'app/build/generated/source/teeBuildInfo/kotlin/io/github/beakthoven/TrickyStoreOSS/tee/TeeBuildInfo.kt'
    if (-not (Test-Path $teeBuildInfo)) {
        throw "Generated TeeBuildInfo.kt not found after validation: $teeBuildInfo"
    }
    $phaseLine = Select-String -Path $teeBuildInfo -Pattern 'const val PHASE = "([^"]+)"' | Select-Object -First 1
    if ($null -eq $phaseLine) {
        throw 'Could not read TeeBuildInfo.PHASE from generated TeeBuildInfo.kt'
    }
    return $phaseLine.Matches[0].Groups[1].Value
}

# 1. Repo root established above.
Write-Step 'Git tree'
# 2. Clean tree before any output mutation.
Assert-CleanGitTree
$branch = git branch --show-current
$fullSha = git rev-parse HEAD
$shortSha = git rev-parse --short=7 HEAD
$commitCount = [int](git rev-list HEAD --count)
$versionCodeEpoch = Get-VersionCodeEpochFromGradleProperties
$expectedVersionCode = $versionCodeEpoch + $commitCount
Write-Host "branch=$branch"
Write-Host "HEAD=$fullSha"
Write-Host "shortCommit=$shortSha"
Write-Host "commitCount=$commitCount"
Write-Host "versionCodeEpoch=$versionCodeEpoch"
Write-Host "expectedVersionCode=$expectedVersionCode"

# 4. validate-release clears out/ and builds exact artifacts.
Write-Step 'Release validation'
$validateScript = Join-Path $PSScriptRoot 'validate-release.ps1'
if (-not (Test-Path $validateScript)) {
    throw "Missing $validateScript"
}
& $validateScript
if ($LASTEXITCODE -ne 0) {
    throw "validate-release.ps1 failed with exit code $LASTEXITCODE"
}

# 5–6. Only after validate-release: inspect Release ZIP (never Debug).
Write-Step 'Collect release artifact metadata'
$releaseZip = Find-ExactlyOneZip -Pattern '*Release*.zip' -Label 'Release'
if ($releaseZip.Name -notmatch [regex]::Escape($shortSha)) {
    throw "Release ZIP filename must contain short SHA '$shortSha': $($releaseZip.Name)"
}

$releaseSha = Get-FileSha256Hex $releaseZip.FullName
$dexSha = Get-ZipEntrySha256Hex -ZipPath $releaseZip.FullName -EntryName 'classes.dex'
$modulePropText = Read-ZipTextEntry -ZipPath $releaseZip.FullName -EntryName 'module.prop'
$moduleProp = Parse-ModuleProp $modulePropText

$requiredKeys = @('id', 'name', 'version', 'versionCode', 'author', 'updateJson')
foreach ($key in $requiredKeys) {
    if (-not $moduleProp.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($moduleProp[$key])) {
        throw "Packaged module.prop missing required key: $key"
    }
}

if ($moduleProp['versionCode'] -notmatch '^\d+$') {
    throw "Packaged versionCode must be numeric: $($moduleProp['versionCode'])"
}
$packagedVersionCode = [int]$moduleProp['versionCode']
if ($packagedVersionCode -ne $expectedVersionCode) {
    throw "Packaged versionCode ($packagedVersionCode) != expected epoch versionCode ($expectedVersionCode = $versionCodeEpoch + $commitCount)"
}
if ($moduleProp['version'] -notmatch [regex]::Escape($shortSha)) {
    throw "Packaged module version must contain short SHA '$shortSha': $($moduleProp['version'])"
}
if ($moduleProp['version'] -notmatch '(?i)release') {
    throw "Packaged module version must reference Release variant: $($moduleProp['version'])"
}

$productVersion = Get-PackagedProductVersion $moduleProp['version']
$teePhase = Get-TeeBuildPhaseFromGeneratedInfo

if ($Tag) {
    Assert-ReleaseTag $Tag
    if ($Tag -ne $productVersion) {
        throw "Release tag '$Tag' does not match packaged product version '$productVersion'."
    }
}

# 5. Clear stale prep output only (validate-release already cleared out/ except we recreate prep subdir).
Write-Step 'Prepare release-prep output directory'
$prepDir = Join-Path $RepoRoot 'out/release-prep'
if (Test-Path $prepDir) {
    Remove-Item $prepDir -Recurse -Force
    Write-Host "Removed stale $prepDir"
}
New-Item -ItemType Directory -Force -Path $prepDir | Out-Null

$trackedUpdateJson = Join-Path $RepoRoot 'update.json'

# Artifact-derived manifest (schemaVersion 2). generatedAtUtc is audit-only; manifest bytes are not reproducible.
$manifest = [ordered]@{
    schemaVersion        = 2
    repository           = $Repository
    commit               = $fullSha
    shortCommit          = $shortSha
    commitCount          = $commitCount
    productVersion       = $productVersion
    versionCodeStrategy  = 'epoch+commitCount'
    versionCodeEpoch     = $versionCodeEpoch
    versionCode          = $packagedVersionCode
    moduleId             = $moduleProp['id']
    moduleName           = $moduleProp['name']
    moduleAuthor         = $moduleProp['author']
    moduleVersion        = $moduleProp['version']
    releaseZip           = $releaseZip.Name
    releaseZipSha256     = $releaseSha
    classesDexSha256     = $dexSha
    teeBuildPhase        = $teePhase
    teeBuildPhaseSource  = 'TeeBuildInfo.kt'
    packagedUpdateJson   = $moduleProp['updateJson']
    trackedUpdateJsonRef = if (Test-Path $trackedUpdateJson) { 'update.json' } else { $null }
    validation           = 'validate-release.ps1 PASS'
    generatedAtUtc       = (Get-Date).ToUniversalTime().ToString('o')
}

if ($Tag) {
    $manifest['releaseTag'] = $Tag
}

$manifestPath = Join-Path $prepDir 'release-manifest.json'
$manifest | ConvertTo-Json -Depth 4 | Set-Content -Path $manifestPath -Encoding utf8NoBOM

$summary = @"
# Release preparation summary

- repository: $Repository
- branch: $branch
- commit: $fullSha
- shortCommit: $shortSha
- commitCount: $commitCount
- productVersion: $productVersion
- versionCodeStrategy: epoch+commitCount
- versionCodeEpoch: $versionCodeEpoch
- versionCode: $packagedVersionCode
- module version: $($moduleProp['version'])
- release artifact: $($releaseZip.Name)
- release ZIP SHA256: $releaseSha
- classes.dex SHA256: $dexSha
- teeBuildPhase: $teePhase (from TeeBuildInfo.kt)
- validation: validate-release.ps1 PASS
- packaged updateJson: $($moduleProp['updateJson'])

Generated under ``out/release-prep/`` — not published.
"@
if ($Tag) {
    $summary += "`n- release tag: $Tag"
}
$summaryPath = Join-Path $prepDir 'release-summary.md'
Set-Content -Path $summaryPath -Value $summary.TrimEnd() -Encoding utf8NoBOM

if ($Tag) {
    Write-Step 'Candidate update.json.next'
    $zipUrl = "https://github.com/$Repository/releases/download/$Tag/$($releaseZip.Name)"
    $updateCandidate = [ordered]@{
        version     = $productVersion
        versionCode = $packagedVersionCode
        zipUrl      = $zipUrl
        changelog   = "https://raw.githubusercontent.com/$Repository/main/changelog.md"
    }
    $updatePath = Join-Path $prepDir 'update.json.next'
    $updateCandidate | ConvertTo-Json | Set-Content -Path $updatePath -Encoding utf8NoBOM
    Write-Host "Wrote $updatePath"
    Write-Host "zipUrl=$zipUrl"
}
else {
    Write-Host 'No -Tag supplied; skipped update.json.next (manifest and summary only).'
}

Write-Step 'Release preparation complete'
Write-Host "manifest=$manifestPath"
Write-Host "summary=$summaryPath"
