# Browser/UI rework — 2026-10-07

This revision addresses three concrete UI/browser requirements:

1. **Real fullscreen browser layout**
   - Normal mode: config + browser share one responsive page.
   - Fullscreen mode: the existing `browserCard` is removed from `browserSlot` and re-parented into `browserFullscreenHost`, a top-level `match_parent` overlay.
   - The config pane, app toolbar and system bars are hidden; the same GeckoView/sessions remain alive.
   - Exiting fullscreen moves the same browser card back to the split view.

2. **In-app multi-tab Gecko browser for OAuth**
   - Each tab owns a `GeckoSession`.
   - All tabs share `contextId=cliproxy-browser`, so cookies/localStorage/login state are shared between the app's tabs while remaining partitioned from the device browser/System WebView.
   - `target=_blank` and `window.open()` now use `NavigationDelegate.onNewSession()` and create a real in-app tab instead of collapsing into the current page.
   - `window.close()` closes that app tab.
   - UI adds tab switching/closing, new-tab, address bar, Go, back/forward/reload and WebUI-home controls.

3. **System-bar/cutout insets**
   - Normal mode uses `WindowInsetsCompat` for status bar, navigation bar and display cutout safe insets.
   - Fullscreen mode intentionally removes those normal-content insets and hides system bars with transient swipe behavior.

## Build

If the native core and WebUI are already present:

```powershell
.\build-all.ps1 -Arm64Only -SkipGo -SkipWebUi
```

For a full rebuild:

```powershell
.\build-all.ps1 -Arm64Only
```

## Validation done in the generation environment

- All Android XML resources parse successfully.
- Every Java `R.id.*` reference resolves to a declared resource ID.
- No `android.webkit.WebView` source reference exists.
- Java source has no syntax-level compiler diagnostics in a parse-only check; Android/Gecko symbols cannot be fully linked here because this environment does not contain the Android SDK/Gradle dependency cache.
- GeckoView API use for new-window tabs follows Mozilla's `NavigationDelegate.onNewSession()` contract: the returned child session is newly created and unopened; Gecko opens it on the parent's runtime.
