package io.github.cliproxy.android;

import android.content.Context;
import android.content.SharedPreferences;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.StorageController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tabbed GeckoView controller with per-account cookie/storage containers.
 *
 * Normal tabs inherit the active tab's context so OAuth popups and related
 * pages share login state. "Isolated account" tabs receive a unique contextId;
 * GeckoView partitions cookies/localStorage by contextId. The last tab in an
 * isolated container clears that context automatically.
 */
public final class GeckoController {
    public interface Listener {
        void onTitle(String title);
        void onLocation(String url);
        void onLoading(boolean loading);
        void onTabsChanged(List<TabInfo> tabs, int activeIndex);
        void onNavigationState(boolean canGoBack, boolean canGoForward);
        void onPageFullScreen(boolean fullScreen);
    }

    public interface DataCallback {
        void onComplete(Throwable error);
    }

    public static final class TabInfo {
        public final long id;
        public final String title;
        public final String url;
        public final boolean loading;
        /** 0 = persistent main account, >0 = temporary isolated account. */
        public final int containerNumber;

        private TabInfo(long id, String title, String url, boolean loading, int containerNumber) {
            this.id = id;
            this.title = title;
            this.url = url;
            this.loading = loading;
            this.containerNumber = containerNumber;
        }
    }

    private static final AtomicLong NEXT_TAB_ID = new AtomicLong(1);
    private static final String MAIN_CONTEXT_ID = "cliproxy-browser";
    private static GeckoRuntime runtime;

    private static final class Tab {
        final long id = NEXT_TAB_ID.getAndIncrement();
        final GeckoSession session;
        final String contextId;
        final int containerNumber;
        String title;
        String url = "about:blank";
        boolean loading;
        boolean canGoBack;
        boolean canGoForward;
        boolean selectWhenReady;

        Tab(GeckoSession session, String contextId, int containerNumber, String newTabTitle) {
            this.session = session;
            this.contextId = contextId;
            this.containerNumber = containerNumber;
            this.title = newTabTitle;
        }
    }

    private final Context context;
    private final SharedPreferences containerPrefs;
    private final GeckoView view;
    private final Listener listener;
    private final List<Tab> tabs = new ArrayList<>();
    private int activeIndex = -1;
    private int nextIsolatedContainerNumber = 1;

    public GeckoController(Context context, GeckoView view, Listener listener) {
        this.context = context.getApplicationContext();
        this.containerPrefs = this.context.getSharedPreferences("gecko_isolated_containers", Context.MODE_PRIVATE);
        this.view = view;
        this.listener = listener;

        if (runtime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true)
                    .consoleOutput(false)
                    .aboutConfigEnabled(false)
                    .build();
            runtime = GeckoRuntime.create(this.context, settings);
        }

        cleanupOrphanedIsolatedContainers();
        createTabInContext("about:blank", true, MAIN_CONTEXT_ID, 0);
    }

    private void cleanupOrphanedIsolatedContainers() {
        Set<String> stale = new HashSet<>(containerPrefs.getStringSet("contexts", Collections.emptySet()));
        for (String contextId : stale) {
            runtime.getStorageController().clearDataForSessionContext(contextId);
        }
        if (!stale.isEmpty()) containerPrefs.edit().remove("contexts").apply();
    }

    private void registerIsolatedContext(String contextId) {
        Set<String> values = new HashSet<>(containerPrefs.getStringSet("contexts", Collections.emptySet()));
        values.add(contextId);
        containerPrefs.edit().putStringSet("contexts", values).apply();
    }

    private void unregisterIsolatedContext(String contextId) {
        Set<String> values = new HashSet<>(containerPrefs.getStringSet("contexts", Collections.emptySet()));
        if (values.remove(contextId)) {
            if (values.isEmpty()) containerPrefs.edit().remove("contexts").apply();
            else containerPrefs.edit().putStringSet("contexts", values).apply();
        }
    }

    private String newTabTitle() {
        return context.getString(R.string.browser_new_tab_title);
    }

    private GeckoSessionSettings newSessionSettings(String contextId) {
        return new GeckoSessionSettings.Builder()
                .allowJavascript(true)
                .contextId(contextId)
                .useTrackingProtection(false)
                .build();
    }

    /** Creates a session and installs delegates. openNow=false is required by onNewSession(). */
    private Tab newTabSession(boolean openNow, String contextId, int containerNumber) {
        GeckoSession session = new GeckoSession(newSessionSettings(contextId));
        Tab tab = new Tab(session, contextId, containerNumber, newTabTitle());
        tabs.add(tab);
        installDelegates(tab);
        if (openNow) session.open(runtime);
        notifyTabsChanged();
        return tab;
    }

    private void installDelegates(Tab tab) {
        GeckoSession session = tab.session;

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(GeckoSession s, String title) {
                tab.title = cleanTitle(title, tab.url);
                notifyTabsChanged();
                if (isActive(tab) && listener != null) listener.onTitle(tab.title);
            }

            @Override
            public void onFullScreen(GeckoSession s, boolean fullScreen) {
                if (isActive(tab) && listener != null) listener.onPageFullScreen(fullScreen);
            }

            @Override
            public void onCloseRequest(GeckoSession s) {
                closeTab(tab.id);
            }
        });

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession s, String url) {
                tab.loading = true;
                if (url != null && !url.isEmpty()) tab.url = url;
                selectPopupWhenReady(tab);
                notifyTabsChanged();
                if (isActive(tab) && listener != null) listener.onLoading(true);
            }

            @Override
            public void onPageStop(GeckoSession s, boolean success) {
                tab.loading = false;
                notifyTabsChanged();
                if (isActive(tab) && listener != null) listener.onLoading(false);
            }
        });

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession s, boolean value) {
                tab.canGoBack = value;
                if (isActive(tab)) notifyNavigationState(tab);
            }

            @Override
            public void onCanGoForward(GeckoSession s, boolean value) {
                tab.canGoForward = value;
                if (isActive(tab)) notifyNavigationState(tab);
            }

            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession opener, String uri) {
                // OAuth target=_blank/window.open stays inside the app and inherits
                // the opener's storage container, so the login can complete normally.
                Tab child = newTabSession(false, tab.contextId, tab.containerNumber);
                child.url = uri == null || uri.isEmpty() ? "about:blank" : uri;
                child.title = cleanTitle(null, child.url);
                child.selectWhenReady = true;
                notifyTabsChanged();
                return GeckoResult.fromValue(child.session);
            }

            @Override
            public void onLocationChange(GeckoSession s, String url,
                                         java.util.List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                         Boolean hasUserGesture) {
                tab.url = url == null || url.isEmpty() ? "about:blank" : url;
                if (tab.title == null || tab.title.isEmpty() || newTabTitle().equals(tab.title)) {
                    tab.title = cleanTitle(null, tab.url);
                }
                selectPopupWhenReady(tab);
                notifyTabsChanged();
                if (isActive(tab) && listener != null) listener.onLocation(tab.url);
            }
        });
    }

    private void selectPopupWhenReady(Tab tab) {
        if (!tab.selectWhenReady || !tab.session.isOpen()) return;
        tab.selectWhenReady = false;
        selectTab(tab.id);
    }

    private String cleanTitle(String title, String url) {
        String t = title == null ? "" : title.trim();
        if (!t.isEmpty()) return t;
        if (url == null || url.isEmpty() || "about:blank".equals(url)) return newTabTitle();
        String compact = url.replaceFirst("^https?://", "");
        int slash = compact.indexOf('/');
        if (slash > 0) compact = compact.substring(0, slash);
        return compact.isEmpty() ? newTabTitle() : compact;
    }

    private boolean isActive(Tab tab) {
        return activeIndex >= 0 && activeIndex < tabs.size() && tabs.get(activeIndex) == tab;
    }

    private int indexOf(long id) {
        for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).id == id) return i;
        return -1;
    }

    private void attach(Tab tab) {
        if (!tab.session.isOpen()) return;
        view.releaseSession();
        view.setSession(tab.session);
        if (listener != null) {
            listener.onTitle(tab.title);
            listener.onLocation(tab.url);
            listener.onLoading(tab.loading);
        }
        notifyNavigationState(tab);
    }

    private void notifyNavigationState(Tab tab) {
        if (listener != null) listener.onNavigationState(tab.canGoBack, tab.canGoForward);
    }

    private void notifyTabsChanged() {
        if (listener == null) return;
        List<TabInfo> snapshot = new ArrayList<>();
        for (Tab tab : tabs) {
            snapshot.add(new TabInfo(tab.id, tab.title, tab.url, tab.loading, tab.containerNumber));
        }
        listener.onTabsChanged(Collections.unmodifiableList(snapshot), activeIndex);
    }

    private long createTabInContext(String url, boolean select, String contextId, int containerNumber) {
        Tab tab = newTabSession(true, contextId, containerNumber);
        if (select) selectTab(tab.id);
        loadInTab(tab, url);
        return tab.id;
    }

    /** New regular tab inherits the active account container. */
    public long createTab(String url, boolean select) {
        Tab current = activeTab();
        String contextId = current == null ? MAIN_CONTEXT_ID : current.contextId;
        int container = current == null ? 0 : current.containerNumber;
        return createTabInContext(url, select, contextId, container);
    }

    public long newBlankTab(boolean select) {
        return createTab("about:blank", select);
    }

    /** Starts a separate cookie/localStorage jar for a second account. */
    public long newIsolatedBlankTab(boolean select) {
        int container = nextIsolatedContainerNumber++;
        String contextId = "cliproxy-browser-isolated-" + UUID.randomUUID();
        registerIsolatedContext(contextId);
        return createTabInContext("about:blank", select, contextId, container);
    }

    public void selectTab(long id) {
        int index = indexOf(id);
        if (index < 0) return;
        Tab tab = tabs.get(index);
        if (!tab.session.isOpen()) {
            tab.selectWhenReady = true;
            return;
        }
        activeIndex = index;
        attach(tab);
        notifyTabsChanged();
    }

    public void closeTab(long id) {
        int index = indexOf(id);
        if (index < 0) return;
        Tab closing = tabs.get(index);
        boolean wasActive = index == activeIndex;
        String contextId = closing.contextId;
        int containerNumber = closing.containerNumber;

        if (wasActive) view.releaseSession();
        if (closing.session.isOpen()) closing.session.close();
        tabs.remove(index);

        boolean contextStillUsed = false;
        for (Tab t : tabs) {
            if (t.contextId.equals(contextId)) {
                contextStillUsed = true;
                break;
            }
        }
        if (!contextStillUsed && containerNumber > 0) {
            runtime.getStorageController().clearDataForSessionContext(contextId);
            unregisterIsolatedContext(contextId);
        }

        if (tabs.isEmpty()) {
            activeIndex = -1;
            createTabInContext("about:blank", true, MAIN_CONTEXT_ID, 0);
            return;
        }

        if (wasActive) {
            int next = Math.min(index, tabs.size() - 1);
            activeIndex = -1;
            selectTab(tabs.get(next).id);
        } else {
            if (index < activeIndex) activeIndex--;
            notifyTabsChanged();
        }
    }

    private void loadInTab(Tab tab, String url) {
        String target = url == null || url.trim().isEmpty() ? "about:blank" : url.trim();
        tab.url = target;
        tab.title = cleanTitle(null, target);
        notifyTabsChanged();
        tab.session.loadUri(target);
    }

    public void load(String url) {
        Tab tab = activeTab();
        if (tab != null) loadInTab(tab, url);
    }

    public void loadInNewTab(String url) {
        createTab(url, true);
    }

    public void reload() {
        Tab tab = activeTab();
        if (tab != null) tab.session.reload();
    }

    public void back() {
        Tab tab = activeTab();
        if (tab != null && tab.canGoBack) tab.session.goBack();
    }

    public void forward() {
        Tab tab = activeTab();
        if (tab != null && tab.canGoForward) tab.session.goForward();
    }

    public boolean canGoBack() {
        Tab tab = activeTab();
        return tab != null && tab.canGoBack;
    }

    public boolean canGoForward() {
        Tab tab = activeTab();
        return tab != null && tab.canGoForward;
    }

    public String currentUrl() {
        Tab tab = activeTab();
        return tab == null ? "" : tab.url;
    }

    public int currentContainerNumber() {
        Tab tab = activeTab();
        return tab == null ? 0 : tab.containerNumber;
    }

    public int tabCount() {
        return tabs.size();
    }

    private Tab activeTab() {
        if (activeIndex < 0 || activeIndex >= tabs.size()) return null;
        return tabs.get(activeIndex);
    }

    /**
     * Clears the active account container. All tabs in that same container are
     * closed first, as required by GeckoView to prevent immediate re-creation
     * of cookies/storage, then a fresh blank tab is opened in that container.
     */
    public void clearCurrentContainer(DataCallback callback) {
        Tab active = activeTab();
        if (active == null) {
            if (callback != null) callback.onComplete(null);
            return;
        }
        String contextId = active.contextId;
        int containerNumber = active.containerNumber;

        view.releaseSession();
        for (Tab tab : new ArrayList<>(tabs)) {
            if (tab.contextId.equals(contextId)) {
                if (tab.session.isOpen()) tab.session.close();
                tabs.remove(tab);
            }
        }
        activeIndex = -1;
        runtime.getStorageController().clearDataForSessionContext(contextId);
        createTabInContext("about:blank", true, contextId, containerNumber);
        if (callback != null) callback.onComplete(null);
    }

    /** Clears network/image cache without touching login cookies. */
    public void clearCache(DataCallback callback) {
        runtime.getStorageController().clearData(StorageController.ClearFlags.ALL_CACHES)
                .accept(v -> {
                    if (callback != null) callback.onComplete(null);
                }, error -> {
                    if (callback != null) callback.onComplete(error);
                });
    }

    /** Clears login-related persistent site data across all account containers. */
    public void clearAllLoginData(DataCallback callback) {
        long flags = StorageController.ClearFlags.SITE_DATA
                | StorageController.ClearFlags.AUTH_SESSIONS;
        clearGlobalAndReset(flags, callback);
    }

    /** Clears every type of Gecko browser data across the app profile. */
    public void clearAllBrowserData(DataCallback callback) {
        clearGlobalAndReset(StorageController.ClearFlags.ALL, callback);
    }

    private void clearGlobalAndReset(long flags, DataCallback callback) {
        closeAllSessionsWithoutReplacement();
        runtime.getStorageController().clearData(flags)
                .accept(v -> {
                    containerPrefs.edit().remove("contexts").apply();
                    createTabInContext("about:blank", true, MAIN_CONTEXT_ID, 0);
                    if (callback != null) callback.onComplete(null);
                }, error -> {
                    createTabInContext("about:blank", true, MAIN_CONTEXT_ID, 0);
                    if (callback != null) callback.onComplete(error);
                });
    }

    private void closeAllSessionsWithoutReplacement() {
        view.releaseSession();
        for (Tab tab : new ArrayList<>(tabs)) {
            if (tab.session.isOpen()) tab.session.close();
        }
        tabs.clear();
        activeIndex = -1;
        notifyTabsChanged();
    }

    public void close() {
        Set<String> isolated = new HashSet<>();
        for (Tab tab : tabs) {
            if (tab.containerNumber > 0) isolated.add(tab.contextId);
        }
        closeAllSessionsWithoutReplacement();
        for (String contextId : isolated) {
            runtime.getStorageController().clearDataForSessionContext(contextId);
            unregisterIsolatedContext(contextId);
        }
    }
}
