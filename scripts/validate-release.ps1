#!/usr/bin/env pwsh
#Requires -Version 7.0
<#
.SYNOPSIS
  Local release acceptance gate — mirrors Phase 7A/7B CI enforcement.

.DESCRIPTION
  Runs the full Gradle validation suite, discovers exactly one Release and one
  Debug module ZIP, prints SHA256 hashes, and validates packaged module.prop.

  Does NOT commit artifacts, require ADB/device/root, or run Play Integrity tests.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $RepoRoot

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Find-ExactlyOneZip {
    param(
        [Parameter(Mandatory)]
        [string]$Pattern,
        [Parameter(Mandatory)]
        [string]$Label
    )

    $matches = @(Get-ChildItem -Path (Join-Path $RepoRoot 'out') -Filter $Pattern -File -ErrorAction SilentlyContinue)
    if ($matches.Count -eq 0) {
        throw "Expected exactly 1 $Label ZIP in out/; found 0 (pattern: $Pattern)"
    }
    if ($matches.Count -gt 1) {
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
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq $EntryName -or $_.Name -eq $EntryName } | Select-Object -First 1
        if ($null -eq $entry) {
            throw "Entry '$EntryName' not found in $ZipPath"
        }
        $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("ts-validate-" + [Guid]::NewGuid().ToString('N'))
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $temp, $true)
        return Get-FileSha256Hex $temp
    }
    finally {
        $zip.Dispose()
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

Write-Step 'Git tree'
# porcelain: XY path — X/Y = staged/unstaged; ?? = untracked; all block release validation.
$porcelain = @(git status --porcelain)
if ($porcelain.Count -gt 0) {
    $staged = @($porcelain | Where-Object { $_ -match '^[MARCDGURBC].' })
    $unstaged = @($porcelain | Where-Object { $_ -match '^.[MARCDGURBC]' })
    $untracked = @($porcelain | Where-Object { $_ -match '^\?\?' })
    Write-Host ($porcelain -join [Environment]::NewLine)
    $parts = @()
    if ($staged.Count -gt 0) { $parts += "staged=$($staged.Count)" }
    if ($unstaged.Count -gt 0) { $parts += "unstaged=$($unstaged.Count)" }
    if ($untracked.Count -gt 0) { $parts += "untracked=$($untracked.Count)" }
    throw "Git working tree is not clean ($($parts -join ', ')). Commit or stash changes before release validation."
}
$branch = git branch --show-current
$head = git rev-parse HEAD
Write-Host "branch=$branch"
Write-Host "HEAD=$head"

$gradlew = Join-Path $RepoRoot 'gradlew.bat'
if (-not (Test-Path $gradlew)) {
    throw "Missing $gradlew"
}

Write-Step 'Gradle acceptance suite'
$gradleTasks = @(
    'testDebugUnitTest',
    'assembleRelease',
    'assembleDebug',
    'verifyReleaseModuleContents',
    'verifyDebugModuleContents',
    'lintRelease'
)
$gradleTasks = @('clean') + $gradleTasks

& $gradlew @gradleTasks --stacktrace
if ($LASTEXITCODE -ne 0) {
    throw "Gradle failed with exit code $LASTEXITCODE"
}

Write-Step 'Artifact discovery'
$releaseZip = Find-ExactlyOneZip -Pattern '*Release*.zip' -Label 'Release'
$debugZip = Find-ExactlyOneZip -Pattern '*Debug*.zip' -Label 'Debug'
Write-Host "Release ZIP: $($releaseZip.FullName)"
Write-Host "Debug ZIP:   $($debugZip.FullName)"

Write-Step 'SHA256 hashes'
$releaseSha = Get-FileSha256Hex $releaseZip.FullName
$debugSha = Get-FileSha256Hex $debugZip.FullName
Write-Host "Release ZIP SHA256: $releaseSha"
Write-Host "Debug ZIP SHA256:   $debugSha"

Write-Step 'Release classes.dex SHA256'
$dexSha = Get-ZipEntrySha256Hex -ZipPath $releaseZip.FullName -EntryName 'classes.dex'
Write-Host "classes.dex SHA256: $dexSha"

Write-Step 'Packaged module.prop (Release)'
$moduleProp = Read-ZipTextEntry -ZipPath $releaseZip.FullName -EntryName 'module.prop'
Write-Host $moduleProp
if ($moduleProp -match 'REPLACEMEVER') {
    throw 'Release module.prop still contains REPLACEMEVER placeholder'
}

Write-Step 'BUILD_ID (generated TeeBuildInfo)'
$teeBuildInfo = Join-Path $RepoRoot 'app/build/generated/source/teeBuildInfo/kotlin/io/github/beakthoven/TrickyStoreOSS/tee/TeeBuildInfo.kt'
if (Test-Path $teeBuildInfo) {
    $buildIdLine = Select-String -Path $teeBuildInfo -Pattern 'const val BUILD_ID' | Select-Object -First 1
    if ($buildIdLine) {
        Write-Host $buildIdLine.Line.Trim()
    }
    $gitLine = Select-String -Path $teeBuildInfo -Pattern 'const val GIT' | Select-Object -First 1
    if ($gitLine) {
        Write-Host $gitLine.Line.Trim()
    }
}
else {
    Write-Warning "TeeBuildInfo.kt not found at $teeBuildInfo"
}

Write-Step 'Release validation PASSED'
Write-Host "branch=$branch HEAD=$head"
Write-Host "release=$($releaseZip.Name)"
Write-Host "debug=$($debugZip.Name)"
