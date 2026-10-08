CLIProxyAPI Android cumulative build fix
========================================

Extract this ZIP into the parent directory that contains CLIProxyAPI-Android-GUI,
or copy the enclosed files over the matching project paths.

Main fixes in this cumulative patch:
- minSdk 24 -> 26 because GeckoView 156 itself declares minSdk 26.
- Removed explicit android:extractNativeLibs from AndroidManifest.xml; AGP's
  packaging.jniLibs.useLegacyPackaging=true remains the source of truth.
- NDK clang API target aligned to android26.
- Arm64Only now also filters the final APK to arm64-v8a, preventing GeckoView's
  x86_64 libraries from making an Arm64-only APK appear x86_64-capable.
- Includes all previous Windows/Gradle/SDK/API37/Java compile fixes.
- Gecko session is closed with the Activity; foreground CPA service is not stopped.

Recommended rerun after applying:
  .\build-all.ps1 -Arm64Only -SkipGo -SkipWebUi

Use the full command without SkipGo/SkipWebUi if you want a clean native/WebUI rebuild:
  .\build-all.ps1 -Arm64Only
