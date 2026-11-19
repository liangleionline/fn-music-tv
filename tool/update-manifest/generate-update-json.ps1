#Requires -Version 7.0
<#
.SYNOPSIS
    生成应用内更新所需的 update.json（GitHub Release 资产，schemaVersion = 1）。

.DESCRIPTION
    客户端「检查更新」只读本仓库的 releases/latest：
      1. GitHub API 取最新 Release（draft / prerelease 自动排除）；
      2. 从资产里找 update.json，读取版本号、APK 直链、大小与 SHA256；
      3. 展示文案使用 Release 的 name 与 body。
    因此每次发布都必须在 Release 里同时上传 APK、APK 的 .sha256 与本脚本生成的 update.json。

.PARAMETER ApkPath
    已签名的发布 APK 路径（sideloadRelease 产物）。

.PARAMETER ReleaseTag
    Release 的 tag（例如 v1.2.15），必须与 version.properties 的 VERSION_NAME 一致。

.PARAMETER OutputPath
    update.json 输出路径。

.PARAMETER Repository
    owner/repo，默认 liangleionline/fn-music-tv。

.PARAMETER Notes
    弹窗里 fallback 的更新说明；留空时客户端直接用 Release body。

.PARAMETER PublishedAt
    ISO8601 UTC 时间；留空取当前时间。
#>
param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$ReleaseTag,
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [string]$Repository = 'liangleionline/fn-music-tv',
    [string]$Notes = '使用 GitHub Release 直链更新',
    [string]$PublishedAt = ''
)

$ErrorActionPreference = 'Stop'

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '../..')
$versionFile = Join-Path $repoRoot 'version.properties'
if (-not (Test-Path -LiteralPath $versionFile)) { throw "缺少 version.properties: $versionFile" }

$versionProps = @{}
Get-Content -LiteralPath $versionFile | Where-Object { $_ -match '=' } | ForEach-Object {
    $key, $value = $_ -split '=', 2
    $versionProps[$key.Trim()] = $value.Trim()
}
$versionCode = [long]($versionProps['VERSION_CODE'])
$versionName = $versionProps['VERSION_NAME']
if ($versionCode -le 0) { throw 'version.properties 中的 VERSION_CODE 无效' }
if ($versionName -notmatch '^[0-9]+.[0-9]+.[0-9]+$') { throw "version.properties 中的 VERSION_NAME 无效: $versionName" }

$normalizedTag = $ReleaseTag.Trim()
if ($normalizedTag -notmatch '^v[0-9]+.[0-9]+.[0-9]+$') { throw "ReleaseTag 必须形如 v1.2.15，实际: $ReleaseTag" }
if ($normalizedTag.Substring(1) -ne $versionName) {
    throw "ReleaseTag($normalizedTag) 与 version.properties($versionName) 不一致"
}

$apk = Resolve-Path -LiteralPath $ApkPath
$apkName = Split-Path -Leaf $apk
$apkSize = (Get-Item -LiteralPath $apk).Length
$sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($sha256 -notmatch '^[0-9a-f]{64}$') { throw 'SHA256 计算失败' }

if ([string]::IsNullOrWhiteSpace($PublishedAt)) {
    $PublishedAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
}
if ($PublishedAt -notmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$') {
    throw "PublishedAt 必须是 ISO8601（例如 2026-11-19T12:00:00Z）: $PublishedAt"
}

$apkUrl = "https://github.com/$Repository/releases/download/$normalizedTag/$apkName"
$releaseUrl = "https://github.com/$Repository/releases/tag/$normalizedTag"

$manifest = [ordered]@{
    schemaVersion    = 1
    packageName      = 'com.fnmusic.tv'
    versionName      = $versionName
    versionCode      = $versionCode
    title            = "飞牛音乐 TV 增强版 $normalizedTag"
    notes            = $Notes
    apk              = [ordered]@{
        url    = $apkUrl
        size   = $apkSize
        sha256 = $sha256
    }
    publishedAt      = $PublishedAt
    githubReleaseUrl = $releaseUrl
}

$json = $manifest | ConvertTo-Json -Depth 5
# 客户端按 UTF-8 直接解码，必须写无 BOM 的 UTF-8
$outputFile = if ([IO.Path]::IsPathRooted($OutputPath)) { $OutputPath } else { Join-Path (Get-Location).Path $OutputPath }
[IO.File]::WriteAllText($outputFile, $json, (New-Object Text.UTF8Encoding($false)))

$written = Get-Content -Raw -LiteralPath $outputFile
if (-not (Test-Json -Json $written)) { throw 'update.json 不是合法 JSON' }
$firstBytes = [IO.File]::ReadAllBytes($outputFile)[0..2]
if ($firstBytes[0] -eq 0xEF -and $firstBytes[1] -eq 0xBB -and $firstBytes[2] -eq 0xBF) {
    throw 'update.json 含有 BOM，客户端会解析失败'
}
$parsed = $written | ConvertFrom-Json
if ($parsed.apk.size -ne $apkSize) { throw 'update.json 的 apk.size 与文件不一致' }
if ($parsed.apk.sha256 -notmatch '^[0-9a-f]{64}$') { throw 'update.json 的 sha256 字段无效' }
if ($parsed.versionCode -ne $versionCode) { throw 'update.json 的 versionCode 与 version.properties 不一致' }

Write-Output "已生成 $OutputPath"
Write-Output "  versionName = $versionName / versionCode = $versionCode"
Write-Output "  apk         = $apkName ($apkSize B)"
Write-Output "  sha256      = $sha256"
Write-Output "  apk url     = $apkUrl"
