CLIProxyAPI Android GUI Java compile fix

Overwrite these two files in the project root:
  android-app/app/src/main/java/io/github/cliproxy/android/CpaService.java
  android-app/app/src/main/java/io/github/cliproxy/android/MainActivity.java

Fixes:
1) Removes unsupported java.lang.Process.pid() call from Android SDK compilation.
   Child PID was only cosmetic notification text; process lifecycle remains managed by Process.
2) Replaces nonexistent setMarginBottom()/setMarginTop() calls with bottomMargin/topMargin fields.

After applying, reuse the already-built Go core and WebUI:
  .\build-all.ps1 -Arm64Only -SkipGo -SkipWebUi
