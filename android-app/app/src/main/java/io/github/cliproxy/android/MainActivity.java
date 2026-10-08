package io.github.cliproxy.android;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import org.mozilla.geckoview.GeckoView;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ConfigRepository repository;
    private GeckoController gecko;
    private SharedPreferences backgroundPrefs;
    private boolean browserFullscreen;
    private boolean webLoaded;
    private int currentPort = 8317;

    private LinearLayout mainSplit;
    private NestedScrollView configScroll;
    private FrameLayout browserSlot, browserFullscreenHost;
    private View appContent;
    private MaterialCardView configCard, browserCard;
    private MaterialToolbar toolbar;
    private TextView statusText, webHint;
    private TextInputEditText addressEdit;
    private ChipGroup tabStrip;
    private TextInputEditText hostEdit, portEdit, trustedProxiesEdit, apiKeysEdit, managementSecretEdit, panelRepositoryEdit;
    private TextInputEditText requestRetryEdit, maxRetryCredentialsEdit, maxRetryIntervalEdit, sessionAffinityTtlEdit, transientCooldownEdit;
    private TextInputEditText proxyUrlEdit, nonstreamKeepaliveEdit, streamKeepaliveEdit, bootstrapRetriesEdit;
    private TextInputEditText logsMaxTotalSizeEdit, errorLogsMaxFilesEdit;
    private AutoCompleteTextView routingStrategyEdit;
    private SwitchMaterial discoverySwitch, commercialModeSwitch, managementRemoteSwitch;
    private SwitchMaterial disablePanelSwitch, disablePanelAutoUpdateSwitch, sessionAffinitySwitch, sessionSubagentsSwitch, forceModelPrefixSwitch, disableCoolingSwitch, saveCooldownSwitch;
    private SwitchMaterial passthroughHeadersSwitch, debugSwitch, loggingFileSwitch, requestLogSwitch, usageStatsSwitch;
    private SwitchMaterial startOnBootSwitch, autoRestartSwitch, cpuWakeLockSwitch, wifiLockSwitch;

    private final Runnable statusPoller = new Runnable() {
        @Override public void run() {
            int port = currentPort;
            io.submit(() -> {
                boolean online = isPortOpen(port);
                boolean binary = AppPaths.nativeBinary(MainActivity.this).isFile();
                runOnUiThread(() -> updateStatus(online, binary));
            });
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        repository = new ConfigRepository(this);
        backgroundPrefs = getSharedPreferences("background", MODE_PRIVATE);
        bindViews();
        configureLanguageAction();
        configureRoutingDropdown();
        configureBackgroundPolicy();
        configureButtons();
        configureWindowInsets();
        configureKeyboardAwareScrolling();
        applyResponsiveLayout();
        requestNotificationPermissionIfNeeded();

        GeckoView geckoView = findViewById(R.id.geckoView);
        gecko = new GeckoController(this, geckoView, new GeckoController.Listener() {
            @Override public void onTitle(String title) {
                // Active-tab title is rendered in the tab chip.
            }

            @Override public void onLocation(String url) {
                if (!addressEdit.hasFocus()) {
                    addressEdit.setText("about:blank".equals(url) ? "" : url);
                }
                if (url != null && !url.isEmpty() && !"about:blank".equals(url)) {
                    webLoaded = true;
                    webHint.setVisibility(View.GONE);
                }
            }

            @Override public void onLoading(boolean loading) {
                findViewById(R.id.reloadButton).setAlpha(loading ? 0.55f : 1f);
            }

            @Override public void onTabsChanged(List<GeckoController.TabInfo> tabs, int activeIndex) {
                renderTabs(tabs, activeIndex);
            }

            @Override public void onNavigationState(boolean canGoBack, boolean canGoForward) {
                View back = findViewById(R.id.backButton);
                View forward = findViewById(R.id.forwardButton);
                back.setEnabled(canGoBack);
                forward.setEnabled(canGoForward);
                back.setAlpha(canGoBack ? 1f : 0.38f);
                forward.setAlpha(canGoForward ? 1f : 0.38f);
            }

            @Override public void onPageFullScreen(boolean fullScreen) {
                setBrowserFullscreen(fullScreen);
            }
        });

        try { loadForm(); }
        catch (Exception e) { showError(getString(R.string.error_read_config), e); }

        handler.post(statusPoller);
    }

    private void bindViews() {
        toolbar = findViewById(R.id.toolbar);
        appContent = findViewById(R.id.appContent);
        mainSplit = findViewById(R.id.mainSplit);
        configScroll = findViewById(R.id.configScroll);
        browserSlot = findViewById(R.id.browserSlot);
        browserFullscreenHost = findViewById(R.id.browserFullscreenHost);
        configCard = findViewById(R.id.configCard);
        browserCard = findViewById(R.id.browserCard);
        statusText = findViewById(R.id.statusText);
        webHint = findViewById(R.id.webHint);
        addressEdit = findViewById(R.id.addressEdit);
        tabStrip = findViewById(R.id.tabStrip);
        hostEdit = findViewById(R.id.hostEdit);
        portEdit = findViewById(R.id.portEdit);
        trustedProxiesEdit = findViewById(R.id.trustedProxiesEdit);
        apiKeysEdit = findViewById(R.id.apiKeysEdit);
        managementSecretEdit = findViewById(R.id.managementSecretEdit);
        panelRepositoryEdit = findViewById(R.id.panelRepositoryEdit);
        routingStrategyEdit = findViewById(R.id.routingStrategyEdit);
        requestRetryEdit = findViewById(R.id.requestRetryEdit);
        sessionAffinityTtlEdit = findViewById(R.id.sessionAffinityTtlEdit);
        transientCooldownEdit = findViewById(R.id.transientCooldownEdit);
        maxRetryCredentialsEdit = findViewById(R.id.maxRetryCredentialsEdit);
        maxRetryIntervalEdit = findViewById(R.id.maxRetryIntervalEdit);
        proxyUrlEdit = findViewById(R.id.proxyUrlEdit);
        nonstreamKeepaliveEdit = findViewById(R.id.nonstreamKeepaliveEdit);
        streamKeepaliveEdit = findViewById(R.id.streamKeepaliveEdit);
        bootstrapRetriesEdit = findViewById(R.id.bootstrapRetriesEdit);
        discoverySwitch = findViewById(R.id.discoverySwitch);
        commercialModeSwitch = findViewById(R.id.commercialModeSwitch);
        managementRemoteSwitch = findViewById(R.id.managementRemoteSwitch);
        disablePanelSwitch = findViewById(R.id.disablePanelSwitch);
        disablePanelAutoUpdateSwitch = findViewById(R.id.disablePanelAutoUpdateSwitch);
        sessionAffinitySwitch = findViewById(R.id.sessionAffinitySwitch);
        sessionSubagentsSwitch = findViewById(R.id.sessionSubagentsSwitch);
        forceModelPrefixSwitch = findViewById(R.id.forceModelPrefixSwitch);
        disableCoolingSwitch = findViewById(R.id.disableCoolingSwitch);
        saveCooldownSwitch = findViewById(R.id.saveCooldownSwitch);
        passthroughHeadersSwitch = findViewById(R.id.passthroughHeadersSwitch);
        debugSwitch = findViewById(R.id.debugSwitch);
        loggingFileSwitch = findViewById(R.id.loggingFileSwitch);
        requestLogSwitch = findViewById(R.id.requestLogSwitch);
        usageStatsSwitch = findViewById(R.id.usageStatsSwitch);
        logsMaxTotalSizeEdit = findViewById(R.id.logsMaxTotalSizeEdit);
        errorLogsMaxFilesEdit = findViewById(R.id.errorLogsMaxFilesEdit);
        startOnBootSwitch = findViewById(R.id.startOnBootSwitch);
        autoRestartSwitch = findViewById(R.id.autoRestartSwitch);
        cpuWakeLockSwitch = findViewById(R.id.cpuWakeLockSwitch);
        wifiLockSwitch = findViewById(R.id.wifiLockSwitch);
    }

    private void configureRoutingDropdown() {
        List<String> values = Arrays.asList("round-robin", "weighted-round-robin", "fill-first");
        routingStrategyEdit.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, values));
    }

    private void configureBackgroundPolicy() {
        startOnBootSwitch.setChecked(backgroundPrefs.getBoolean("start_on_boot", false));
        autoRestartSwitch.setChecked(backgroundPrefs.getBoolean("auto_restart", true));
        cpuWakeLockSwitch.setChecked(backgroundPrefs.getBoolean("cpu_wakelock", true));
        wifiLockSwitch.setChecked(backgroundPrefs.getBoolean("wifi_lock", false));
        startOnBootSwitch.setOnCheckedChangeListener((b, v) -> backgroundPrefs.edit().putBoolean("start_on_boot", v).apply());
        autoRestartSwitch.setOnCheckedChangeListener((b, v) -> backgroundPrefs.edit().putBoolean("auto_restart", v).apply());
        cpuWakeLockSwitch.setOnCheckedChangeListener((b, v) -> backgroundPrefs.edit().putBoolean("cpu_wakelock", v).apply());
        wifiLockSwitch.setOnCheckedChangeListener((b, v) -> backgroundPrefs.edit().putBoolean("wifi_lock", v).apply());
        findViewById(R.id.batteryButton).setOnClickListener(v -> BatteryPolicy.openRequest(this));
    }

    private void configureButtons() {
        findViewById(R.id.startButton).setOnClickListener(v -> {
            if (saveForm()) CpaService.start(this);
        });
        findViewById(R.id.stopButton).setOnClickListener(v -> CpaService.stop(this));
        findViewById(R.id.restartButton).setOnClickListener(v -> CpaService.restart(this));
        findViewById(R.id.saveButton).setOnClickListener(v -> saveForm());
        findViewById(R.id.saveRestartButton).setOnClickListener(v -> {
            if (saveForm()) CpaService.restart(this);
        });
        findViewById(R.id.rawYamlButton).setOnClickListener(v -> showRawYamlEditor());
        findViewById(R.id.logButton).setOnClickListener(v -> showLogs());

        findViewById(R.id.backButton).setOnClickListener(v -> gecko.back());
        findViewById(R.id.forwardButton).setOnClickListener(v -> gecko.forward());
        findViewById(R.id.reloadButton).setOnClickListener(v -> gecko.reload());
        findViewById(R.id.homeButton).setOnClickListener(v -> {
            webLoaded = true;
            webHint.setVisibility(View.GONE);
            gecko.load(dashboardUrl());
        });
        findViewById(R.id.newTabButton).setOnClickListener(v -> {
            gecko.newBlankTab(true);
            focusAddressBarForNewTab();
        });
        findViewById(R.id.newAccountButton).setOnClickListener(v -> openNewIsolatedAccountTab());
        findViewById(R.id.browserDataButton).setOnClickListener(v -> showBrowserDataManager());
        findViewById(R.id.fullscreenButton).setOnClickListener(v -> setBrowserFullscreen(!browserFullscreen));
        findViewById(R.id.goButton).setOnClickListener(v -> navigateAddressBar());

        addressEdit.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                navigateAddressBar();
                return true;
            }
            return false;
        });
        addressEdit.setOnFocusChangeListener((v, focused) -> {
            if (focused) addressEdit.selectAll();
        });
    }

    private void configureLanguageAction() {
        MenuItem language = toolbar.getMenu().add(isChineseUi()
                ? R.string.language_switch_to_en : R.string.language_switch_to_zh);
        language.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        language.setOnMenuItemClickListener(item -> {
            String tag = isChineseUi() ? "en" : "zh-CN";
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag));
            return true;
        });
    }

    private boolean isChineseUi() {
        if (getResources().getConfiguration().getLocales().isEmpty()) return false;
        return "zh".equalsIgnoreCase(getResources().getConfiguration().getLocales().get(0).getLanguage());
    }

    private void focusAddressBarForNewTab() {
        addressEdit.setText("");
        addressEdit.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(addressEdit, InputMethodManager.SHOW_IMPLICIT);
    }

    private void openNewIsolatedAccountTab() {
        gecko.newIsolatedBlankTab(true);
        focusAddressBarForNewTab();
        Snackbar.make(browserCard, R.string.browser_account_created, Snackbar.LENGTH_SHORT).show();
    }

    private MaterialButton browserDataActionButton(int textRes) {
        MaterialButton button = new MaterialButton(this);
        button.setText(textRes);
        button.setAllCaps(false);
        button.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        button.setLayoutParams(lp);
        return button;
    }

    private void showBrowserDataManager() {
        int container = gecko.currentContainerNumber();
        String current = container == 0
                ? getString(R.string.browser_data_current_main)
                : getString(R.string.browser_data_current_isolated, container);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(4), dp(24), dp(8));

        TextView currentView = new TextView(this);
        currentView.setText(current);
        currentView.setTextSize(16f);
        currentView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(currentView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView explanation = new TextView(this);
        explanation.setText(R.string.browser_data_explainer);
        explanation.setTextSize(14f);
        explanation.setPadding(0, dp(8), 0, dp(4));
        content.addView(explanation, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        MaterialButton newIsolated = browserDataActionButton(R.string.browser_data_new_isolated);
        MaterialButton clearCurrent = browserDataActionButton(R.string.browser_data_clear_current);
        MaterialButton clearCache = browserDataActionButton(R.string.browser_data_clear_cache);
        MaterialButton clearLogins = browserDataActionButton(R.string.browser_data_clear_all_logins);
        MaterialButton clearEverything = browserDataActionButton(R.string.browser_data_clear_everything);
        content.addView(newIsolated);
        content.addView(clearCurrent);
        content.addView(clearCache);
        content.addView(clearLogins);
        content.addView(clearEverything);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.browser_data_title)
                .setView(content)
                .setNegativeButton(R.string.browser_data_close, null)
                .create();

        newIsolated.setOnClickListener(v -> {
            dialog.dismiss();
            openNewIsolatedAccountTab();
        });
        clearCurrent.setOnClickListener(v -> {
            dialog.dismiss();
            gecko.clearCurrentContainer(error -> showBrowserDataResult(
                    error, R.string.browser_data_cleared_current));
        });
        clearCache.setOnClickListener(v -> {
            dialog.dismiss();
            gecko.clearCache(error -> showBrowserDataResult(
                    error, R.string.browser_data_cleared_cache));
        });
        clearLogins.setOnClickListener(v -> {
            dialog.dismiss();
            gecko.clearAllLoginData(error -> showBrowserDataResult(
                    error, R.string.browser_data_cleared_logins));
        });
        clearEverything.setOnClickListener(v -> {
            dialog.dismiss();
            confirmClearAllBrowserData();
        });
        dialog.show();
    }

    private void confirmClearAllBrowserData() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.browser_data_confirm_title)
                .setMessage(R.string.browser_data_confirm_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.browser_data_confirm_clear, (d, w) ->
                        gecko.clearAllBrowserData(error -> showBrowserDataResult(
                                error, R.string.browser_data_cleared_all)))
                .show();
    }

    private void showBrowserDataResult(Throwable error, int successMessage) {
        runOnUiThread(() -> {
            if (error == null) {
                Snackbar.make(mainSplit, successMessage, Snackbar.LENGTH_SHORT).show();
            } else {
                showError(getString(R.string.browser_data_failed), error);
            }
        });
    }

    private void navigateAddressBar() {
        String raw = addressEdit.getText() == null ? "" : addressEdit.getText().toString();
        String url = normalizeUrl(raw);
        if (url.isEmpty()) return;
        webLoaded = true;
        webHint.setVisibility(View.GONE);
        gecko.load(url);
        addressEdit.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(addressEdit.getWindowToken(), 0);
    }

    private static String normalizeUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) return "";
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("://") || lower.startsWith("about:") || lower.startsWith("data:") || lower.startsWith("file:")) {
            return url;
        }
        if (lower.startsWith("localhost") || lower.startsWith("127.") || lower.startsWith("[::1]")) {
            return "http://" + url;
        }
        return "https://" + url;
    }

    private void renderTabs(List<GeckoController.TabInfo> tabs, int activeIndex) {
        tabStrip.removeAllViews();
        for (int i = 0; i < tabs.size(); i++) {
            GeckoController.TabInfo tab = tabs.get(i);
            Chip chip = new Chip(this);
            chip.setId(View.generateViewId());
            chip.setCheckable(true);
            chip.setChecked(i == activeIndex);
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setMaxWidth(dp(220));
            String tabTitle = tab.containerNumber > 0
                    ? getString(R.string.browser_tab_isolated_prefix, tab.containerNumber, tab.title)
                    : tab.title;
            chip.setText(tab.loading ? tabTitle + " …" : tabTitle);
            chip.setCloseIconResource(R.drawable.ic_close_24);
            chip.setCloseIconVisible(true);
            chip.setOnClickListener(v -> gecko.selectTab(tab.id));
            chip.setOnCloseIconClickListener(v -> gecko.closeTab(tab.id));
            tabStrip.addView(chip);
        }
        if (activeIndex >= 0 && activeIndex < tabs.size()) {
            GeckoController.TabInfo active = tabs.get(activeIndex);
            if (!addressEdit.hasFocus()) {
                addressEdit.setText("about:blank".equals(active.url) ? "" : active.url);
            }
        }
    }

    private void loadForm() throws Exception {
        ConfigRepository.Settings s = repository.loadSettings();
        hostEdit.setText(s.host);
        portEdit.setText(String.valueOf(s.port));
        trustedProxiesEdit.setText(String.join("\n", s.trustedProxies));
        currentPort = s.port;
        discoverySwitch.setChecked(s.discovery);
        commercialModeSwitch.setChecked(s.commercialMode);
        managementRemoteSwitch.setChecked(s.allowRemoteManagement);
        managementSecretEdit.setText(s.managementSecret);
        disablePanelSwitch.setChecked(s.disableControlPanel);
        disablePanelAutoUpdateSwitch.setChecked(s.disablePanelAutoUpdate);
        panelRepositoryEdit.setText(s.panelRepository);
        apiKeysEdit.setText(String.join("\n", s.apiKeys));
        routingStrategyEdit.setText(s.routingStrategy, false);
        sessionAffinitySwitch.setChecked(s.sessionAffinity);
        sessionAffinityTtlEdit.setText(s.sessionAffinityTtl);
        sessionSubagentsSwitch.setChecked(s.sessionAffinitySubagents);
        forceModelPrefixSwitch.setChecked(s.forceModelPrefix);
        requestRetryEdit.setText(String.valueOf(s.requestRetry));
        maxRetryCredentialsEdit.setText(String.valueOf(s.maxRetryCredentials));
        maxRetryIntervalEdit.setText(String.valueOf(s.maxRetryInterval));
        disableCoolingSwitch.setChecked(s.disableCooling);
        saveCooldownSwitch.setChecked(s.saveCooldown);
        transientCooldownEdit.setText(String.valueOf(s.transientCooldownSeconds));
        proxyUrlEdit.setText(s.proxyUrl);
        passthroughHeadersSwitch.setChecked(s.passthroughHeaders);
        nonstreamKeepaliveEdit.setText(String.valueOf(s.nonstreamKeepalive));
        streamKeepaliveEdit.setText(String.valueOf(s.streamKeepalive));
        bootstrapRetriesEdit.setText(String.valueOf(s.bootstrapRetries));
        debugSwitch.setChecked(s.debug);
        loggingFileSwitch.setChecked(s.loggingToFile);
        requestLogSwitch.setChecked(s.requestLog);
        usageStatsSwitch.setChecked(s.usageStatistics);
        logsMaxTotalSizeEdit.setText(String.valueOf(s.logsMaxTotalSizeMb));
        errorLogsMaxFilesEdit.setText(String.valueOf(s.errorLogsMaxFiles));
    }

    private boolean saveForm() {
        try {
            ConfigRepository.Settings s = new ConfigRepository.Settings();
            s.host = text(hostEdit, "0.0.0.0");
            s.port = integer(portEdit, 8317);
            s.trustedProxies = lines(trustedProxiesEdit);
            if (s.port < 1 || s.port > 65535) throw new IllegalArgumentException(getString(R.string.error_port_range));
            s.discovery = discoverySwitch.isChecked();
            s.commercialMode = commercialModeSwitch.isChecked();
            s.allowRemoteManagement = managementRemoteSwitch.isChecked();
            s.managementSecret = text(managementSecretEdit, "");
            s.disableControlPanel = disablePanelSwitch.isChecked();
            s.disablePanelAutoUpdate = disablePanelAutoUpdateSwitch.isChecked();
            s.panelRepository = text(panelRepositoryEdit, "https://github.com/router-for-me/Cli-Proxy-API-Management-Center");
            s.apiKeys = lines(apiKeysEdit);
            s.routingStrategy = routingStrategyEdit.getText().toString().trim();
            s.sessionAffinity = sessionAffinitySwitch.isChecked();
            s.sessionAffinityTtl = text(sessionAffinityTtlEdit, "1h");
            s.sessionAffinitySubagents = sessionSubagentsSwitch.isChecked();
            s.forceModelPrefix = forceModelPrefixSwitch.isChecked();
            if (s.routingStrategy.trim().isEmpty()) s.routingStrategy = "round-robin";
            s.requestRetry = integer(requestRetryEdit, 3);
            s.maxRetryCredentials = integer(maxRetryCredentialsEdit, 0);
            s.maxRetryInterval = integer(maxRetryIntervalEdit, 30);
            s.disableCooling = disableCoolingSwitch.isChecked();
            s.saveCooldown = saveCooldownSwitch.isChecked();
            s.transientCooldownSeconds = integer(transientCooldownEdit, 0);
            s.proxyUrl = text(proxyUrlEdit, "");
            s.passthroughHeaders = passthroughHeadersSwitch.isChecked();
            s.nonstreamKeepalive = integer(nonstreamKeepaliveEdit, 0);
            s.streamKeepalive = integer(streamKeepaliveEdit, 0);
            s.bootstrapRetries = integer(bootstrapRetriesEdit, 0);
            s.debug = debugSwitch.isChecked();
            s.loggingToFile = loggingFileSwitch.isChecked();
            s.requestLog = requestLogSwitch.isChecked();
            s.usageStatistics = usageStatsSwitch.isChecked();
            s.logsMaxTotalSizeMb = integer(logsMaxTotalSizeEdit, 0);
            s.errorLogsMaxFiles = integer(errorLogsMaxFilesEdit, 10);
            repository.saveSettings(s);
            currentPort = s.port;
                Snackbar.make(mainSplit, R.string.msg_config_saved, Snackbar.LENGTH_SHORT).show();
            if (s.managementSecret.trim().isEmpty()) {
                Snackbar.make(mainSplit, R.string.msg_management_secret_empty, Snackbar.LENGTH_LONG).show();
            }
            return true;
        } catch (Exception e) {
            showError(getString(R.string.error_config_not_saved), e);
            return false;
        }
    }

    private void updateStatus(boolean online, boolean binaryPresent) {
        String battery = getString(BatteryPolicy.isIgnoringOptimizations(this)
                ? R.string.status_battery_unrestricted : R.string.status_battery_optimized);
        String abi = Build.SUPPORTED_ABIS.length == 0 ? getString(R.string.status_unknown_abi) : Build.SUPPORTED_ABIS[0];
        if (!binaryPresent) {
            statusText.setText(getString(R.string.status_native_missing, abi));
            webHint.setVisibility(View.VISIBLE);
            return;
        }
        if (online) {
            statusText.setText(getString(R.string.status_online, currentPort, battery));
            webHint.setVisibility(View.GONE);
            if (!webLoaded) {
                webLoaded = true;
                gecko.load(dashboardUrl());
            }
        } else {
            statusText.setText(getString(R.string.status_offline, battery));
            if (!webLoaded) webHint.setVisibility(View.VISIBLE);
        }
    }

    private String dashboardUrl() { return "http://127.0.0.1:" + currentPort + "/management.html"; }

    private boolean isPortOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 350);
            return true;
        } catch (Exception ignored) { return false; }
    }

    private void showRawYamlEditor() {
        try {
            TextInputEditText edit = new TextInputEditText(this);
            edit.setText(repository.readRaw());
            edit.setTypeface(Typeface.MONOSPACE);
            edit.setTextSize(12f);
            edit.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
            edit.setMinLines(18);
            edit.setMaxLines(32);
            edit.setHorizontallyScrolling(true);
            int pad = (int)(16 * getResources().getDisplayMetrics().density);
            edit.setPadding(pad, pad, pad, pad);
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.yaml_title)
                    .setMessage(R.string.yaml_message)
                    .setView(edit)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_save, (d, w) -> {
                        try {
                            repository.writeRaw(edit.getText() == null ? "" : edit.getText().toString());
                            loadForm();
                            Snackbar.make(mainSplit, R.string.msg_yaml_saved, Snackbar.LENGTH_SHORT).show();
                        } catch (Exception e) { showError(getString(R.string.error_yaml_invalid), e); }
                    }).show();
        } catch (Exception e) { showError(getString(R.string.error_yaml_open), e); }
    }

    private void showLogs() {
        try {
            File file = AppPaths.wrapperLog(this);
            String text = file.isFile() ? readTextFile(file) : getString(R.string.log_empty);
            if (text.length() > 30000) text = getString(R.string.log_tail) + text.substring(text.length() - 30000);
            TextView view = new TextView(this);
            view.setText(text);
            view.setTypeface(Typeface.MONOSPACE);
            view.setTextSize(11f);
            view.setTextIsSelectable(true);
            int pad = (int)(16 * getResources().getDisplayMetrics().density);
            view.setPadding(pad, pad, pad, pad);
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.log_title)
                    .setView(view)
                    .setNegativeButton(R.string.action_close, null)
                    .setPositiveButton(R.string.action_reload, (d, w) -> showLogs())
                    .show();
        } catch (Exception e) { showError(getString(R.string.error_log_read), e); }
    }

    private void configureWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            applyWindowInsets(insets);
            return insets;
        });
        ViewCompat.setWindowInsetsAnimationCallback(root,
                new WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    @NonNull
                    @Override
                    public WindowInsetsCompat onProgress(@NonNull WindowInsetsCompat insets,
                                                         @NonNull List<WindowInsetsAnimationCompat> runningAnimations) {
                        applyWindowInsets(insets);
                        return insets;
                    }
                });
        updateSystemBarAppearance();
        ViewCompat.requestApplyInsets(root);
    }

    private void applyWindowInsets(WindowInsetsCompat insets) {
        Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
        boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());

        // With edge-to-edge enabled, adjustResize alone is not enough on every OEM.
        // Explicitly reserve whichever is taller: navigation/system bar or IME.
        int normalBottom = Math.max(bars.bottom, imeVisible ? ime.bottom : 0);
        appContent.setPadding(bars.left, bars.top, bars.right, normalBottom);

        // Fullscreen hides system bars, but still keep text fields above the IME and
        // out of a display cutout. This also makes GeckoView resize with the keyboard.
        Insets cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
        browserFullscreenHost.setPadding(cutout.left, cutout.top, cutout.right,
                imeVisible ? ime.bottom : 0);
    }

    private void configureKeyboardAwareScrolling() {
        int[] fieldIds = new int[]{
                R.id.hostEdit, R.id.portEdit, R.id.trustedProxiesEdit, R.id.apiKeysEdit,
                R.id.managementSecretEdit, R.id.panelRepositoryEdit, R.id.routingStrategyEdit,
                R.id.sessionAffinityTtlEdit, R.id.requestRetryEdit, R.id.maxRetryCredentialsEdit,
                R.id.maxRetryIntervalEdit, R.id.transientCooldownEdit, R.id.proxyUrlEdit,
                R.id.nonstreamKeepaliveEdit, R.id.streamKeepaliveEdit, R.id.bootstrapRetriesEdit,
                R.id.logsMaxTotalSizeEdit, R.id.errorLogsMaxFilesEdit
        };
        for (int id : fieldIds) {
            View field = findViewById(id);
            if (field == null) continue;
            field.setOnFocusChangeListener((v, focused) -> {
                if (!focused) return;
                configScroll.postDelayed(() -> scrollConfigFieldIntoView(v), 180);
            });
        }
    }

    private void scrollConfigFieldIntoView(View field) {
        if (configScroll == null || field == null) return;
        Rect rect = new Rect();
        field.getDrawingRect(rect);
        configScroll.offsetDescendantRectToMyCoords(field, rect);
        int target = Math.max(0, rect.top - dp(28));
        configScroll.smoothScrollTo(0, target);
    }

    private void updateSystemBarAppearance() {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(!night);
        controller.setAppearanceLightNavigationBars(!night);
    }

    private void setBrowserFullscreen(boolean fullscreen) {
        if (browserFullscreen == fullscreen) return;
        browserFullscreen = fullscreen;
        MaterialButton button = findViewById(R.id.fullscreenButton);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());

        ViewGroup parent = (ViewGroup) browserCard.getParent();
        if (parent != null) parent.removeView(browserCard);

        if (fullscreen) {
            appContent.setVisibility(View.GONE);
            browserFullscreenHost.setVisibility(View.VISIBLE);
            browserFullscreenHost.addView(browserCard, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            browserCard.setRadius(0f);
            browserCard.setStrokeWidth(0);
            button.setText("↙");
            hideFullscreenSystemBars();
        } else {
            browserFullscreenHost.removeAllViews();
            browserFullscreenHost.setVisibility(View.GONE);
            appContent.setVisibility(View.VISIBLE);
            browserSlot.addView(browserCard, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            browserCard.setRadius(dp(20));
            browserCard.setStrokeWidth(dp(1));
            button.setText("⛶");
            controller.show(WindowInsetsCompat.Type.systemBars());
            updateSystemBarAppearance();
            ViewCompat.requestApplyInsets(findViewById(R.id.root));
            applyResponsiveLayout();
        }
    }

    private void hideFullscreenSystemBars() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private void applyResponsiveLayout() {
        if (browserFullscreen) return;
        int widthDp = getResources().getConfiguration().screenWidthDp;
        boolean wide = widthDp >= 700;
        mainSplit.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams c = new LinearLayout.LayoutParams(
                wide ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                wide ? ViewGroup.LayoutParams.MATCH_PARENT : 0,
                wide ? 0.43f : 0.52f);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(
                wide ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                wide ? ViewGroup.LayoutParams.MATCH_PARENT : 0,
                wide ? 0.57f : 0.48f);
        int gap = dp(4);
        if (wide) {
            c.setMarginEnd(gap);
            b.setMarginStart(gap);
        } else {
            c.bottomMargin = gap;
            b.topMargin = gap;
        }
        configCard.setLayoutParams(c);
        browserSlot.setLayoutParams(b);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 40);
        }
    }

    private static String text(TextInputEditText e, String fallback) {
        String s = e.getText() == null ? "" : e.getText().toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    private static int integer(TextInputEditText e, int fallback) {
        try { return Integer.parseInt(text(e, String.valueOf(fallback))); }
        catch (NumberFormatException ex) { return fallback; }
    }

    private static List<String> lines(TextInputEditText e) {
        String raw = e.getText() == null ? "" : e.getText().toString();
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\\R")) if (!line.trim().isEmpty()) out.add(line.trim());
        return out;
    }


    private static String readTextFile(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void showError(String title, Throwable t) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(t.getMessage() == null ? t.toString() : t.getMessage())
                .setPositiveButton(R.string.action_ok, null)
                .show();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (browserFullscreen) hideFullscreenSystemBars();
        else updateSystemBarAppearance();
        handler.post(this::applyResponsiveLayout);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && browserFullscreen) hideFullscreenSystemBars();
    }

    @Override
    public void onBackPressed() {
        if (browserFullscreen) {
            setBrowserFullscreen(false);
            return;
        }
        if (gecko != null && gecko.canGoBack()) {
            gecko.back();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(statusPoller);
        io.shutdownNow();
        if (gecko != null) gecko.close();
        // Do not stop CpaService here: API serving must survive UI lifecycle.
        super.onDestroy();
    }
}
