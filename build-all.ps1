# Build CLIProxyAPI Android APK. Uses Android Studio's bundled JBR when Java
# is not available on PATH, and refuses to publish a failed Gradle build.
[CmdletBinding()]
param(
  [switch]$SkipWebUi,
  [switch]$Arm64Only,
  [Alias('SkipNative')][switch]$SkipGo,
  [string]$SdkRoot,
  [string]$JdkRoot,
  [switch]$NoGradleCrashRetry
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot

function Find-AndroidSdk {
  param([string]$RequestedSdkRoot)

  $candidates = New-Object 'System.Collections.Generic.List[string]'
  if (-not [string]::IsNullOrWhiteSpace($RequestedSdkRoot)) { $candidates.Add($RequestedSdkRoot) }

  foreach ($name in @('ANDROID_SDK_ROOT', 'ANDROID_HOME')) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if (-not [string]::IsNullOrWhiteSpace($value)) { $candidates.Add($value) }
  }

  if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
    $candidates.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
  }
  if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
    $candidates.Add((Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk'))
  }

  foreach ($raw in $candidates) {
    $sdk = [Environment]::ExpandEnvironmentVariables($raw.Trim().Trim('"'))
    if ((Test-Path $sdk -PathType Container) -and
        ((Test-Path (Join-Path $sdk 'platforms') -PathType Container) -or
         (Test-Path (Join-Path $sdk 'platform-tools') -PathType Container) -or
         (Test-Path (Join-Path $sdk 'ndk') -PathType Container))) {
      return (Resolve-Path $sdk).Path
    }
  }
  return $null
}

function Configure-AndroidSdk {
  param([string]$SdkRoot)

  $sdk = Find-AndroidSdk -RequestedSdkRoot $SdkRoot
  if (-not $sdk) {
    throw @'
Android SDK not found.
Install/open Android Studio once, or pass the SDK explicitly:
  .\build-all.ps1 -Arm64Only -SdkRoot "C:\Users\YOU\AppData\Local\Android\Sdk"
The default Android Studio SDK location on Windows is usually:
  %LOCALAPPDATA%\Android\Sdk
'@
  }

  $env:ANDROID_HOME = $sdk
  $env:ANDROID_SDK_ROOT = $sdk

  # Gradle/AGP also accepts android-app/local.properties. Generate it so the
  # project keeps working even when ANDROID_HOME is not globally configured.
  $localProperties = Join-Path $Root 'android-app\local.properties'
  $sdkForProperties = $sdk -replace '\\', '/'
  Set-Content -LiteralPath $localProperties -Value ("sdk.dir=" + $sdkForProperties) -Encoding ASCII

  Write-Host "Using Android SDK: $sdk"
  Write-Host "Wrote: $localProperties"

  # Android 17 / API 37 introduced minor platform package naming. With
  # AGP 9.1.1 this project supports API 37.0, whose SDK directory is normally
  # "platforms\android-37.0". Some SDK layouts may still expose "android-37",
  # so accept both names. Do NOT silently accept 37.1+ here: AGP 9.1.1 is
  # documented to support API 37.0 and lower.
  $platformsDir = Join-Path $sdk 'platforms'
  $platform37Candidates = @(
    (Join-Path $platformsDir 'android-37.0\android.jar'),
    (Join-Path $platformsDir 'android-37\android.jar')
  )

  $requiredPlatform = $null
  foreach ($candidate in $platform37Candidates) {
    if (Test-Path $candidate -PathType Leaf) {
      $requiredPlatform = $candidate
      break
    }
  }

  if (-not $requiredPlatform) {
    $installed37 = @(
      Get-ChildItem -LiteralPath $platformsDir -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'android-37*' } |
        ForEach-Object { $_.Name }
    )
    $installedText = if ($installed37.Count -gt 0) { $installed37 -join ', ' } else { '(none)' }

    throw @"
Android SDK Platform 37.0 was not found, but this project uses compileSdk 37
with Android Gradle Plugin 9.1.1 (which supports API 37.0).

Open Android Studio > SDK Manager and install Android 17.0 / API 37.0,
or run (if Command-line Tools are installed):
  & "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-37.0"

Accepted android.jar locations:
  $sdk\platforms\android-37.0\android.jar
  $sdk\platforms\android-37\android.jar

Detected API-37 platform directories: $installedText
"@
  }

  $platformDirName = Split-Path (Split-Path $requiredPlatform -Parent) -Leaf
  Write-Host "Using Android platform: $platformDirName"
  Write-Host "Minimum Android runtime: API 26 / Android 8.0 (required by GeckoView 156)"

  return $sdk
}


function Find-JdkHome {
  param([string]$RequestedJdkRoot)

  $candidates = New-Object 'System.Collections.Generic.List[string]'

  if (-not [string]::IsNullOrWhiteSpace($RequestedJdkRoot)) { $candidates.Add($RequestedJdkRoot) }

  # A configured JDK takes priority. GRADLE_JAVA_HOME is also accepted as a
  # project-specific convenience and normalized to JAVA_HOME below.
  foreach ($name in @('JAVA_HOME', 'GRADLE_JAVA_HOME', 'ANDROID_STUDIO_JDK', 'STUDIO_JDK')) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if (-not [string]::IsNullOrWhiteSpace($value)) { $candidates.Add($value) }
  }

  foreach ($name in @('ANDROID_STUDIO_HOME', 'STUDIO_HOME')) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if (-not [string]::IsNullOrWhiteSpace($value)) {
      $candidates.Add((Join-Path $value 'jbr'))
    }
  }

  # Standard Windows Android Studio locations (system/user, stable/preview).
  foreach ($base in @($env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:LOCALAPPDATA)) {
    if ([string]::IsNullOrWhiteSpace($base)) { continue }
    foreach ($relative in @(
      'Android\Android Studio\jbr',
      'Android\Android Studio Preview\jbr',
      'Programs\Android Studio\jbr',
      'Programs\Android Studio Preview\jbr'
    )) {
      $candidates.Add((Join-Path $base $relative))
    }
  }

  foreach ($raw in $candidates) {
    # PowerShell variable names are case-insensitive, so $home collides with
    # the built-in read-only $HOME automatic variable. Use a non-reserved name.
    $jdkCandidateHome = [Environment]::ExpandEnvironmentVariables($raw.Trim().Trim('"'))
    if ($jdkCandidateHome -match '(?i)[\\/]bin[\\/]java\.exe$') {
      $jdkCandidateHome = Split-Path (Split-Path $jdkCandidateHome -Parent) -Parent
    }
    if ((Test-Path (Join-Path $jdkCandidateHome 'bin\java.exe') -PathType Leaf) -and
        (Test-Path (Join-Path $jdkCandidateHome 'bin\javac.exe') -PathType Leaf)) {
      return $jdkCandidateHome
    }
  }

  # Finally accept a genuine JDK already available on PATH.
  $pathJava = Get-Command 'java.exe' -ErrorAction SilentlyContinue
  if ($pathJava -and $pathJava.Source) {
    $pathHome = Split-Path (Split-Path $pathJava.Source -Parent) -Parent
    if (Test-Path (Join-Path $pathHome 'bin\javac.exe') -PathType Leaf) {
      return $pathHome
    }
  }
  return $null
}

$jdkHome = Find-JdkHome -RequestedJdkRoot $JdkRoot
if (-not $jdkHome) {
  throw @'
Java JDK not found. Gradle requires JDK 17 or later.
Android Studio normally bundles one in its jbr folder. In PowerShell:
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  $env:Path = "$env:JAVA_HOME\bin;$env:Path"
  java -version
If Android Studio is installed elsewhere, use its actual jbr directory.
'@
}
$env:JAVA_HOME = $jdkHome
$env:Path = (Join-Path $jdkHome 'bin') + [IO.Path]::PathSeparator + $env:Path
Write-Host "Using JDK: $jdkHome"

$resolvedSdk = Configure-AndroidSdk -SdkRoot $SdkRoot

if (-not $SkipWebUi) {
  & (Join-Path $Root 'scripts\fetch-webui.ps1')
}
if (-not $SkipGo) {
  if ($Arm64Only) {
    & (Join-Path $Root 'scripts\build-go.ps1') -SdkRoot $resolvedSdk -Abis @('arm64-v8a')
  } else {
    & (Join-Path $Root 'scripts\build-go.ps1') -SdkRoot $resolvedSdk
  }
} else {
  $abiList = if ($Arm64Only) { @('arm64-v8a') } else { @('arm64-v8a', 'x86_64') }
  foreach ($abi in $abiList) {
    $native = Join-Path $Root "android-app\app\src\main\jniLibs\$abi\libcliproxyapi.so"
    if ((-not (Test-Path $native -PathType Leaf)) -or ((Get-Item $native).Length -eq 0)) {
      throw "-SkipGo requested but native binary is missing or empty: $native"
    }
  }
  Write-Host 'Skipping Go compilation; using existing native core.'
}

$wrapper = Join-Path $Root 'android-app\gradle\wrapper\gradle-wrapper.jar'
if (-not (Test-Path $wrapper -PathType Leaf)) {
  & (Join-Path $Root 'scripts\bootstrap-gradle-wrapper.ps1')
}
if (-not (Test-Path $wrapper -PathType Leaf)) {
  throw "Gradle wrapper bootstrap failed; JAR is missing: $wrapper"
}

$apkSource = Join-Path $Root 'android-app\app\build\outputs\apk\debug\app-debug.apk'
$androidAppRoot = Join-Path $Root 'android-app'
$abiProperty = if ($Arm64Only) { 'arm64-v8a' } else { 'arm64-v8a,x86_64' }
if ($Arm64Only) {
  Write-Host 'APK ABI filter: arm64-v8a only'
} else {
  Write-Host 'APK ABI filters: arm64-v8a, x86_64'
}

function Get-NewJvmCrashLogs {
  param([datetime]$Since)
  $dirs = @($androidAppRoot, $Root) | Select-Object -Unique
  $logs = New-Object 'System.Collections.Generic.List[System.IO.FileInfo]'
  foreach ($dir in $dirs) {
    if (-not (Test-Path $dir -PathType Container)) { continue }
    foreach ($item in @(Get-ChildItem -LiteralPath $dir -Filter 'hs_err_pid*.log' -File -ErrorAction SilentlyContinue)) {
      if ($item.LastWriteTime -ge $Since.AddSeconds(-2)) { $logs.Add($item) }
    }
  }
  return @($logs | Sort-Object LastWriteTime -Descending -Unique)
}

function Show-JvmCrashSummary {
  param([System.IO.FileInfo[]]$Logs)
  foreach ($log in $Logs) {
    Write-Warning "JVM crash log: $($log.FullName)"
    try {
      $diagDir = Join-Path $Root 'dist\diagnostics'
      New-Item -ItemType Directory -Force $diagDir | Out-Null
      Copy-Item -LiteralPath $log.FullName -Destination (Join-Path $diagDir $log.Name) -Force
      Write-Host "  Copied crash log to: $diagDir\$($log.Name)"
      $interesting = Get-Content -LiteralPath $log.FullName -ErrorAction Stop |
        Where-Object {
          $_ -match '^# (A fatal error|JRE version|Java VM|Problematic frame|Internal Error|SIG|EXCEPTION_)' -or
          $_ -match '^#  (EXCEPTION_|SIG|Internal Error)' -or
          $_ -match '^# C  \[' -or
          $_ -match '^# V  \['
        } |
        Select-Object -First 24
      if ($interesting) { $interesting | ForEach-Object { Write-Host "  $_" } }
    } catch {
      Write-Warning "Could not read JVM crash log: $($_.Exception.Message)"
    }
  }
}

function Stop-GradleDaemons {
  Push-Location $androidAppRoot
  try {
    Write-Host 'Stopping any existing Gradle 9.3.1 daemons...'
    & '.\gradlew.bat' --stop 2>$null | Out-Host
  } catch {
    # --stop is best-effort; a dead/corrupt daemon may already be gone.
  } finally {
    Pop-Location
  }
}

function Invoke-GradleBuild {
  param(
    [switch]$ConservativeJvm
  )

  $gradleInvocationArgs = New-Object 'System.Collections.Generic.List[string]'
  $gradleInvocationArgs.Add('--no-daemon')
  $gradleInvocationArgs.Add('--no-parallel')
  $gradleInvocationArgs.Add('--no-watch-fs')
  $gradleInvocationArgs.Add('-Dorg.gradle.internal.instrumentation.agent=false')
  if ($ConservativeJvm) {
    $gradleInvocationArgs.Add('--max-workers=1')
    # Native JVM crashes are not ordinary Java OOMs. The fallback removes C2
    # compilation and uses SerialGC to avoid JIT/GC native crash paths while
    # still leaving enough heap for AGP/D8 and the large GeckoView AAR.
    $gradleInvocationArgs.Add('-Dorg.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8')
    Write-Host 'Gradle JVM safety mode: single worker, SerialGC, C1-only JIT.'
  } else {
    $gradleInvocationArgs.Add('--max-workers=2')
  }
  $gradleInvocationArgs.Add("-PcpaAbis=$abiProperty")
  $gradleInvocationArgs.Add('assembleDebug')

  # Never publish a stale APK from a previous run if this build fails.
  if (Test-Path $apkSource) { Remove-Item $apkSource -Force }

  $started = Get-Date
  Push-Location $androidAppRoot
  try {
    & '.\gradlew.bat' @gradleInvocationArgs
    $exit = $LASTEXITCODE
  } finally {
    Pop-Location
  }

  return [pscustomobject]@{
    ExitCode = $exit
    Started = $started
    CrashLogs = @(Get-NewJvmCrashLogs -Since $started)
  }
}

# The previous build reused a 63-minute-old daemon and produced hs_err_pid*.log,
# i.e. the Java VM itself crashed. Do not reuse persistent daemons for this
# packaging pipeline. Gradle officially supports --stop/--no-daemon; the
# instrumentation agent is also disabled for this build to reduce native/JVMTI
# moving parts on Windows/JBR.
Stop-GradleDaemons
$first = Invoke-GradleBuild
if ($first.ExitCode -ne 0) {
  if ($first.CrashLogs.Count -gt 0) {
    Show-JvmCrashSummary -Logs $first.CrashLogs
    if (-not $NoGradleCrashRetry) {
      Write-Warning 'The Gradle JVM crashed natively. Retrying once with conservative JVM settings...'
      Stop-GradleDaemons
      $retry = Invoke-GradleBuild -ConservativeJvm
      if ($retry.ExitCode -ne 0) {
        if ($retry.CrashLogs.Count -gt 0) { Show-JvmCrashSummary -Logs $retry.CrashLogs }
        $latest = @($retry.CrashLogs + $first.CrashLogs | Sort-Object LastWriteTime -Descending | Select-Object -First 1)
        $hint = if ($latest.Count -gt 0) { " Latest JVM crash log: $($latest[0].FullName)" } else { '' }
        throw "Gradle assembleDebug failed after the automatic JVM-crash retry (exit code $($retry.ExitCode)).$hint"
      }
    } else {
      throw "Gradle assembleDebug failed because the JVM crashed (exit code $($first.ExitCode)); retry disabled by -NoGradleCrashRetry."
    }
  } else {
    throw "Gradle assembleDebug failed (exit code $($first.ExitCode)). See errors above; no APK was published."
  }
}

if ((-not (Test-Path $apkSource -PathType Leaf)) -or ((Get-Item $apkSource -ErrorAction SilentlyContinue).Length -eq 0)) {
  throw "Gradle reported success but APK was not created (or is empty): $apkSource"
}
$dist = Join-Path $Root 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
$apkDestination = Join-Path $dist 'CLIProxyAPI-Android-debug.apk'
Copy-Item $apkSource $apkDestination -Force
Write-Host "APK: $apkDestination"
Write-Host "SHA-256: $((Get-FileHash $apkDestination -Algorithm SHA256).Hash.ToLowerInvariant())"
