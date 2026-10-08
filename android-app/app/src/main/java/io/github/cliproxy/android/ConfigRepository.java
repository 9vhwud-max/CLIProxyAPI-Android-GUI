package io.github.cliproxy.android;

import android.content.Context;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConfigRepository {
    private final Context context;
    private final Yaml yaml;

    public ConfigRepository(Context context) {
        this.context = context.getApplicationContext();
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        this.yaml = new Yaml(options);
    }

    public Settings loadSettings() throws IOException {
        Map<String, Object> m = loadMap();
        Settings s = new Settings();
        s.host = str(get(m, "server", "host"), "0.0.0.0");
        s.port = integer(get(m, "server", "port"), 8317);
        s.trustedProxies = stringList(get(m, "server", "trusted-proxies"));
        s.discovery = bool(get(m, "server", "discovery", "enabled"), false);
        s.commercialMode = bool(get(m, "server", "commercial-mode"), false);
        s.allowRemoteManagement = bool(get(m, "management", "allow-remote"), false);
        String storedSecret = str(get(m, "management", "secret-key"), "");
        if (storedSecret.isEmpty()) {
            context.getSharedPreferences("secrets", Context.MODE_PRIVATE).edit().remove("management_plaintext").apply();
            s.managementSecret = "";
        } else if (looksLikeBcrypt(storedSecret)) {
            String remembered = context.getSharedPreferences("secrets", Context.MODE_PRIVATE).getString("management_plaintext", "");
            s.managementSecret = remembered == null || remembered.isEmpty() ? storedSecret : remembered;
        } else {
            s.managementSecret = storedSecret;
            context.getSharedPreferences("secrets", Context.MODE_PRIVATE).edit().putString("management_plaintext", storedSecret).apply();
        }
        s.disableControlPanel = bool(get(m, "management", "disable-control-panel"), false);
        s.disablePanelAutoUpdate = bool(get(m, "management", "disable-auto-update-panel"), false);
        s.panelRepository = str(get(m, "management", "panel-github-repository"), "https://github.com/router-for-me/Cli-Proxy-API-Management-Center");
        s.apiKeys = stringList(get(m, "access", "api-keys"));
        s.routingStrategy = str(get(m, "routing", "strategy"), "round-robin");
        s.sessionAffinity = bool(get(m, "routing", "session-affinity"), false);
        s.sessionAffinityTtl = str(get(m, "routing", "session-affinity-ttl"), "1h");
        s.sessionAffinitySubagents = bool(get(m, "routing", "session-affinity-subagents"), true);
        s.forceModelPrefix = bool(get(m, "routing", "force-model-prefix"), false);
        s.requestRetry = integer(get(m, "routing", "retry", "request-retry"), 3);
        s.maxRetryCredentials = integer(get(m, "routing", "retry", "max-retry-credentials"), 0);
        s.maxRetryInterval = integer(get(m, "routing", "retry", "max-retry-interval"), 30);
        s.disableCooling = bool(get(m, "routing", "cooldown", "disable-cooling"), false);
        s.saveCooldown = bool(get(m, "routing", "cooldown", "save-cooldown-status"), false);
        s.transientCooldownSeconds = integer(get(m, "routing", "cooldown", "transient-error-cooldown-seconds"), 0);
        s.proxyUrl = str(get(m, "requests", "proxy-url"), "");
        s.passthroughHeaders = bool(get(m, "requests", "passthrough-headers"), false);
        s.nonstreamKeepalive = integer(get(m, "requests", "nonstream-keepalive-interval"), 0);
        s.streamKeepalive = integer(get(m, "requests", "streaming", "keepalive-seconds"), 0);
        s.bootstrapRetries = integer(get(m, "requests", "streaming", "bootstrap-retries"), 0);
        s.debug = bool(get(m, "observability", "logs", "debug"), false);
        s.loggingToFile = bool(get(m, "observability", "logs", "logging-to-file"), true);
        s.requestLog = bool(get(m, "observability", "logs", "request-log"), false);
        s.logsMaxTotalSizeMb = integer(get(m, "observability", "logs", "logs-max-total-size-mb"), 0);
        s.errorLogsMaxFiles = integer(get(m, "observability", "logs", "error-logs-max-files"), 10);
        s.usageStatistics = bool(get(m, "observability", "usage", "usage-statistics-enabled"), true);
        return s;
    }

    public void saveSettings(Settings s) throws IOException {
        Map<String, Object> m = loadMap();
        set(m, s.host, "server", "host");
        set(m, s.port, "server", "port");
        set(m, new ArrayList<>(s.trustedProxies), "server", "trusted-proxies");
        set(m, s.discovery, "server", "discovery", "enabled");
        set(m, s.commercialMode, "server", "commercial-mode");
        set(m, s.allowRemoteManagement, "management", "allow-remote");
        set(m, s.managementSecret, "management", "secret-key");
        if (s.managementSecret == null || s.managementSecret.isEmpty()) {
            context.getSharedPreferences("secrets", Context.MODE_PRIVATE).edit().remove("management_plaintext").apply();
        } else if (!looksLikeBcrypt(s.managementSecret)) {
            context.getSharedPreferences("secrets", Context.MODE_PRIVATE).edit().putString("management_plaintext", s.managementSecret).apply();
        }
        set(m, s.disableControlPanel, "management", "disable-control-panel");
        set(m, s.disablePanelAutoUpdate, "management", "disable-auto-update-panel");
        set(m, s.panelRepository, "management", "panel-github-repository");
        set(m, new ArrayList<>(s.apiKeys), "access", "api-keys");
        set(m, s.routingStrategy, "routing", "strategy");
        set(m, s.sessionAffinity, "routing", "session-affinity");
        set(m, s.sessionAffinityTtl, "routing", "session-affinity-ttl");
        set(m, s.sessionAffinitySubagents, "routing", "session-affinity-subagents");
        set(m, s.forceModelPrefix, "routing", "force-model-prefix");
        set(m, s.requestRetry, "routing", "retry", "request-retry");
        set(m, s.maxRetryCredentials, "routing", "retry", "max-retry-credentials");
        set(m, s.maxRetryInterval, "routing", "retry", "max-retry-interval");
        set(m, s.disableCooling, "routing", "cooldown", "disable-cooling");
        set(m, s.saveCooldown, "routing", "cooldown", "save-cooldown-status");
        set(m, s.transientCooldownSeconds, "routing", "cooldown", "transient-error-cooldown-seconds");
        set(m, s.proxyUrl, "requests", "proxy-url");
        set(m, s.passthroughHeaders, "requests", "passthrough-headers");
        set(m, s.nonstreamKeepalive, "requests", "nonstream-keepalive-interval");
        set(m, s.streamKeepalive, "requests", "streaming", "keepalive-seconds");
        set(m, s.bootstrapRetries, "requests", "streaming", "bootstrap-retries");
        set(m, AppPaths.authDir(context).getAbsolutePath(), "oauth", "auth-dir");
        set(m, s.debug, "observability", "logs", "debug");
        set(m, s.loggingToFile, "observability", "logs", "logging-to-file");
        set(m, s.requestLog, "observability", "logs", "request-log");
        set(m, s.logsMaxTotalSizeMb, "observability", "logs", "logs-max-total-size-mb");
        set(m, s.errorLogsMaxFiles, "observability", "logs", "error-logs-max-files");
        set(m, s.usageStatistics, "observability", "usage", "usage-statistics-enabled");
        writeMap(m);
    }

    public String readRaw() throws IOException {
        try (FileInputStream in = new FileInputStream(AppPaths.config(context)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    public void writeRaw(String raw) throws IOException {
        Object parsed = yaml.load(raw);
        if (!(parsed instanceof Map)) throw new IOException("YAML root must be a mapping/object");
        try (FileOutputStream out = new FileOutputStream(AppPaths.config(context))) {
            out.write(raw.getBytes(StandardCharsets.UTF_8));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadMap() throws IOException {
        try (FileInputStream in = new FileInputStream(AppPaths.config(context))) {
            Object value = yaml.load(in);
            if (value == null) return new LinkedHashMap<>();
            if (!(value instanceof Map)) throw new IOException("config.yaml root is not a YAML map");
            return (Map<String, Object>) value;
        }
    }

    private void writeMap(Map<String, Object> map) throws IOException {
        try (FileOutputStream out = new FileOutputStream(AppPaths.config(context))) {
            out.write(yaml.dump(map).getBytes(StandardCharsets.UTF_8));
        }
    }

    @SuppressWarnings("unchecked")
    private static Object get(Map<String, Object> map, String... path) {
        Object current = map;
        for (String key : path) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(key);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static void set(Map<String, Object> map, Object value, String... path) {
        Map<String, Object> current = map;
        for (int i = 0; i < path.length - 1; i++) {
            Object child = current.get(path[i]);
            if (!(child instanceof Map)) {
                child = new LinkedHashMap<String, Object>();
                current.put(path[i], child);
            }
            current = (Map<String, Object>) child;
        }
        current.put(path[path.length - 1], value);
    }


    private static boolean looksLikeBcrypt(String value) {
        return value != null && (value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$"));
    }

    private static String str(Object o, String fallback) { return o == null ? fallback : String.valueOf(o); }
    private static boolean bool(Object o, boolean fallback) { return o instanceof Boolean ? (Boolean)o : o == null ? fallback : Boolean.parseBoolean(String.valueOf(o)); }
    private static int integer(Object o, int fallback) {
        if (o instanceof Number) return ((Number)o).intValue();
        try { return o == null ? fallback : Integer.parseInt(String.valueOf(o)); } catch (NumberFormatException e) { return fallback; }
    }
    private static List<String> stringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?>) for (Object v : (List<?>)o) if (v != null) out.add(String.valueOf(v));
        return out;
    }

    public static final class Settings {
        public String host = "0.0.0.0";
        public int port = 8317;
        public List<String> trustedProxies = new ArrayList<>();
        public boolean discovery;
        public boolean commercialMode;
        public boolean allowRemoteManagement;
        public String managementSecret = "";
        public boolean disableControlPanel;
        public boolean disablePanelAutoUpdate;
        public String panelRepository = "https://github.com/router-for-me/Cli-Proxy-API-Management-Center";
        public List<String> apiKeys = new ArrayList<>();
        public String routingStrategy = "round-robin";
        public boolean sessionAffinity;
        public String sessionAffinityTtl = "1h";
        public boolean sessionAffinitySubagents = true;
        public boolean forceModelPrefix;
        public int requestRetry = 3;
        public int maxRetryCredentials;
        public int maxRetryInterval = 30;
        public boolean disableCooling;
        public boolean saveCooldown;
        public int transientCooldownSeconds;
        public String proxyUrl = "";
        public boolean passthroughHeaders;
        public int nonstreamKeepalive;
        public int streamKeepalive;
        public int bootstrapRetries;
        public boolean debug;
        public boolean loggingToFile = true;
        public boolean requestLog;
        public int logsMaxTotalSizeMb;
        public int errorLogsMaxFiles = 10;
        public boolean usageStatistics = true;
    }
}
