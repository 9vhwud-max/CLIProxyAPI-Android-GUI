param(
  [string]$SdkRoot = $env:ANDROID_SDK_ROOT,
  [string]$NdkVersion = "27.2.12479018",
  [string[]]$Abis = @("arm64-v8a", "x86_64")
)
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Core = Join-Path $Root "CLIProxyAPI"
$Jni = Join-Path $Root "android-app\app\src\main\jniLibs"

if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_HOME }
if (-not $SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
if (-not (Test-Path $SdkRoot)) { throw "Android SDK not found. Set ANDROID_SDK_ROOT." }

$Ndk = Join-Path $SdkRoot "ndk\$NdkVersion"
if (-not (Test-Path $Ndk)) {
  $ndkRoot = Join-Path $SdkRoot "ndk"
  $candidate = Get-ChildItem $ndkRoot -Directory -ErrorAction SilentlyContinue |
    ForEach-Object {
      $rev = $null
      if ($_.Name -match '^(\d+)\.(\d+)\.(\d+)') {
        $rev = [version]("{0}.{1}.{2}" -f $Matches[1], $Matches[2], $Matches[3])
      }
      [pscustomobject]@{ Dir = $_; Revision = $rev }
    } |
    Where-Object { $_.Revision -ne $null } |
    Sort-Object Revision -Descending |
    Select-Object -First 1
  if (-not $candidate) { throw "Android NDK not found. Install NDK $NdkVersion (r27c) or a newer NDK in Android Studio SDK Manager." }
  $Ndk = $candidate.Dir.FullName
  Write-Warning "NDK $NdkVersion not found; using $Ndk"
}

$go = Get-Command go -ErrorAction Stop
$goVersion = & $go.Source version
Write-Host $goVersion
if ($goVersion -notmatch 'go1\.(2[6-9]|[3-9][0-9])') { throw "CLIProxyAPI in this bundle requires Go 1.26+." }

$hostTag = "windows-x86_64"
$toolchain = Join-Path $Ndk "toolchains\llvm\prebuilt\$hostTag\bin"
if (-not (Test-Path $toolchain)) { throw "NDK LLVM toolchain not found at $toolchain" }

# Android NDK r28+ emits 16 KiB-aligned ELF by default. r27 and older
# need explicit lld page-size flags.
$ndkMajor = 0
$sourceProps = Join-Path $Ndk "source.properties"
if (Test-Path $sourceProps) {
  $pkgRevision = Select-String -Path $sourceProps -Pattern '^Pkg\.Revision\s*=\s*(\d+)' | Select-Object -First 1
  if ($pkgRevision -and $pkgRevision.Matches.Count -gt 0) {
    $ndkMajor = [int]$pkgRevision.Matches[0].Groups[1].Value
  }
}
if ($ndkMajor -eq 0 -and (Split-Path $Ndk -Leaf) -match '^(\d+)') {
  $ndkMajor = [int]$Matches[1]
}
Write-Host "Using Android NDK r$ndkMajor at $Ndk"

$version = "android-gui"
$commit = "bundled-source"
$buildDate = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$ldflagParts = @(
  "-s",
  "-w",
  "-buildid=",
  "-checklinkname=0",
  "-linkmode=external"
)
if ($ndkMajor -gt 0 -and $ndkMajor -le 27) {
  $ldflagParts += "-extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384"
}
$ldflagParts += @(
  "-X", "main.Version=$version",
  "-X", "main.Commit=$commit",
  "-X", "main.BuildDate=$buildDate"
)
$ldflags = $ldflagParts -join " "

Push-Location $Core
try {
  foreach ($abi in $Abis) {
    switch ($abi) {
      "arm64-v8a" { $goarch="arm64"; $cc="aarch64-linux-android26-clang.cmd" }
      "x86_64" { $goarch="amd64"; $cc="x86_64-linux-android26-clang.cmd" }
      default { throw "Unsupported ABI: $abi" }
    }
    $ccPath = Join-Path $toolchain $cc
    if (-not (Test-Path $ccPath)) { throw "Compiler not found: $ccPath" }
    $outDir = Join-Path $Jni $abi
    New-Item -ItemType Directory -Force $outDir | Out-Null
    $out = Join-Path $outDir "libcliproxyapi.so"
    Remove-Item $out -Force -ErrorAction SilentlyContinue
    Write-Host "Building CLIProxyAPI for $abi -> $out"
    $env:CGO_ENABLED="1"
    $env:GOOS="android"
    $env:GOARCH=$goarch
    $env:CC=$ccPath

    # IMPORTANT: pass each native-process argument as an array element. In
    # Windows PowerShell, `-ldflags=$ldflags` can be forwarded literally as
    # "$ldflags", which makes `go build` parse it as a malformed linker flag.
    $goArgs = @(
      "build",
      "-buildvcs=false",
      "-trimpath",
      "-ldflags", $ldflags,
      "-o", $out,
      "./cmd/server/"
    )
    & $go.Source @goArgs
    if ($LASTEXITCODE -ne 0) { throw "go build failed for $abi" }
  }
} finally {
  Pop-Location
}
Write-Host "Native core build complete."
