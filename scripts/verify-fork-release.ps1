#!/usr/bin/env pwsh
#Requires -Version 7.0
<#
.SYNOPSIS
  Read-only publication gate verifier for xStoikk fork GitHub Releases.

.DESCRIPTION
  Validates release assets, manifest, attestation, protected-main update feed,
  changelog, release body, and tag state. Does not mutate git, GitHub, or
  tracked repository files.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Tag,

    [Parameter(Mandatory)]
    [ValidateSet('Draft', 'Published')]
    [string]$State,

    [string]$Repository = 'xStoikk/TrickyStoreOSS',

    [string]$MainRef = 'origin/main'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $RepoRoot

$ForkUpdateJsonUrl = 'https://raw.githubusercontent.com/xStoikk/TrickyStoreOSS/main/update.json'
$ForkModuleAuthor = 'xStoikk (fork; upstream by beakthoven)'
$VersionCodeEpoch = 100000
$RequiredAssetNames = @('release-manifest.json', 'release-summary.md', 'update.json.next')
$RequiredUpdateFields = @('version', 'versionCode', 'zipUrl', 'changelog')

function Fail-Gate([string]$Message) {
    throw "PHASE 7H RELEASE GATE: FAIL — $Message"
}

function Assert-CommandAvailable([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        Fail-Gate "Required command not found: $Name"
    }
}

function Get-FileSha256Hex([string]$Path) {
    return (Get-FileHash -Path $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Read-ZipTextEntry([string]$ZipPath, [string]$EntryName) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $entry = $zip.GetEntry($EntryName)
        if ($null -eq $entry) {
            Fail-Gate "Entry '$EntryName' not found in $ZipPath"
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

function Get-ZipEntrySha256Hex([string]$ZipPath, [string]$EntryName) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    $temp = $null
    try {
        $entry = $zip.GetEntry($EntryName)
        if ($null -eq $entry) {
            Fail-Gate "Entry '$EntryName' not found in $ZipPath"
        }
        $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("ts-verify-" + [Guid]::NewGuid().ToString('N'))
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

function Get-JsonSemanticMap([object]$JsonObject, [string]$Label) {
    if ($null -eq $JsonObject) {
        Fail-Gate "$Label is null"
    }
    $props = @($JsonObject.PSObject.Properties.Name)
    foreach ($field in $RequiredUpdateFields) {
        if ($field -notin $props) {
            Fail-Gate "$Label missing required field: $field"
        }
    }
    foreach ($prop in $props) {
        if ($prop -notin $RequiredUpdateFields) {
            Fail-Gate "$Label has unexpected field: $prop"
        }
    }
    return [ordered]@{
        version     = [string]$JsonObject.version
        versionCode = [int]$JsonObject.versionCode
        zipUrl      = [string]$JsonObject.zipUrl
        changelog   = [string]$JsonObject.changelog
    }
}

function Test-PublicationGatePhrases([string]$Text, [string]$Label) {
    if ([string]::IsNullOrWhiteSpace($Text)) {
        Fail-Gate "$Label is empty"
    }
    if ($Text -match '(?i)Do not publish') {
        Fail-Gate "$Label contains publication-gate wording: Do not publish"
    }
    if ($Text -match '(?i)not published') {
        Fail-Gate "$Label contains publication-gate wording: not published"
    }
    if ($Text -match '(?i)update feed has not yet been committed') {
        Fail-Gate "$Label contains publication-gate wording: update feed has not yet been committed"
    }
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -match '^\s*DRAFT\b') {
            Fail-Gate "$Label contains draft banner line: $line"
        }
    }
}

Assert-CommandAvailable 'git'
Assert-CommandAvailable 'gh'

Write-Host 'Checking GitHub authentication...'
gh auth status --hostname github.com 2>&1 | Out-Host
if ($LASTEXITCODE -ne 0) {
    Fail-Gate 'GitHub CLI is not authenticated for github.com'
}

Write-Host "Fetching origin main and tags..."
& git fetch origin main --tags
if ($LASTEXITCODE -ne 0) {
    Fail-Gate 'git fetch origin main --tags failed'
}

$mainSha = (git rev-parse $MainRef).Trim()
if ($LASTEXITCODE -ne 0) {
    Fail-Gate "Could not resolve MainRef '$MainRef'"
}

Write-Host "Resolving release $Tag from $Repository..."
$releaseJson = gh release view $Tag --repo $Repository --json tagName,name,isDraft,isPrerelease,targetCommitish,publishedAt,body,assets,url
if ($LASTEXITCODE -ne 0) {
    Fail-Gate "Could not resolve GitHub Release for tag '$Tag'"
}
$release = $releaseJson | ConvertFrom-Json

if ($release.tagName -ne $Tag) {
    Fail-Gate "Release tagName '$($release.tagName)' != requested Tag '$Tag'"
}
if ($release.name -ne $Tag) {
    Fail-Gate "Release name '$($release.name)' != requested Tag '$Tag'"
}

$publishedAt = [string]$release.publishedAt
switch ($State) {
    'Draft' {
        if (-not $release.isDraft) {
            Fail-Gate "State=Draft requires isDraft=true (actual isDraft=$($release.isDraft))"
        }
        if (-not [string]::IsNullOrWhiteSpace($publishedAt)) {
            Fail-Gate "State=Draft requires publishedAt to be absent (actual publishedAt=$publishedAt)"
        }
    }
    'Published' {
        if ($release.isDraft) {
            Fail-Gate "State=Published requires isDraft=false"
        }
        if ([string]::IsNullOrWhiteSpace($publishedAt)) {
            Fail-Gate 'State=Published requires publishedAt to be non-empty'
        }
    }
}

$assets = @($release.assets)
if ($assets.Count -ne 4) {
    $names = ($assets | ForEach-Object { $_.name }) -join ', '
    Fail-Gate "Expected exactly 4 release assets; found $($assets.Count): $names"
}

$releaseZipAssets = @($assets | Where-Object { $_.name -like '*-Release.zip' })
if ($releaseZipAssets.Count -ne 1) {
    $names = ($releaseZipAssets | ForEach-Object { $_.name }) -join ', '
    Fail-Gate "Expected exactly 1 Release ZIP asset; found $($releaseZipAssets.Count): $names"
}
if ($assets | Where-Object { $_.name -like '*-Debug.zip' }) {
    Fail-Gate 'Release must not include Debug ZIP assets'
}
if ($assets | Where-Object { $_.name -eq 'release-notes.md' }) {
    Fail-Gate 'release-notes.md must not be attached as a release asset'
}

foreach ($requiredName in $RequiredAssetNames) {
    $matches = @($assets | Where-Object { $_.name -eq $requiredName })
    if ($matches.Count -ne 1) {
        Fail-Gate "Expected exactly 1 asset named '$requiredName'"
    }
}

$unexpected = @($assets | Where-Object {
        $_.name -notin $RequiredAssetNames -and $_.name -ne $releaseZipAssets[0].name
    })
if ($unexpected.Count -gt 0) {
    $names = ($unexpected | ForEach-Object { $_.name }) -join ', '
    Fail-Gate "Unexpected release assets: $names"
}

$releaseZipAsset = $releaseZipAssets[0]
$releaseZipName = $releaseZipAsset.name
$releaseZipDigest = [string]$releaseZipAsset.digest

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("ts-fork-release-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

try {
    Write-Host "Downloading release assets to $tempDir..."
    gh release download $Tag --repo $Repository --dir $tempDir
    if ($LASTEXITCODE -ne 0) {
        Fail-Gate "Failed to download release assets for tag '$Tag'"
    }

    $manifestPath = Join-Path $tempDir 'release-manifest.json'
    $summaryPath = Join-Path $tempDir 'release-summary.md'
    $updateNextPath = Join-Path $tempDir 'update.json.next'
    $releaseZipPath = Join-Path $tempDir $releaseZipName

    foreach ($path in @($manifestPath, $summaryPath, $updateNextPath, $releaseZipPath)) {
        if (-not (Test-Path $path)) {
            Fail-Gate "Downloaded asset missing: $path"
        }
    }

    $manifest = Get-Content -Path $manifestPath -Raw | ConvertFrom-Json

    if ([int]$manifest.schemaVersion -ne 2) {
        Fail-Gate "manifest.schemaVersion must be 2 (actual $($manifest.schemaVersion))"
    }
    if ([string]$manifest.repository -ne $Repository) {
        Fail-Gate "manifest.repository '$($manifest.repository)' != '$Repository'"
    }
    if ([string]$manifest.releaseTag -ne $Tag) {
        Fail-Gate "manifest.releaseTag '$($manifest.releaseTag)' != '$Tag'"
    }
    if ([string]$manifest.productVersion -ne $Tag) {
        Fail-Gate "manifest.productVersion '$($manifest.productVersion)' != '$Tag'"
    }
    if ([string]$manifest.versionCodeStrategy -ne 'epoch+commitCount') {
        Fail-Gate "manifest.versionCodeStrategy must be epoch+commitCount"
    }
    if ([int]$manifest.versionCodeEpoch -ne $VersionCodeEpoch) {
        Fail-Gate "manifest.versionCodeEpoch must be $VersionCodeEpoch"
    }
    if ([string]$manifest.validation -ne 'validate-release.ps1 PASS') {
        Fail-Gate "manifest.validation must be 'validate-release.ps1 PASS'"
    }

    foreach ($field in @(
            'commit', 'shortCommit', 'commitCount', 'versionCode', 'releaseZip',
            'releaseZipSha256', 'classesDexSha256', 'teeBuildPhase', 'packagedUpdateJson'
        )) {
        $value = [string]$manifest.$field
        if ([string]::IsNullOrWhiteSpace($value)) {
            Fail-Gate "manifest.$field is empty"
        }
    }

    $manifestCommit = [string]$manifest.commit
    $manifestShortCommit = [string]$manifest.shortCommit
    $manifestCommitCount = [int]$manifest.commitCount
    $manifestVersionCode = [int]$manifest.versionCode
    $manifestReleaseZip = [string]$manifest.releaseZip
    $manifestReleaseZipSha256 = [string]$manifest.releaseZipSha256
    $manifestDexSha256 = [string]$manifest.classesDexSha256
    $manifestProductVersion = [string]$manifest.productVersion
    $manifestTeePhase = [string]$manifest.teeBuildPhase
    $manifestPackagedUpdateJson = [string]$manifest.packagedUpdateJson

    if ($manifestReleaseZip -ne $releaseZipName) {
        Fail-Gate "manifest.releaseZip '$manifestReleaseZip' != Release ZIP asset '$releaseZipName'"
    }
    if ($manifestPackagedUpdateJson -ne $ForkUpdateJsonUrl) {
        Fail-Gate "manifest.packagedUpdateJson mismatch"
    }
    if ($manifestShortCommit -ne $manifestCommit.Substring(0, [Math]::Min(7, $manifestCommit.Length))) {
        Fail-Gate "manifest.shortCommit '$manifestShortCommit' != first 7 chars of manifest.commit"
    }
    if ($manifestVersionCode -ne ($VersionCodeEpoch + $manifestCommitCount)) {
        Fail-Gate "manifest.versionCode ($manifestVersionCode) != $VersionCodeEpoch + commitCount ($manifestCommitCount)"
    }

    if ([string]$release.targetCommitish -ne $manifestCommit) {
        Fail-Gate "release.targetCommitish '$($release.targetCommitish)' != manifest.commit '$manifestCommit'"
    }

    git cat-file -e "$manifestCommit^{commit}" 2>$null
    if ($LASTEXITCODE -ne 0) {
        Fail-Gate "manifest.commit '$manifestCommit' is not available locally after fetch"
    }

    git merge-base --is-ancestor $manifestCommit $mainSha
    if ($LASTEXITCODE -ne 0) {
        Fail-Gate "manifest.commit '$manifestCommit' is not an ancestor of protected main '$mainSha'"
    }

    $downloadedZipSha256 = Get-FileSha256Hex $releaseZipPath
    if ($downloadedZipSha256 -ne $manifestReleaseZipSha256) {
        Fail-Gate "Downloaded Release ZIP SHA256 mismatch (expected $manifestReleaseZipSha256, actual $downloadedZipSha256)"
    }
    if (-not [string]::IsNullOrWhiteSpace($releaseZipDigest)) {
        $expectedDigest = "sha256:$manifestReleaseZipSha256"
        if ($releaseZipDigest -ne $expectedDigest) {
            Fail-Gate "Release ZIP asset digest '$releaseZipDigest' != '$expectedDigest'"
        }
    }

    $downloadedDexSha256 = Get-ZipEntrySha256Hex -ZipPath $releaseZipPath -EntryName 'classes.dex'
    if ($downloadedDexSha256 -ne $manifestDexSha256) {
        Fail-Gate "classes.dex SHA256 mismatch (expected $manifestDexSha256, actual $downloadedDexSha256)"
    }

    $modulePropText = Read-ZipTextEntry -ZipPath $releaseZipPath -EntryName 'module.prop'
    $moduleProp = Parse-ModuleProp $modulePropText

    if ($moduleProp['id'] -ne 'tricky_store') {
        Fail-Gate "module.prop id must be tricky_store"
    }
    if ($moduleProp['name'] -ne 'Tricky Store OSS') {
        Fail-Gate "module.prop name must be 'Tricky Store OSS'"
    }
    if ([int]$moduleProp['versionCode'] -ne $manifestVersionCode) {
        Fail-Gate "module.prop versionCode '$($moduleProp['versionCode'])' != manifest.versionCode '$manifestVersionCode'"
    }
    if ($moduleProp['author'] -ne $ForkModuleAuthor) {
        Fail-Gate "module.prop author mismatch"
    }
    if ($moduleProp['updateJson'] -ne $ForkUpdateJsonUrl) {
        Fail-Gate "module.prop updateJson mismatch"
    }
    if ($moduleProp['version'] -notmatch [regex]::Escape($manifestProductVersion)) {
        Fail-Gate "module.prop version must contain productVersion '$manifestProductVersion'"
    }
    if ($moduleProp['version'] -notmatch [regex]::Escape($manifestShortCommit)) {
        Fail-Gate "module.prop version must contain shortCommit '$manifestShortCommit'"
    }
    if ($moduleProp['version'] -notmatch '(?i)release') {
        Fail-Gate "module.prop version must reference release variant"
    }

    Write-Host 'Verifying Release ZIP attestation...'
    $attestOutput = gh attestation verify $releaseZipPath `
        -R $Repository `
        --source-digest $manifestCommit `
        --source-ref refs/heads/main `
        --signer-workflow "$Repository/.github/workflows/release-fork.yml" 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        Fail-Gate "gh attestation verify failed:`n$attestOutput"
    }
    $attestationResult = $attestOutput.Trim()
    if ([string]::IsNullOrWhiteSpace($attestationResult)) {
        $attestationResult = 'PASS'
    }

    $updateNextJson = Get-Content -Path $updateNextPath -Raw | ConvertFrom-Json
    $updateNextMap = Get-JsonSemanticMap -JsonObject $updateNextJson -Label 'update.json.next'

    $mainUpdateText = (& git show "${MainRef}:update.json" | Out-String).TrimEnd()
    if ($LASTEXITCODE -ne 0) {
        Fail-Gate "Could not read update.json from $MainRef"
    }
    $mainUpdateJson = $mainUpdateText | ConvertFrom-Json
    $mainUpdateMap = Get-JsonSemanticMap -JsonObject $mainUpdateJson -Label "protected-main update.json ($MainRef)"

    foreach ($field in $RequiredUpdateFields) {
        if ($updateNextMap[$field] -ne $mainUpdateMap[$field]) {
            Fail-Gate "update.json.next.$field != protected-main update.json.$field"
        }
    }
    $feedMatch = 'PASS'

    $expectedZipUrl = "https://github.com/$Repository/releases/download/$Tag/$manifestReleaseZip"
    $expectedChangelog = "https://raw.githubusercontent.com/$Repository/main/changelog.md"
    if ($updateNextMap.version -ne $manifestProductVersion) {
        Fail-Gate 'update.json.next.version != manifest.productVersion'
    }
    if ($updateNextMap.versionCode -ne $manifestVersionCode) {
        Fail-Gate 'update.json.next.versionCode != manifest.versionCode'
    }
    if ($updateNextMap.zipUrl -ne $expectedZipUrl) {
        Fail-Gate "update.json.next.zipUrl != expected '$expectedZipUrl'"
    }
    if ($updateNextMap.changelog -ne $expectedChangelog) {
        Fail-Gate "update.json.next.changelog != expected '$expectedChangelog'"
    }

    $mainChangelog = (& git show "${MainRef}:changelog.md" | Out-String).TrimEnd()
    if ($LASTEXITCODE -ne 0) {
        Fail-Gate "Could not read changelog.md from $MainRef"
    }
    if ([string]::IsNullOrWhiteSpace($mainChangelog)) {
        Fail-Gate "protected-main changelog.md from $MainRef is empty"
    }
    foreach ($requiredValue in @(
            $manifestProductVersion,
            $manifestCommit,
            $manifestReleaseZip,
            $manifestReleaseZipSha256,
            $manifestDexSha256
        )) {
        if ($mainChangelog -notmatch [regex]::Escape($requiredValue)) {
            Fail-Gate "protected-main changelog.md missing required value: $requiredValue"
        }
    }
    $changelogMatch = 'PASS'

    $releaseBody = [string]$release.body
    Test-PublicationGatePhrases -Text $releaseBody -Label 'GitHub Release body'
    foreach ($requiredValue in @(
            $manifestProductVersion,
            [string]$manifestVersionCode,
            $manifestCommit,
            $manifestReleaseZip,
            $manifestReleaseZipSha256,
            $manifestDexSha256
        )) {
        if ($releaseBody -notmatch [regex]::Escape($requiredValue)) {
            Fail-Gate "GitHub Release body missing required value: $requiredValue"
        }
    }
    $releaseBodyMatch = 'PASS'

    $summaryText = Get-Content -Path $summaryPath -Raw
    if ([string]::IsNullOrWhiteSpace($summaryText)) {
        Fail-Gate 'release-summary.md is empty'
    }
    foreach ($requiredValue in @(
            $manifestCommit,
            $manifestReleaseZip,
            $manifestReleaseZipSha256,
            [string]$manifestVersionCode
        )) {
        if ($summaryText -notmatch [regex]::Escape($requiredValue)) {
            Fail-Gate "release-summary.md missing required identity value: $requiredValue"
        }
    }

    Write-Host "Checking remote tag state for State=$State..."
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $lsRemoteOutput = git ls-remote --exit-code --tags origin "refs/tags/$Tag" 2>&1
    $tagLookupRc = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorAction

    $tagState = ''
    switch ($State) {
        'Draft' {
            if ($tagLookupRc -eq 0) {
                Fail-Gate "State=Draft requires remote tag refs/tags/$Tag to be absent"
            }
            if ($tagLookupRc -ne 2) {
                Fail-Gate "Remote tag lookup failed for Draft state (exit code $tagLookupRc): $lsRemoteOutput"
            }
            $tagState = 'absent (Draft expected)'
        }
        'Published' {
            if ($tagLookupRc -ne 0) {
                Fail-Gate "State=Published requires remote tag refs/tags/$Tag to exist (exit code $tagLookupRc): $lsRemoteOutput"
            }
            $tagCommit = (git rev-parse "refs/tags/$Tag^{commit}").Trim()
            if ($LASTEXITCODE -ne 0) {
                Fail-Gate "Could not resolve local tag refs/tags/$Tag"
            }
            if ($tagCommit -ne $manifestCommit) {
                Fail-Gate "Resolved tag commit '$tagCommit' != manifest.commit '$manifestCommit'"
            }
            $tagState = "present -> $tagCommit"
        }
    }

    Write-Host ''
    Write-Host '=== Fork release publication gate audit ==='
    Write-Host "Repository: $Repository"
    Write-Host "State: $State"
    Write-Host "Tag: $Tag"
    Write-Host "Release URL: $($release.url)"
    Write-Host "Prerelease: $($release.isPrerelease)"
    Write-Host "MainRef: $MainRef"
    Write-Host "Main SHA: $mainSha"
    Write-Host "Release source SHA: $manifestCommit"
    Write-Host "Commit count: $manifestCommitCount"
    Write-Host "VersionCode: $manifestVersionCode"
    Write-Host "Release ZIP: $manifestReleaseZip"
    Write-Host "Release ZIP SHA256: $manifestReleaseZipSha256"
    Write-Host "classes.dex SHA256: $manifestDexSha256"
    Write-Host "TEE phase: $manifestTeePhase"
    Write-Host "Attestation: $attestationResult"
    Write-Host "Feed match: $feedMatch"
    Write-Host "Changelog match: $changelogMatch"
    Write-Host "Release-body match: $releaseBodyMatch"
    Write-Host "Tag state: $tagState"
    Write-Host ''
    Write-Host 'PHASE 7H RELEASE GATE: PASS'
}
finally {
    if (Test-Path $tempDir) {
        Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
