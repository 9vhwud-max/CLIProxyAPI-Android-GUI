param([string]$Tag = "")
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Out = Join-Path $Root "android-app\app\src\main\assets\cpa\management.html"
$headers = @{ "User-Agent" = "CLIProxyAPI-Android-GUI" }
if ($Tag) { $api = "https://api.github.com/repos/router-for-me/Cli-Proxy-API-Management-Center/releases/tags/$Tag" }
else { $api = "https://api.github.com/repos/router-for-me/Cli-Proxy-API-Management-Center/releases/latest" }
$release = Invoke-RestMethod -Headers $headers -Uri $api
$asset = $release.assets | Where-Object { $_.name -eq "management.html" } | Select-Object -First 1
if (-not $asset) { throw "management.html asset was not found in release $($release.tag_name)" }
Invoke-WebRequest -Headers $headers -Uri $asset.browser_download_url -OutFile $Out
$hash = (Get-FileHash $Out -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host "Bundled Management Center $($release.tag_name), SHA-256 $hash"
