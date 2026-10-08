$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\android-app")).Path
$Jar = Join-Path $Root "gradle\wrapper\gradle-wrapper.jar"
New-Item -ItemType Directory -Force (Split-Path $Jar) | Out-Null
$url = "https://raw.githubusercontent.com/gradle/gradle/v9.3.1/gradle/wrapper/gradle-wrapper.jar"
Invoke-WebRequest -Uri $url -OutFile $Jar
Write-Host "Gradle wrapper bootstrap complete: $Jar"
