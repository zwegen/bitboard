package com.zwegen.bitboard;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Shader;
import android.graphics.RenderEffect;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.transition.AutoTransition;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "bitaxe_prefs";
    private static final String KEY_IPS = "ips";
    private static final String KEY_REFRESH_INTERVAL_MS = "refresh_interval_ms";
    private static final String KEY_CARDS_EXPANDED = "cards_expanded";
    private static final String KEY_ONLINE_ONLY = "online_only";
    private static final String KEY_BACKUP_TREE_URI = "backup_tree_uri";
    private static final String KEY_LAST_DEVICE_PREFIX = "last_device_";
    private static final long RESUME_STALE_MS = 60_000L;
    private static final long DEFAULT_REFRESH_INTERVAL_MS = 120000L;
    private static final long REFRESH_MANUAL = 0L;
    private static final int REQUEST_IMPORT_BACKUP = 7102;
    private static final int REQUEST_BACKUP_FOLDER = 7103;
    private static final int BACKUP_ACTION_NONE = 0;
    private static final int BACKUP_ACTION_EXPORT = 1;
    private static final int BACKUP_ACTION_IMPORT = 2;
    private static final int BG = Color.rgb(7, 17, 31);
    private static final int CARD = Color.rgb(14, 28, 48);
    private static final int CARD2 = Color.rgb(18, 36, 61);
    private static final int TEXT = Color.rgb(232, 240, 255);
    private static final int MUTED = Color.rgb(139, 157, 185);
    private static final int ACCENT = Color.rgb(56, 189, 248);
    private static final int ACTION_BLUE = Color.rgb(37, 99, 235);
    private static final int ACTION_AMBER = Color.rgb(245, 158, 11);
    private static final int ACTION_DISABLED = Color.rgb(71, 85, 105);
    private static final int GOOD = Color.rgb(34, 197, 94);
    private static final int BORDER = Color.rgb(75, 82, 94);
    private static final int ONLINE_BORDER = Color.rgb(21, 87, 45);
    private static final int UPDATE_BORDER = Color.rgb(74, 222, 128);
    private static final String ZAPSTORE_URL = "https://zapstore.dev/apps/com.zwegen.bitboard";
    private static final String GITHUB_URL = "https://github.com/zwegen/bitboard";
    private static final String DONATION_URL = "https://coinos.io/zwgn";
    private static final long UI_ANIMATION_MS = 30L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(12);
    private LinearLayout deviceList;
    private ProgressBar emptyLoadingSpinner;
    private SwipeRefreshLayout swipeRefresh;
    private TextView summaryText;
    private TextView chanceText;
    private SharedPreferences prefs;
    private PopupWindow activeOverlayPopup;
    private FrameLayout activeOverlay;
    private View activeOverlayCard;
    private double networkHashrateEh = 0;
    private long networkHashrateFetchedAt = 0;
    private int pendingBackupAction = BACKUP_ACTION_NONE;
    private final Map<String, CardHolder> cards = new HashMap<>();
    private final Map<String, Device> currentDevices = new HashMap<>();
    private long lastDeviceRefreshAt = 0;
    private boolean refreshRunning = false;
    private boolean forceLargeRefreshSpinner = false;
    private boolean suppressLargeRefreshSpinner = false;
    private int nextRefreshIndex = 0;
    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            refreshNextDevice();
        }
    };
    private final Runnable footerTimeRunnable = new Runnable() {
        @Override public void run() {
            updateFooterTimes();
            handler.postDelayed(this, 5000L);
        }
    };
    private final Runnable headerSummaryRunnable = new Runnable() {
        @Override public void run() {
            updateSummaryFromCurrentDevices(loadIps());
            handler.postDelayed(this, 21000L);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshRunnable);
        if (cards.isEmpty()) renderCachedDevices();
        handler.removeCallbacks(footerTimeRunnable);
        handler.post(footerTimeRunnable);
        handler.removeCallbacks(headerSummaryRunnable);
        handler.postDelayed(headerSummaryRunnable, 21000L);
        if (shouldRefreshOnResume()) {
            refresh(false);
        } else {
            scheduleNextRefresh();
        }
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        handler.removeCallbacks(footerTimeRunnable);
        handler.removeCallbacks(headerSummaryRunnable);
        super.onPause();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_IMPORT_BACKUP) {
            importBackupFromUri(uri);
        } else if (requestCode == REQUEST_BACKUP_FOLDER) {
            saveBackupFolder(uri, data.getFlags());
            if (pendingBackupAction == BACKUP_ACTION_EXPORT) {
                writeBackupToFolder(uri);
            } else if (pendingBackupAction == BACKUP_ACTION_IMPORT) {
                openBackupImportPicker(uri);
            }
            pendingBackupAction = BACKUP_ACTION_NONE;
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);

        int pad = dp(14);

        LinearLayout fixedHeader = new LinearLayout(this);
        fixedHeader.setOrientation(LinearLayout.VERTICAL);
        fixedHeader.setPadding(pad, 0, pad, 0);
        fixedHeader.setBackgroundColor(BG);
        root.addView(fixedHeader, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        fixedHeader.addView(header, new LinearLayout.LayoutParams(-1, dp(42)));

        TextView leftSpacer = text("", 28, TEXT, true);
        header.addView(leftSpacer, new LinearLayout.LayoutParams(dp(48), -1));

        TextView title = text("BitBoard", 28, TEXT, true);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, -1, 1));

        TextView menu = text("☰", 28, TEXT, false);
        menu.setGravity(Gravity.CENTER);
        menu.setPadding(0, 0, 0, 0);
        menu.setOnClickListener(this::showMenu);
        header.addView(menu, new LinearLayout.LayoutParams(dp(48), -1));

        summaryText = text("", 14, MUTED, false);
        summaryText.setGravity(Gravity.CENTER);
        summaryText.setPadding(0, 0, 0, dp(2));
        fixedHeader.addView(summaryText);

        chanceText = text("", 14, TEXT, true);
        chanceText.setGravity(Gravity.CENTER);
        chanceText.setPadding(0, 0, 0, dp(10));
        fixedHeader.addView(chanceText);

        swipeRefresh = new SwipeRefreshLayout(this);
        swipeRefresh.setColorSchemeColors(ACCENT, GOOD);
        swipeRefresh.setOnRefreshListener(this::refreshFromSwipe);
        root.addView(swipeRefresh, new LinearLayout.LayoutParams(-1, 0, 1));

        FrameLayout scrollHost = new FrameLayout(this);
        swipeRefresh.addView(scrollHost, new SwipeRefreshLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scrollHost.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        swipeRefresh.setOnChildScrollUpCallback((parent, child) -> scroll.canScrollVertically(-1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, dp(4), pad, dp(8));
        scroll.addView(content);

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        content.addView(deviceList);

        emptyLoadingSpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        emptyLoadingSpinner.setIndeterminate(true);
        emptyLoadingSpinner.setVisibility(View.GONE);
        FrameLayout.LayoutParams spinnerLp = new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER);
        scrollHost.addView(emptyLoadingSpinner, spinnerLp);

    }

    private void refresh() {
        refresh(true);
    }

    private void refreshFromSwipe() {
        refresh(true, true);
    }

    private long getRefreshIntervalMs() {
        long interval = prefs.getLong(KEY_REFRESH_INTERVAL_MS, DEFAULT_REFRESH_INTERVAL_MS);
        if (interval == 30000L || interval == 300000L) return DEFAULT_REFRESH_INTERVAL_MS;
        return interval;
    }

    private void setRefreshIntervalMs(long intervalMs) {
        prefs.edit().putLong(KEY_REFRESH_INTERVAL_MS, intervalMs).apply();
        handler.removeCallbacks(refreshRunnable);
        nextRefreshIndex = 0;
        if (intervalMs != REFRESH_MANUAL) handler.post(refreshRunnable);
    }

    private boolean isOnlineOnly() {
        return prefs.getBoolean(KEY_ONLINE_ONLY, false);
    }

    private void setOnlineOnly(boolean enabled) {
        prefs.edit().putBoolean(KEY_ONLINE_ONLY, enabled).apply();
        applyOnlineOnlyVisibility();
    }

    private void scheduleNextRefresh() {
        long interval = getRefreshIntervalMs();
        List<String> ips = loadIps();
        if (interval > 0 && !ips.isEmpty()) handler.postDelayed(refreshRunnable, Math.max(1000L, interval / ips.size()));
    }

    private void refresh(boolean showUpdating) {
        refresh(showUpdating, false);
    }

    private void refresh(boolean showUpdating, boolean fromSwipe) {
        handler.removeCallbacks(refreshRunnable);
        if (refreshRunning) {
            forceLargeRefreshSpinner = false;
            suppressLargeRefreshSpinner = false;
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            updateEmptyLoadingSpinner();
            return;
        }
        List<String> ips = loadIps();
        if (ips.isEmpty()) {
            forceLargeRefreshSpinner = false;
            suppressLargeRefreshSpinner = false;
            clearDeviceUi();
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            return;
        }
        refreshRunning = true;
        suppressLargeRefreshSpinner = fromSwipe;
        updateEmptyLoadingSpinner();
        if (showUpdating && !cards.isEmpty()) summaryText.setText("Updating...");
        executor.execute(() -> {
            List<Device> devices = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch latch = new CountDownLatch(ips.size());
            for (String ip : ips) {
                executor.execute(() -> {
                    try {
                        devices.add(fetchDevice(ip));
                    } finally {
                        latch.countDown();
                    }
                });
            }
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            handler.post(() -> {
                refreshRunning = false;
                forceLargeRefreshSpinner = false;
                suppressLargeRefreshSpinner = false;
                nextRefreshIndex = 0;
                lastDeviceRefreshAt = System.currentTimeMillis();
                renderDevices(devices, ips, true);
                updateEmptyLoadingSpinner();
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                scheduleNextRefresh();
            });
        });
    }

    private void refreshNextDevice() {
        if (refreshRunning) { scheduleNextRefresh(); return; }
        List<String> ips = loadIps();
        if (ips.isEmpty()) {
            clearDeviceUi();
            return;
        }
        int index = nextRefreshIndex % ips.size();
        String ip = ips.get(index);
        refreshRunning = true;
        updateEmptyLoadingSpinner();
        executor.execute(() -> {
            Device fetched = fetchDevice(ip);
            handler.post(() -> {
                refreshRunning = false;
                lastDeviceRefreshAt = System.currentTimeMillis();
                updateSingleDeviceCard(fetched, ips);
                updateEmptyLoadingSpinner();
                nextRefreshIndex = (index + 1) % ips.size();
                updateSummaryFromCurrentDevices(ips);
                scheduleNextRefresh();
            });
        });
    }

    private void renderCachedDevices() {
        List<String> ips = loadIps();
        if (ips.isEmpty()) {
            clearDeviceUi();
            return;
        }
        List<Device> devices = new ArrayList<>();
        for (String ip : ips) {
            Device d = loadLastDevice(ip);
            if (d == null) d = placeholderDevice(ip);
            d.online = false;
            devices.add(d);
        }
        renderDevices(devices, ips, false);
    }

    private boolean shouldRefreshOnResume() {
        List<String> ips = loadIps();
        if (ips.isEmpty()) return false;
        return lastDeviceRefreshAt <= 0 || System.currentTimeMillis() - lastDeviceRefreshAt >= RESUME_STALE_MS;
    }

    private void clearDeviceUi() {
        cards.clear();
        currentDevices.clear();
        deviceList.removeAllViews();
        summaryText.setText("No devices yet. Open the menu and add an IP.");
        chanceText.setText("");
        updateEmptyLoadingSpinner();
    }

    private void renderDevices(List<Device> devices, List<String> ips, boolean updateSummary) {
        Map<String, Device> byIp = new HashMap<>();
        for (Device d : devices) byIp.put(d.ip, d);

        List<String> onlineOrder = new ArrayList<>();
        List<String> offlineOrder = new ArrayList<>();
        for (String ip : ips) {
            Device d = byIp.get(ip);
            CardHolder h = cards.get(ip);
            if (h == null) {
                h = addDeviceCard(d != null ? d : placeholderDevice(ip));
                cards.put(ip, h);
            }
            Device display = prepareDisplayDevice(ip, d);
            currentDevices.put(ip, display);
            updateCard(h, display);
            setCardOnline(h, display.online);
            applyCardVisibility(h, display);
            if (display.online) onlineOrder.add(ip); else offlineOrder.add(ip);
        }

        List<String> remove = new ArrayList<>();
        for (String ip : cards.keySet()) if (!ips.contains(ip)) remove.add(ip);
        for (String ip : remove) {
            CardHolder h = cards.remove(ip);
            currentDevices.remove(ip);
            if (h != null) deviceList.removeView(h.card);
        }

        reorderCards(onlineOrder, offlineOrder);
        if (updateSummary) updateSummaryFromCurrentDevices(ips);
        updateEmptyLoadingSpinner();
    }

    private void openDeviceInBrowser(CardHolder h) {
        if (h == null) return;
        String ip = h.ip == null || h.ip.isEmpty()
            ? (h.currentDevice != null ? h.currentDevice.ip : "")
            : h.ip;
        if (ip == null || ip.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://" + cleanIp(ip))));
        } catch (Exception e) {
            Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshDeviceNow(CardHolder h) {
        if (h == null) return;
        String ip = h.ip == null || h.ip.isEmpty()
            ? (h.currentDevice != null ? h.currentDevice.ip : "")
            : h.ip;
        if (ip == null || ip.isEmpty()) return;
        handler.removeCallbacks(refreshRunnable);
        if (refreshRunning) {
            Toast.makeText(this, "Refresh already running", Toast.LENGTH_SHORT).show();
            if (getRefreshIntervalMs() != REFRESH_MANUAL) scheduleNextRefresh();
            return;
        }
        refreshRunning = true;
        updateEmptyLoadingSpinner();
        executor.execute(() -> {
            Device fetched = fetchDevice(ip);
            handler.post(() -> {
                refreshRunning = false;
                List<String> ips = loadIps();
                lastDeviceRefreshAt = System.currentTimeMillis();
                updateSingleDeviceCard(fetched, ips);
                updateSummaryFromCurrentDevices(ips);
                updateEmptyLoadingSpinner();
                if (getRefreshIntervalMs() != REFRESH_MANUAL) scheduleNextRefresh();
            });
        });
    }

    private void updateSingleDeviceCard(Device fetched, List<String> ips) {
        if (fetched == null) return;
        String ip = fetched.ip;
        CardHolder h = cards.get(ip);
        if (h == null) {
            h = addDeviceCard(fetched);
            cards.put(ip, h);
        }
        Device display = prepareDisplayDevice(ip, fetched);
        currentDevices.put(ip, display);
        updateCard(h, display);
        setCardOnline(h, display.online);
        applyCardVisibility(h, display);
        if (fetched.online) pulseUpdatedCard(h);

        List<String> remove = new ArrayList<>();
        for (String cardIp : cards.keySet()) if (!ips.contains(cardIp)) remove.add(cardIp);
        for (String cardIp : remove) {
            CardHolder old = cards.remove(cardIp);
            currentDevices.remove(cardIp);
            if (old != null) deviceList.removeView(old.card);
        }
        reorderCardsByOnlineState(ips);
        updateEmptyLoadingSpinner();
    }

    private Device prepareDisplayDevice(String ip, Device fetched) {
        if (fetched != null && fetched.online) {
            saveLastDevice(fetched);
            return fetched;
        }
        Device last = loadLastDevice(ip);
        if (last == null) last = placeholderDevice(ip);
        last.online = false;
        return last;
    }

    private void updateSummaryFromCurrentDevices(List<String> ips) {
        int online = 0;
        double hash = 0, power = 0;
        for (String ip : ips) {
            Device d = currentDevices.get(ip);
            if (d != null && d.online) {
                online++;
                hash += d.hashRate;
                power += d.power;
            }
        }
        setSummaryText(online, ips.size(), hash, power);
        updateChanceText(hash);
    }

    private Device placeholderDevice(String ip) {
        Device d = new Device();
        d.ip = ip;
        d.name = ip;
        d.online = false;
        return d;
    }

    private void setCardOnline(CardHolder h, boolean online) {
        if (h == null || h.card == null) return;
        h.card.setBackground(round(online ? ONLINE_BORDER : BORDER, dp(20), 0));
        if (h.body != null) h.body.setAlpha(online ? 1.0f : 0.62f);
        if (!online && h.name != null) h.name.setTextColor(MUTED);
    }

    private void applyCardVisibility(CardHolder h, Device d) {
        if (h == null || h.card == null) return;
        boolean hide = isOnlineOnly() && (d == null || !d.online);
        h.card.setVisibility(hide ? View.GONE : View.VISIBLE);
    }

    private void applyOnlineOnlyVisibility() {
        for (CardHolder h : cards.values()) {
            if (h == null) continue;
            applyCardVisibility(h, h.currentDevice);
        }
        updateSummaryFromCurrentDevices(loadIps());
        updateEmptyLoadingSpinner();
    }

    private void updateEmptyLoadingSpinner() {
        if (emptyLoadingSpinner == null || deviceList == null) return;
        boolean hasVisibleCard = false;
        for (int i = 0; i < deviceList.getChildCount(); i++) {
            View child = deviceList.getChildAt(i);
            if (child != null && child.getVisibility() == View.VISIBLE) {
                hasVisibleCard = true;
                break;
            }
        }
        boolean show = refreshRunning && !suppressLargeRefreshSpinner && (forceLargeRefreshSpinner || !hasVisibleCard) && !loadIps().isEmpty();
        emptyLoadingSpinner.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void pulseUpdatedCard(CardHolder h) {
        if (h == null || h.card == null) return;
        h.card.setBackground(round(UPDATE_BORDER, dp(20), 0));
        handler.postDelayed(() -> {
            Device d = h.currentDevice;
            setCardOnline(h, d != null && d.online);
            if (d != null && h.name != null) h.name.setTextColor(getDeviceTitleColor(d));
        }, 1000);
    }

    private void reorderCardsByOnlineState(List<String> order) {
        List<String> onlineOrder = new ArrayList<>();
        List<String> offlineOrder = new ArrayList<>();
        for (String ip : order) {
            Device d = currentDevices.get(ip);
            if (d != null && d.online) onlineOrder.add(ip); else offlineOrder.add(ip);
        }
        reorderCards(onlineOrder, offlineOrder);
    }

    private void reorderCards(List<String> onlineOrder, List<String> offlineOrder) {
        for (String ip : onlineOrder) reattachCard(ip);
        for (String ip : offlineOrder) reattachCard(ip);
    }

    private void reattachCard(String ip) {
        CardHolder h = cards.get(ip);
        if (h != null && h.card.getParent() == deviceList) {
            deviceList.removeView(h.card);
            deviceList.addView(h.card);
        }
    }

    private void setSummaryText(int online, int total, double hash, double power) {
        String summary = online + "/" + total + " online";
        if (online > 0) {
            summary += " · " + fmtTopHash(hash) + " · " + (power > 0 ? zer(power) + " W" : "–");
            summaryText.setTextColor(ACCENT);
        } else {
            summaryText.setTextColor(MUTED);
        }
        summaryText.setText(summary);
    }

    private void updateChanceText(double totalHashGh) {
        if (totalHashGh < 0.1) {
            chanceText.setText("No solo chance data");
            return;
        }
        if (networkHashrateEh > 0) {
            chanceText.setText(formatSoloChance(totalHashGh, networkHashrateEh));
            refreshNetworkHashrateIfNeeded(false);
        } else {
            chanceText.setText("Loading chance...");
            refreshNetworkHashrateIfNeeded(true);
        }
    }

    private void refreshNetworkHashrateIfNeeded(boolean updateAfterFetch) {
        long now = System.currentTimeMillis();
        if (networkHashrateEh > 0 && now - networkHashrateFetchedAt < 60L * 60L * 1000L) return;
        executor.execute(() -> {
            double eh = fetchNetworkHashrateEh();
            if (eh > 0) {
                networkHashrateEh = eh;
                networkHashrateFetchedAt = System.currentTimeMillis();
                if (updateAfterFetch) handler.post(this::refresh);
            }
        });
    }

    private double fetchNetworkHashrateEh() {
        HttpURLConnection c = null;
        try {
            URL url = new URL("https://mempool.space/api/v1/mining/hashrate/1w");
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("Accept", "application/json");
            StringBuilder sb = new StringBuilder(); String line;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                while ((line = br.readLine()) != null) sb.append(line);
            }
            Matcher m = Pattern.compile("\\\"currentHashrate\\\"\\s*:\\s*([0-9.Ee+-]+)").matcher(sb.toString());
            if (m.find()) {
                double hashesPerSecond = Double.parseDouble(m.group(1));
                return hashesPerSecond / 1_000_000_000_000_000_000.0;
            }
        } catch (Exception ignored) {
            return 0;
        } finally {
            if (c != null) c.disconnect();
        }
        return 0;
    }

    private String formatSoloChance(double totalHashGh, double networkEh) {
        double networkGh = networkEh * 1_000_000_000.0;
        double chance = (totalHashGh / networkGh) * 1008.0;
        if (chance <= 0) return "No solo chance data";
        long weeks = (long)(1.0 / chance);
        return "1:" + formatLong(weeks) + " / Week";
    }

    private Device fetchDevice(String ip) {
        Device d = new Device(); d.ip = ip;
        long start = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            URL url = new URL("http://" + ip + "/api/system/info");
            c = (HttpURLConnection) url.openConnection();
            c.setUseCaches(false);
            int timeoutMs = isVpnActive() ? 6000 : 4000;
            c.setConnectTimeout(timeoutMs); c.setReadTimeout(timeoutMs);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Cache-Control", "no-cache");
            c.setRequestProperty("Connection", "close");
            int code = c.getResponseCode();
            InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (stream == null) throw new Exception("No response stream");
            BufferedReader br = new BufferedReader(new InputStreamReader(stream));
            StringBuilder sb = new StringBuilder(); String line;
            try {
                while ((line = br.readLine()) != null) sb.append(line);
            } finally {
                br.close();
            }
            JSONObject j = new JSONObject(sb.toString());
            d.online = code >= 200 && code < 300;
            d.updatedAt = d.online ? System.currentTimeMillis() : 0;
            d.latency = System.currentTimeMillis() - start;
            d.name = str(j, "hostname", ip);
            d.model = str(j, "deviceModel", str(j, "ASICModel", ""));
            d.axeOSVersion = str(j, "axeOSVersion", "");
            d.firmware = d.axeOSVersion.isEmpty() ? str(j, "version", "") : d.axeOSVersion;
            d.chip = str(j, "ASICModel", "");
            d.miningPaused = j.optBoolean("miningPaused", false);
            d.hashRate = num(j, "hashRate");
            d.hashRate10m = numAny(j, "hashRate_10m", "hashRate10m", "hashRate10min", "hashRate10Min", "hashRateAvg10m", "hashRateAvg10min");
            d.responseTime = numAny(j, "responseTime", "lastpingrtt");
            d.block = raw(j, "blockFound", raw(j, "foundBlocks", raw(j, "totalFoundBlocks", raw(j, "blocks", "–"))));
            d.bestDiff = raw(j, "bestDiff", "–");
            d.sessionDiff = raw(j, "bestSessionDiff", "–");
            d.temp = numAny(j, "temp", "temperature", "asicTemp");
            d.vrTemp = numAny(j, "vrTemp", "vrTempInt");
            d.power = num(j, "power");
            double voltage = numAny(j, "voltage", "inputVoltage", "voltageActual", "asicVoltage");
            d.voltage = voltage > 1000 ? voltage / 1000 : voltage;
            d.frequency = num(j, "frequency");
            d.coreMv = numAny(j, "coreVoltageActual", "coreVoltage");
            d.sharesAccepted = (long) numAny(j, "sharesAccepted", "accepted");
            d.sharesRejected = (long) numAny(j, "sharesRejected", "rejected");
            d.poolDiff = raw(j, "poolDifficulty", raw(j, "stratumDifficulty", "–"));
            d.fanSpeed = numAny(j, "fanspeed", "fanSpeed", "manualFanSpeed");
            d.wifi = wifiPercent(num(j, "wifiRSSI"));
            d.uptime = fmtUptime((long) numAny(j, "uptimeSeconds", "uptime", "upTime"));
        } catch (Exception e) {
            d.online = false; d.latency = System.currentTimeMillis() - start;
        } finally { if (c != null) c.disconnect(); }
        return d;
    }

    private boolean isVpnActive() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (Exception ignored) {
            return false;
        }
    }

    private CardHolder addDeviceCard(Device d) {
        CardHolder h = new CardHolder();
        LinearLayout card = new LinearLayout(this);
        h.card = card;
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackground(round(BORDER, dp(20), 0));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.setMargins(0, 0, 0, dp(12));
        deviceList.addView(card, clp);


        LinearLayout body = new LinearLayout(this);
        h.body = body;
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(12), dp(14), dp(8));
        body.setBackground(round(CARD, dp(20), 0));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, -2, 1);
        blp.setMargins(dp(1), dp(1), dp(1), dp(1));
        card.addView(body, blp);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(head, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout titleBox = new LinearLayout(this); titleBox.setOrientation(LinearLayout.VERTICAL);
        head.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));
        h.name = text("", 20, TEXT, true);
        h.name.setGravity(Gravity.START);
        h.sub = text("", 12, MUTED, false);
        h.sub.setGravity(Gravity.START);
        titleBox.addView(h.name);
        titleBox.addView(h.sub);
        h.pauseResume = iconButton(R.drawable.ic_card_pause, ACTION_AMBER, "Pause mining");
        h.pauseResume.setOnClickListener(v -> togglePauseResume(h));
        head.addView(h.pauseResume, deviceIconButtonLp());

        ImageButton open = iconButton(R.drawable.ic_open_browser, Color.rgb(100, 116, 139), "Open");
        open.setOnClickListener(v -> openDeviceInBrowser(h));
        head.addView(open, deviceIconButtonLp());

        ImageButton refresh = iconButton(R.drawable.ic_card_refresh, ACTION_BLUE, "Refresh");
        refresh.setOnClickListener(v -> refreshDeviceNow(h));
        head.addView(refresh, deviceIconButtonLp());

        ImageButton restart = iconButton(R.drawable.ic_card_restart, Color.rgb(220, 38, 38), "Restart");
        restart.setOnClickListener(v -> confirmRestart(h));
        head.addView(restart, deviceIconButtonLp());

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, dp(12), 0, 0);
        body.addView(grid);
        h.values = new TextView[16];
        h.labels = new TextView[16];
        h.rows = new LinearLayout[8];
        int i = 0;
        i = addMetricRow(grid, i, h, "Hashrate", "Session Difficulty");
        i = addMetricRow(grid, i, h, "Temperature", "Power");
        i = addMetricRow(grid, i, h, "Shares", "Response Time");
        i = addMetricRow(grid, i, h, "Hashrate 10 min", "Best Difficulty");
        i = addMetricRow(grid, i, h, "VR Temperature", "Voltage");
        i = addMetricRow(grid, i, h, "Frequency", "Core Voltage");
        i = addMetricRow(grid, i, h, "Pool Difficulty", "Fan Speed");
        addMetricRow(grid, i, h, "Wi-Fi Signal", "Uptime");
        h.expanded = areCardsExpandedByDefault();
        applyCompactMode(h, false);
        card.setOnClickListener(v -> toggleCompactMode(h));

        h.footer = text("", 11, MUTED, false);
        h.footer.setGravity(Gravity.CENTER);
        h.footer.setPadding(0, 0, 0, 0);
        body.addView(h.footer, new LinearLayout.LayoutParams(-1, -2));
        return h;
    }

    private void updateCard(CardHolder h, Device d) {
        h.currentDevice = d;
        h.ip = d.ip;
        h.deviceName = d.name;
        h.name.setText(d.name);
        h.sub.setText(formatBlocksFound(d.block));
        h.footer.setText(formatFooter(d));
        setCardOnline(h, d.online);
        h.name.setTextColor(getDeviceTitleColor(d));
        updatePauseResumeButton(h);
        String sharesLabel = sharesLabel(h, d);
        String[] labels;
        String[] vals;
        if (h.expanded) {
            labels = new String[] {
                "Hashrate", "Hashrate 10 min",
                "Best Difficulty", "Session Difficulty",
                "Temperature", "VR Temperature",
                "Power", "Voltage",
                "Frequency", "Core Voltage",
                sharesLabel, "Pool Difficulty",
                "Response Time", "Wi-Fi Signal",
                "Fan Speed", "Uptime"
            };
            vals = new String[] {
                fmtHash(d.hashRate), d.hashRate10m > 0 ? fmtHash(d.hashRate10m) : "–",
                fmtDiff(d.bestDiff), fmtDiff(d.sessionDiff),
                d.temp > 0 ? one(d.temp) + " °C" : "–", d.vrTemp > 0 ? one(d.vrTemp) + " °C" : "–",
                d.power > 0 ? one(d.power) + " W" : "–", d.voltage > 0 ? String.format(Locale.US, "%.2f V", d.voltage) : "–",
                d.frequency > 0 ? zer(d.frequency) + " MHz" : "–", d.coreMv > 0 ? zer(d.coreMv) + " mV" : "–",
                d.sharesAccepted + "/" + d.sharesRejected, fmtDiff(d.poolDiff),
                d.responseTime > 0 ? String.format(Locale.US, "%.2f ms", d.responseTime) : "–", d.wifi,
                d.fanSpeed > 0 ? zer(d.fanSpeed) + "%" : "–", d.uptime
            };
        } else {
            labels = new String[] {
                "Hashrate", "Session Difficulty",
                "Temperature", "Power",
                sharesLabel, "Response Time",
                "Hashrate 10 min", "Best Difficulty",
                "VR Temperature", "Voltage",
                "Frequency", "Core Voltage",
                "Pool Difficulty", "Fan Speed",
                "Wi-Fi Signal", "Uptime"
            };
            vals = new String[] {
                fmtHash(d.hashRate), fmtDiff(d.sessionDiff),
                d.temp > 0 ? one(d.temp) + " °C" : "–", d.power > 0 ? one(d.power) + " W" : "–",
                d.sharesAccepted + "/" + d.sharesRejected, d.responseTime > 0 ? String.format(Locale.US, "%.2f ms", d.responseTime) : "–",
                d.hashRate10m > 0 ? fmtHash(d.hashRate10m) : "–", fmtDiff(d.bestDiff),
                d.vrTemp > 0 ? one(d.vrTemp) + " °C" : "–", d.voltage > 0 ? String.format(Locale.US, "%.2f V", d.voltage) : "–",
                d.frequency > 0 ? zer(d.frequency) + " MHz" : "–", d.coreMv > 0 ? zer(d.coreMv) + " mV" : "–",
                fmtDiff(d.poolDiff), d.fanSpeed > 0 ? zer(d.fanSpeed) + "%" : "–",
                d.wifi, d.uptime
            };
        }
        for (int i = 0; i < h.values.length && i < vals.length; i++) {
            if (h.labels != null && i < h.labels.length && h.labels[i] != null) h.labels[i].setText(labels[i]);
            h.values[i].setText(vals[i]);
            h.values[i].setTextColor(TEXT);
        }
        if (h.expanded) {
            setTemperatureWarningColor(h.values[4], d.temp, 60, 70);
            setTemperatureWarningColor(h.values[5], d.vrTemp, 70, 80);
            setResponseTimeWarningColor(h.values[12], d.responseTime);
            setWifiSignalWarningColor(h.values[13], d.wifi);
            setFanSpeedWarningColor(h.values[14], d.temp, d.fanSpeed);
        } else {
            setTemperatureWarningColor(h.values[2], d.temp, 60, 70);
            setResponseTimeWarningColor(h.values[5], d.responseTime);
            setFanSpeedWarningColor(h.values[13], d.temp, d.fanSpeed);
        }
        double best = diffNumber(d.bestDiff);
        double session = diffNumber(d.sessionDiff);
        if (session > 0 && best > 0 && session >= best) {
            h.values[h.expanded ? 3 : 1].setTextColor(Color.rgb(34, 197, 94));
        }
    }

    private String sharesLabel(CardHolder h, Device d) {
        long delta = 0;
        if (h != null && d != null && d.online && h.lastSharesAccepted >= 0) {
            delta = d.sharesAccepted - h.lastSharesAccepted;
        }
        if (h != null && d != null && d.online) h.lastSharesAccepted = d.sharesAccepted;
        return delta > 0 ? "Shares (+" + formatLong(delta) + ")" : "Shares";
    }

    private void updateFooterTimes() {
        for (CardHolder h : cards.values()) {
            if (h != null && h.footer != null && h.currentDevice != null) {
                h.footer.setText(formatFooter(h.currentDevice));
            }
        }
    }

    private String formatFooter(Device d) {
        if (d == null) return "";
        String footer = d.ip
            + (d.firmware.isEmpty() ? "" : " · " + d.firmware)
            + (d.chip.isEmpty() ? "" : " · " + d.chip);
        String age = formatUpdatedAge(d.updatedAt);
        return age.isEmpty() ? footer : footer + " · " + age;
    }

    private String formatUpdatedAge(long updatedAt) {
        if (updatedAt <= 0) return "";
        long ageSeconds = Math.max(0L, (System.currentTimeMillis() - updatedAt) / 1000L);
        if (ageSeconds < 5) return "now";
        if (ageSeconds < 60) return ageSeconds + "s ago";
        long minutes = ageSeconds / 60L;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60L;
        if (hours < 24) return hours + "h ago";
        return (hours / 24L) + "d ago";
    }

    private int getDeviceTitleColor(Device d) {
        if (d == null || !d.online) return MUTED;
        if (isCriticalDevice(d)) return Color.rgb(248, 113, 113);
        if (isWarningDevice(d)) return Color.rgb(251, 146, 60);
        return TEXT;
    }

    private boolean isCriticalDevice(Device d) {
        return d.temp >= 70 || d.vrTemp >= 80 || d.responseTime >= 200 || wifiPercentValue(d.wifi) < 40;
    }

    private boolean isWarningDevice(Device d) {
        int wifi = wifiPercentValue(d.wifi);
        return d.temp >= 60 || d.vrTemp >= 70 || d.responseTime >= 100 || (wifi >= 40 && wifi < 60);
    }

    private int wifiPercentValue(String wifiText) {
        if (wifiText == null || wifiText.equals("–")) return 100;
        try {
            return Integer.parseInt(wifiText.replaceAll("[^0-9]", ""));
        } catch (Exception ignored) {
            return 100;
        }
    }

    private void setTemperatureWarningColor(TextView valueView, double temp, double warnAt, double criticalAt) {
        if (valueView == null || temp <= 0) return;
        if (temp >= criticalAt) {
            valueView.setTextColor(Color.rgb(248, 113, 113));
        } else if (temp >= warnAt) {
            valueView.setTextColor(Color.rgb(251, 146, 60));
        }
    }

    private void setResponseTimeWarningColor(TextView valueView, double responseTime) {
        if (valueView == null || responseTime <= 0) return;
        if (responseTime >= 200) {
            valueView.setTextColor(Color.rgb(248, 113, 113));
        } else if (responseTime >= 100) {
            valueView.setTextColor(Color.rgb(251, 146, 60));
        }
    }

    private void setWifiSignalWarningColor(TextView valueView, String wifiText) {
        if (valueView == null || wifiText == null) return;
        try {
            int percent = wifiPercentValue(wifiText);
            if (percent < 40) {
                valueView.setTextColor(Color.rgb(248, 113, 113));
            } else if (percent < 60) {
                valueView.setTextColor(Color.rgb(251, 146, 60));
            }
        } catch (Exception ignored) {}
    }

    private boolean isCriticalFanSpeed(double temp, double fanSpeed) {
        return temp >= 70 && fanSpeed < 10;
    }

    private boolean isWarningFanSpeed(double temp, double fanSpeed) {
        return temp >= 60 && fanSpeed < 20;
    }

    private void setFanSpeedWarningColor(TextView valueView, double temp, double fanSpeed) {
        if (valueView == null || temp <= 0) return;
        if (isCriticalFanSpeed(temp, fanSpeed)) {
            valueView.setTextColor(Color.rgb(248, 113, 113));
        } else if (isWarningFanSpeed(temp, fanSpeed)) {
            valueView.setTextColor(Color.rgb(251, 146, 60));
        }
    }

    private int addMetricRow(LinearLayout parent, int index, CardHolder holder, String l1, String l2) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(8));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        int rowIndex = index / 2;
        if (holder.rows != null && rowIndex >= 0 && rowIndex < holder.rows.length) holder.rows[rowIndex] = row;
        MetricBox m1 = metric(l1);
        row.addView(m1.box, new LinearLayout.LayoutParams(0, -2, 1));
        holder.labels[index] = m1.label;
        holder.values[index++] = m1.value;
        MetricBox m2 = metric(l2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.setMargins(dp(8), 0, 0, 0);
        row.addView(m2.box, lp);
        holder.labels[index] = m2.label;
        holder.values[index++] = m2.value;
        return index;
    }

    private void toggleCompactMode(CardHolder h) {
        if (h == null) return;
        h.expanded = !h.expanded;
        applyCompactMode(h, true);
        if (h.currentDevice != null) updateCard(h, h.currentDevice);
    }

    private void applyCompactMode(CardHolder h, boolean animate) {
        if (h == null || h.rows == null) return;
        if (animate && h.card != null) {
            Transition transition = new AutoTransition();
            transition.setDuration(UI_ANIMATION_MS);
            TransitionManager.beginDelayedTransition(h.card, transition);
        }
        for (int i = 0; i < h.rows.length; i++) {
            if (h.rows[i] != null) h.rows[i].setVisibility(h.expanded || i < 3 ? View.VISIBLE : View.GONE);
        }
    }

    private MetricBox metric(String label) {
        MetricBox m = new MetricBox();
        LinearLayout box = new LinearLayout(this);
        m.box = box;
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.setBackground(round(CARD2, dp(14), 0));
        m.label = text(label, 11, MUTED, false);
        box.addView(m.label);
        m.value = text("–", 16, TEXT, true);
        box.addView(m.value);
        return m;
    }

    private String formatBlocksFound(String block) {
        String value = (block == null || block.trim().isEmpty() || block.trim().equals("–")) ? "0" : block.trim();
        boolean found = false;
        String numeric = value.replaceAll("^.*?([0-9]+(?:\\.[0-9]+)?).*$", "$1");
        try {
            found = Double.parseDouble(numeric) > 0;
        } catch (Exception ignored) {
            found = !value.equals("0");
        }
        return "Blocks found: " + value + (found ? " 🏆" : "");
    }

    private void confirmRestart(CardHolder h) {
        if (h == null || h.ip == null || h.ip.isEmpty()) return;
        String name = h.deviceName == null || h.deviceName.isEmpty() ? h.ip : h.deviceName;
        LinearLayout menu = menuCard("Restart device");

        TextView msg = text("Restart " + name + "?", 15, MUTED, false);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(dp(14), 0, dp(14), 0);
        menu.addView(msg, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = dialogActionRow(menu);

        final PopupWindow[] ref = new PopupWindow[1];
        Button cancel = dialogButton("Cancel", Color.rgb(71, 85, 105));
        cancel.setOnClickListener(v -> ref[0].dismiss());

        Button restart = dialogButton("Restart", Color.rgb(220, 38, 38));
        restart.setOnClickListener(v -> { ref[0].dismiss(); restartDevice(h.ip, h.deviceName); });

        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
        left.setMargins(0, 0, dp(5), 0);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
        right.setMargins(dp(5), 0, 0, 0);
        row.addView(cancel, left);
        row.addView(restart, right);

        ref[0] = showOverlayCard(menu, 300);
    }

    private void restartDevice(String ip, String name) {
        Toast.makeText(this, "Restarting " + (name == null || name.isEmpty() ? ip : name), Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            boolean ok = false;
            HttpURLConnection c = null;
            try {
                URL url = new URL("http://" + ip + "/api/system/restart");
                c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.getOutputStream().write(new byte[0]);
                int code = c.getResponseCode();
                ok = code >= 200 && code < 300;
            } catch (Exception ignored) {
                ok = false;
            } finally {
                if (c != null) c.disconnect();
            }
            boolean result = ok;
            handler.post(() -> Toast.makeText(this, result ? "Restart sent" : "Restart failed", Toast.LENGTH_LONG).show());
        });
    }

    private void togglePauseResume(CardHolder h) {
        if (h == null || h.currentDevice == null || !supportsPauseResume(h.currentDevice) || h.pauseResumeBusy) return;
        boolean resume = h.currentDevice.miningPaused;
        h.pauseResumeBusy = true;
        updatePauseResumeButton(h);
        executor.execute(() -> {
            boolean ok = postSystemAction(h.ip, resume ? "resume" : "pause");
            Device fetched = ok ? fetchDevice(h.ip) : null;
            handler.post(() -> {
                h.pauseResumeBusy = false;
                if (fetched != null) {
                    updateSingleDeviceCard(fetched, loadIps());
                    updateSummaryFromCurrentDevices(loadIps());
                } else {
                    updatePauseResumeButton(h);
                }
                Toast.makeText(this, ok ? (resume ? "Resume sent" : "Pause sent") : "Pause/Resume failed", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private boolean postSystemAction(String ip, String action) {
        HttpURLConnection c = null;
        try {
            URL url = new URL("http://" + ip + "/api/system/" + action);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.getOutputStream().write(new byte[0]);
            int code = c.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void updatePauseResumeButton(CardHolder h) {
        if (h == null || h.pauseResume == null) return;
        Device d = h.currentDevice;
        if (!supportsPauseResume(d)) {
            h.pauseResume.setVisibility(View.GONE);
            return;
        }
        h.pauseResume.setVisibility(View.VISIBLE);
        boolean paused = d.miningPaused;
        h.pauseResume.setImageResource(paused ? R.drawable.ic_card_play : R.drawable.ic_card_pause);
        h.pauseResume.setContentDescription(paused ? "Resume mining" : "Pause mining");
        h.pauseResume.setEnabled(!h.pauseResumeBusy);
        h.pauseResume.setAlpha(h.pauseResumeBusy ? 0.62f : 1.0f);
        h.pauseResume.setBackground(round(h.pauseResumeBusy ? ACTION_DISABLED : (paused ? GOOD : ACTION_AMBER), dp(14), 0));
    }

    private boolean supportsPauseResume(Device d) {
        return d != null && d.online && !isNerdQaxeDevice(d) && isAxeOsAtLeast(d.axeOSVersion, 2, 14, 0);
    }

    private boolean isNerdQaxeDevice(Device d) {
        String text = ((d.name == null ? "" : d.name) + " " + (d.model == null ? "" : d.model) + " " + (d.chip == null ? "" : d.chip)).toLowerCase(Locale.US);
        return text.contains("nerdqaxe") || text.contains("nerdqaxeplus") || text.contains("nerdqaxe+");
    }

    private boolean isAxeOsAtLeast(String version, int wantMajor, int wantMinor, int wantPatch) {
        if (version == null || version.trim().isEmpty()) return false;
        Matcher m = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(version);
        if (!m.find()) return false;
        int major = parseInt(m.group(1));
        int minor = parseInt(m.group(2));
        int patch = m.group(3) == null ? 0 : parseInt(m.group(3));
        if (major != wantMajor) return major > wantMajor;
        if (minor != wantMinor) return minor > wantMinor;
        return patch >= wantPatch;
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private PopupWindow showOverlayCard(LinearLayout card, int widthDp) {
        card.setClickable(true);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        if (activeOverlayPopup != null && activeOverlayPopup.isShowing() && activeOverlay != null) {
            if (activeOverlayCard != null) activeOverlay.removeView(activeOverlayCard);
            activeOverlayCard = card;
            activeOverlay.addView(card, mlp);
            animateOverlayCard(card);
            activeOverlay.requestLayout();
            return activeOverlayPopup;
        }

        FrameLayout overlay = new FrameLayout(this);
        addBlurredBackground(overlay);
        activeOverlay = overlay;
        activeOverlayCard = card;
        overlay.addView(card, mlp);
        PopupWindow pw = new PopupWindow(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        activeOverlayPopup = pw;
        overlay.setOnClickListener(v -> pw.dismiss());
        pw.setOnDismissListener(() -> {
            activeOverlayPopup = null;
            activeOverlay = null;
            activeOverlayCard = null;
        });
        pw.setOutsideTouchable(true);
        pw.showAtLocation(getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        animateOverlayCard(card);
        return pw;
    }

    private void animateOverlayCard(View card) {
        card.setAlpha(0f);
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        card.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(UI_ANIMATION_MS)
                .start();
    }

    private void addBlurredBackground(FrameLayout overlay) {
        View decor = getWindow().getDecorView();
        int w = decor.getWidth();
        int h = decor.getHeight();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && w > 0 && h > 0) {
            try {
                Bitmap snapshot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(snapshot);
                decor.draw(canvas);
                ImageView bg = new ImageView(this);
                bg.setImageBitmap(snapshot);
                bg.setScaleType(ImageView.ScaleType.FIT_XY);
                bg.setRenderEffect(RenderEffect.createBlurEffect(dp(8), dp(8), Shader.TileMode.CLAMP));
                overlay.addView(bg, new FrameLayout.LayoutParams(-1, -1));
            } catch (Exception ignored) {
                overlay.setBackgroundColor(Color.argb(120, 7, 17, 31));
                return;
            }
        }
        View dim = new View(this);
        dim.setBackgroundColor(Color.argb(120, 7, 17, 31));
        overlay.addView(dim, new FrameLayout.LayoutParams(-1, -1));
    }

    private LinearLayout menuCard(String title) {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(10), dp(10), dp(10), dp(10));
        menu.setBackground(round(Color.rgb(12, 28, 48), dp(16), Color.rgb(51, 82, 120)));
        if (title != null && !title.isEmpty()) {
            TextView t = text(title, 18, TEXT, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(10), dp(8), dp(10), dp(10));
            menu.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        return menu;
    }

    private TextView menuAction(String label, Runnable action) {
        TextView b = text(label, 17, TEXT, false);
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private TextView menuAction(String label, int color, Runnable action) {
        TextView b = menuAction(label, action);
        b.setTextColor(color);
        return b;
    }

    private LinearLayout menuActionIcon(int iconRes, String label, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setOnClickListener(v -> action.run());
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(TEXT);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), dp(20));
        ilp.setMargins(0, 0, dp(12), 0);
        row.addView(icon, ilp);
        TextView text = text(label, 17, TEXT, false);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private LinearLayout statusMenuAction(String label, int iconRes, boolean active, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOnClickListener(v -> action.run());
        TextView left = text(label, 17, TEXT, false);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView right = new ImageView(this);
        right.setImageResource(iconRes);
        right.setColorFilter(active ? GOOD : MUTED);
        right.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        iconLp.setMargins(dp(26), 0, 0, 0);
        row.addView(right, iconLp);
        return row;
    }

    private void setStatusIcon(LinearLayout row, int iconRes, boolean active) {
        ImageView status = (ImageView) row.getChildAt(1);
        status.setImageResource(iconRes);
        status.setColorFilter(active ? GOOD : MUTED);
    }

    private Button actionButton(String label, int bgColor, Runnable action) {
        Button b = dialogButton(label, bgColor);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private LinearLayout.LayoutParams actionButtonLp(boolean rightSide) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(rightSide ? dp(5) : 0, 0, rightSide ? 0 : dp(5), 0);
        return lp;
    }

    private LinearLayout dialogActionRow(LinearLayout menu) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(6), 0, dp(6), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(14), 0, 0);
        menu.addView(row, lp);
        return row;
    }

    private void addDialogMenuAction(LinearLayout menu, TextView item) {
        item.setPadding(dp(14), dp(10), dp(14), dp(10));
        menu.addView(item, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addDialogDeviceRow(LinearLayout menu, LinearLayout row) {
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        menu.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addDialogInput(LinearLayout menu, EditText input) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(6), 0, dp(6), dp(10));
        menu.addView(input, lp);
    }

    private void showAddDialog() {
        showAddDialog(false);
    }

    private void showAddDialog(boolean returnToDevices) {
        LinearLayout menu = menuCard("Add device");
        EditText input = new EditText(this);
        input.setHint("192.168.1.100");
        input.setHintTextColor(MUTED);
        input.setTextColor(TEXT);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        int p = dp(14); input.setPadding(p, dp(10), p, dp(10));
        input.setBackground(round(CARD2, dp(12), Color.rgb(51, 82, 120)));
        addDialogInput(menu, input);

        LinearLayout row = dialogActionRow(menu);
        final PopupWindow[] ref = new PopupWindow[1];
        row.addView(actionButton("Cancel", Color.rgb(71, 85, 105), () -> {
            showMenu(null);
        }), actionButtonLp(false));
        row.addView(actionButton("Add", GOOD, () -> {
            String ip = cleanIp(input.getText().toString());
            if (ip.isEmpty()) return;
            List<String> ips = loadIps();
            if (ips.contains(ip)) {
                Toast.makeText(this, "Device already exists", Toast.LENGTH_SHORT).show();
                return;
            }
            ips.add(ip);
            saveIps(ips);
            refresh();
            if (returnToDevices) showBitaxeDialog();
            else showMenu(null);
        }), actionButtonLp(true));
        ref[0] = showOverlayCard(menu, 300);
    }

    private void showMenu(View anchor) {
        LinearLayout menu = menuCard("");
        final PopupWindow[] ref = new PopupWindow[1];
        menu.addView(menuActionIcon(R.drawable.ic_menu_add, "Add", () -> showAddDialog(true)), new LinearLayout.LayoutParams(-1, -2));
        menu.addView(menuActionIcon(R.drawable.ic_menu_devices, "Devices", () -> showBitaxeDialog()), new LinearLayout.LayoutParams(-1, -2));
        menu.addView(menuActionIcon(R.drawable.ic_menu_interval, "Interval", () -> showRefreshIntervalDialog()), new LinearLayout.LayoutParams(-1, -2));
        menu.addView(menuActionIcon(R.drawable.ic_menu_display, "Display", () -> showDisplayDialog()), new LinearLayout.LayoutParams(-1, -2));
        menu.addView(menuActionIcon(R.drawable.ic_menu_backup, "Backup", () -> showBackupDialog()), new LinearLayout.LayoutParams(-1, -2));
        menu.addView(menuActionIcon(R.drawable.ic_menu_info, "About", () -> showInfoDialog()), new LinearLayout.LayoutParams(-1, -2));
        ref[0] = showOverlayCard(menu, 280);
    }

    private void showBackupDialog() {
        LinearLayout menu = menuCard("Backup");
        final PopupWindow[] ref = new PopupWindow[1];
        addDialogMenuAction(menu, menuAction("Export", () -> { ref[0].dismiss(); startBackupExport(); }));
        addDialogMenuAction(menu, menuAction("Import", () -> { ref[0].dismiss(); startBackupImport(); }));
        LinearLayout closeRow = dialogActionRow(menu);
        closeRow.addView(actionButton("Close", ACTION_BLUE, () -> showMenu(null)), new LinearLayout.LayoutParams(-1, -2));
        ref[0] = showOverlayCard(menu, 300);
    }

    private void startBackupExport() {
        Uri folder = getBackupFolderUri();
        if (folder != null) {
            writeBackupToFolder(folder);
        } else {
            pendingBackupAction = BACKUP_ACTION_EXPORT;
            startBackupFolderPicker();
        }
    }

    private void startBackupImport() {
        Uri folder = getBackupFolderUri();
        if (folder != null) {
            openBackupImportPicker(folder);
        } else {
            pendingBackupAction = BACKUP_ACTION_IMPORT;
            startBackupFolderPicker();
        }
    }

    private void startBackupFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, documentsFolderUri());
        }
        Toast.makeText(this, "Choose Documents folder", Toast.LENGTH_LONG).show();
        try {
            startActivityForResult(intent, REQUEST_BACKUP_FOLDER);
        } catch (Exception e) {
            pendingBackupAction = BACKUP_ACTION_NONE;
            Toast.makeText(this, "Could not open folder picker", Toast.LENGTH_SHORT).show();
        }
    }

    private void openBackupImportPicker(Uri folder) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder);
        }
        try {
            startActivityForResult(intent, REQUEST_IMPORT_BACKUP);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open file picker", Toast.LENGTH_SHORT).show();
        }
    }

    private Uri getBackupFolderUri() {
        String value = prefs.getString(KEY_BACKUP_TREE_URI, "");
        if (value == null || value.isEmpty()) return null;
        return Uri.parse(value);
    }

    private void saveBackupFolder(Uri uri, int flags) {
        int permissions = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, permissions);
        } catch (Exception ignored) {
        }
        prefs.edit().putString(KEY_BACKUP_TREE_URI, uri.toString()).apply();
    }

    private Uri documentsFolderUri() {
        return DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Documents"
        );
    }

    private String backupFilename() {
        return "bitboard-backup-" + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()) + ".json";
    }

    private Uri backupFolderDocumentUri(Uri folderTreeUri) {
        String documentId = DocumentsContract.getTreeDocumentId(folderTreeUri);
        return DocumentsContract.buildDocumentUriUsingTree(folderTreeUri, documentId);
    }

    private String readableBackupFolder(Uri folderTreeUri) {
        try {
            String documentId = DocumentsContract.getTreeDocumentId(folderTreeUri);
            if (documentId == null || documentId.isEmpty()) return "selected folder";
            String path = Uri.decode(documentId);
            int colon = path.indexOf(':');
            if (colon >= 0) path = path.substring(colon + 1);
            path = path.trim();
            while (path.startsWith("/")) path = path.substring(1);
            if (path.isEmpty()) return "selected folder";
            return "/" + path;
        } catch (Exception e) {
            return "selected folder";
        }
    }

    private JSONObject createBackupJson() throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", "BitBoard");
        root.put("version", 1);
        root.put("exportedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date()));

        JSONArray devices = new JSONArray();
        for (String ip : loadIps()) {
            JSONObject item = new JSONObject();
            item.put("ip", ip);
            devices.put(item);
        }
        root.put("devices", devices);

        JSONObject settings = new JSONObject();
        settings.put("refreshIntervalMs", getRefreshIntervalMs());
        settings.put("onlineOnly", isOnlineOnly());
        settings.put("cardsExpanded", areCardsExpandedByDefault());
        root.put("settings", settings);
        return root;
    }

    private boolean writeBackupToUri(Uri uri) {
        try {
            OutputStream out = getContentResolver().openOutputStream(uri);
            if (out == null) throw new Exception("No output stream");
            OutputStreamWriter writer = new OutputStreamWriter(out, "UTF-8");
            try {
                writer.write(createBackupJson().toString(2));
            } finally {
                writer.close();
            }
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Export failed", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void writeBackupToFolder(Uri folderUri) {
        try {
            Uri target = DocumentsContract.createDocument(getContentResolver(), backupFolderDocumentUri(folderUri), "application/json", backupFilename());
            if (target == null) throw new Exception("No backup file");
            if (writeBackupToUri(target)) {
                Toast.makeText(this, "Backup exported to: " + readableBackupFolder(folderUri), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Export failed", Toast.LENGTH_LONG).show();
        }
    }

    private void importBackupFromUri(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("No input stream");
            JSONObject root = new JSONObject(readText(in));
            if (!"BitBoard".equals(root.optString("app", ""))) throw new Exception("Wrong app");
            JSONArray devices = root.optJSONArray("devices");
            if (devices == null) throw new Exception("No devices");

            List<String> ips = new ArrayList<>();
            for (int i = 0; i < devices.length(); i++) {
                JSONObject item = devices.optJSONObject(i);
                String ip = cleanIp(item != null ? item.optString("ip", "") : devices.optString(i, ""));
                if (!ip.isEmpty() && !ips.contains(ip)) ips.add(ip);
            }

            JSONObject settings = root.optJSONObject("settings");
            if (settings != null) {
                if (settings.has("refreshIntervalMs")) setRefreshIntervalMs(settings.optLong("refreshIntervalMs", DEFAULT_REFRESH_INTERVAL_MS));
                if (settings.has("onlineOnly")) setOnlineOnly(settings.optBoolean("onlineOnly", isOnlineOnly()));
                if (settings.has("cardsExpanded")) prefs.edit().putBoolean(KEY_CARDS_EXPANDED, settings.optBoolean("cardsExpanded", areCardsExpandedByDefault())).apply();
            }

            saveIps(ips);
            refresh();
            Toast.makeText(this, "Backup imported", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Import failed", Toast.LENGTH_LONG).show();
        }
    }

    private void addStoreLinksSection(LinearLayout menu) {
        ImageView zapstore = new ImageView(this);
        zapstore.setImageResource(R.drawable.zapstore);
        zapstore.setAdjustViewBounds(true);
        zapstore.setScaleType(ImageView.ScaleType.FIT_CENTER);
        zapstore.setOnClickListener(v -> openExternalLink(ZAPSTORE_URL, "Could not open Zapstore"));
        LinearLayout.LayoutParams zapstoreLp = new LinearLayout.LayoutParams(dp(190), dp(56));
        zapstoreLp.gravity = Gravity.CENTER_HORIZONTAL;
        zapstoreLp.setMargins(0, dp(24), 0, dp(10));
        menu.addView(zapstore, zapstoreLp);

        LinearLayout footerRow = new LinearLayout(this);
        footerRow.setGravity(Gravity.CENTER_VERTICAL);
        footerRow.setOrientation(LinearLayout.HORIZONTAL);
        footerRow.setPadding(dp(14), 0, dp(14), 0);

        TextView lightningAddress = text("Lightning Donation", 15, ACCENT, false);
        lightningAddress.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        lightningAddress.setPaintFlags(lightningAddress.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        lightningAddress.setOnClickListener(v -> openExternalLink(DONATION_URL, "Could not open donation link"));
        footerRow.addView(lightningAddress, new LinearLayout.LayoutParams(0, -2, 1));

        TextView github = text("Github", 15, ACCENT, false);
        github.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        github.setPaintFlags(github.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        github.setOnClickListener(v -> openExternalLink(GITHUB_URL, "Could not open Github"));
        footerRow.addView(github, new LinearLayout.LayoutParams(-2, -2));

        menu.addView(footerRow, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addVersionLine(LinearLayout menu) {
        TextView version = text("Version " + getAppVersionName(), 15, TEXT, false);
        version.setGravity(Gravity.CENTER);
        version.setPadding(dp(14), dp(12), dp(14), 0);
        menu.addView(version, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showInfoDialog() {
        LinearLayout menu = menuCard("About BitBoard");
        TextView msg = text("Simple Bitaxe monitoring.\n\nTrack your miners, check key stats, and manage devices in one clean view.", 15, TEXT, false);
        msg.setGravity(Gravity.START);
        msg.setLineSpacing(dp(2), 1.0f);
        msg.setPadding(dp(14), 0, dp(14), 0);
        menu.addView(msg, new LinearLayout.LayoutParams(-1, -2));

        addStoreLinksSection(menu);
        addVersionLine(menu);

        LinearLayout closeRow = dialogActionRow(menu);
        final PopupWindow[] ref = new PopupWindow[1];
        closeRow.addView(actionButton("Close", ACTION_BLUE, () -> showMenu(null)), new LinearLayout.LayoutParams(-1, -2));
        ref[0] = showOverlayCard(menu, 300);
    }

    private String getAppVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "";
        }
    }

    private void openExternalLink(String url, String errorMessage) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show();
        }
    }

    private void showDisplayDialog() {
        LinearLayout menu = menuCard("Display");
        final PopupWindow[] ref = new PopupWindow[1];
        final LinearLayout[] compactItem = new LinearLayout[1];
        final LinearLayout[] expandedItem = new LinearLayout[1];
        final LinearLayout[] onlineOnlyItem = new LinearLayout[1];
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            boolean expandedSelected = areAllCardsExpanded();
            boolean onlineOnly = isOnlineOnly();
            setStatusIcon(compactItem[0], !expandedSelected ? R.drawable.ic_status_radio_on : R.drawable.ic_status_radio_off, !expandedSelected);
            setStatusIcon(expandedItem[0], expandedSelected ? R.drawable.ic_status_radio_on : R.drawable.ic_status_radio_off, expandedSelected);
            setStatusIcon(onlineOnlyItem[0], onlineOnly ? R.drawable.ic_status_checkbox_on : R.drawable.ic_status_checkbox_off, onlineOnly);
        };

        compactItem[0] = statusMenuAction("Compact", R.drawable.ic_status_radio_off, false, () -> { setAllCardsExpanded(false); render[0].run(); });
        expandedItem[0] = statusMenuAction("Expanded", R.drawable.ic_status_radio_off, false, () -> { setAllCardsExpanded(true); render[0].run(); });
        onlineOnlyItem[0] = statusMenuAction("Online only", R.drawable.ic_status_checkbox_off, false, () -> { setOnlineOnly(!isOnlineOnly()); render[0].run(); });
        addDialogDeviceRow(menu, compactItem[0]);
        addDialogDeviceRow(menu, expandedItem[0]);
        addDialogDeviceRow(menu, onlineOnlyItem[0]);
        render[0].run();

        LinearLayout closeRow = dialogActionRow(menu);
        closeRow.addView(actionButton("Close", ACTION_BLUE, () -> showMenu(null)), new LinearLayout.LayoutParams(-1, -2));
        ref[0] = showOverlayCard(menu, 300);
    }

    private boolean areCardsExpandedByDefault() {
        return prefs.getBoolean(KEY_CARDS_EXPANDED, false);
    }

    private void setAllCardsExpanded(boolean expanded) {
        prefs.edit().putBoolean(KEY_CARDS_EXPANDED, expanded).apply();
        for (CardHolder h : cards.values()) {
            if (h == null) continue;
            h.expanded = expanded;
            applyCompactMode(h, true);
            if (h.currentDevice != null) updateCard(h, h.currentDevice);
        }
    }

    private boolean areAllCardsExpanded() {
        if (cards.isEmpty()) return areCardsExpandedByDefault();
        for (CardHolder h : cards.values()) {
            if (h != null && !h.expanded) return false;
        }
        return true;
    }

    private void showRefreshIntervalDialog() {
        LinearLayout menu = menuCard("Interval");
        final PopupWindow[] ref = new PopupWindow[1];
        addRefreshIntervalOption(menu, ref, "1 minute", 60000L);
        addRefreshIntervalOption(menu, ref, "2 minutes", 120000L);
        addRefreshIntervalOption(menu, ref, "3 minutes", 180000L);
        addRefreshIntervalOption(menu, ref, "Pull to refresh", REFRESH_MANUAL);
        LinearLayout closeRow = dialogActionRow(menu);
        closeRow.addView(actionButton("Close", ACTION_BLUE, () -> showMenu(null)), new LinearLayout.LayoutParams(-1, -2));
        ref[0] = showOverlayCard(menu, 300);
    }

    private void addRefreshIntervalOption(LinearLayout menu, PopupWindow[] ref, String label, long intervalMs) {
        boolean selected = getRefreshIntervalMs() == intervalMs;
        LinearLayout row = statusMenuAction(label, selected ? R.drawable.ic_status_radio_on : R.drawable.ic_status_radio_off, selected, () -> {
            setRefreshIntervalMs(intervalMs);
            showMenu(null);
        });
        addDialogDeviceRow(menu, row);
    }

    private void showBitaxeDialog() {
        LinearLayout menu = menuCard("Manage devices");
        final PopupWindow[] ref = new PopupWindow[1];
        List<String> ips = loadIps();
        if (ips.isEmpty()) {
            TextView empty = text("No devices saved", 15, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(14), dp(10), dp(14), dp(10));
            menu.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            for (int i = 0; i < ips.size(); i++) {
                String ip = ips.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView left = text(deviceMenuLabel(ip), 16, TEXT, false);
                row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

                ImageButton up = iconButton(R.drawable.ic_device_arrow_up, Color.rgb(71, 85, 105), "Move up");
                up.setEnabled(i > 0);
                up.setAlpha(i > 0 ? 1.0f : 0.35f);
                up.setOnClickListener(v -> moveDevice(ip, -1));
                row.addView(up, deviceIconButtonLp(true));

                ImageButton down = iconButton(R.drawable.ic_device_arrow_down, Color.rgb(71, 85, 105), "Move down");
                down.setEnabled(i < ips.size() - 1);
                down.setAlpha(i < ips.size() - 1 ? 1.0f : 0.35f);
                down.setOnClickListener(v -> moveDevice(ip, 1));
                row.addView(down, deviceIconButtonLp(false));

                ImageButton edit = iconButton(R.drawable.ic_device_edit, ACTION_BLUE, "Edit IP");
                edit.setOnClickListener(v -> showEditIpDialog(ip));
                row.addView(edit, deviceIconButtonLp(false));

                ImageButton delete = iconButton(R.drawable.ic_device_trash, Color.rgb(220, 38, 38), "Delete");
                delete.setOnClickListener(v -> confirmDeleteIp(ip));
                row.addView(delete, deviceIconButtonLp(false));
                addDialogDeviceRow(menu, row);
            }
        }
        LinearLayout closeRow = dialogActionRow(menu);
        closeRow.addView(actionButton("Add", GOOD, () -> {
            ref[0].dismiss();
            showAddDialog(true);
        }), actionButtonLp(false));
        closeRow.addView(actionButton("Close", ACTION_BLUE, () -> showMenu(null)), actionButtonLp(true));
        ref[0] = showOverlayCard(menu, 340);
    }

    private void moveDevice(String ip, int delta) {
        List<String> ips = loadIps();
        int from = ips.indexOf(ip);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= ips.size()) return;
        Collections.swap(ips, from, to);
        saveIps(ips);
        renderDevicesFromCurrentOrder();
        showBitaxeDialog();
    }

    private void renderDevicesFromCurrentOrder() {
        List<String> ips = loadIps();
        List<Device> devices = new ArrayList<>();
        for (String ip : ips) {
            Device d = currentDevices.get(ip);
            if (d == null) d = loadLastDevice(ip);
            if (d == null) d = placeholderDevice(ip);
            devices.add(d);
        }
        renderDevices(devices, ips, true);
    }

    private String deviceMenuLabel(String ip) {
        Device current = currentDevices.get(ip);
        String name = current != null ? current.name : "";
        if (name == null || name.trim().isEmpty() || name.equals(ip)) {
            Device last = loadLastDevice(ip);
            name = last != null ? last.name : "";
        }
        return name == null || name.trim().isEmpty() || name.equals(ip) ? ip : name.trim();
    }

    private void showEditIpDialog(String oldIp) {
        LinearLayout menu = menuCard("Edit IP");
        EditText input = new EditText(this);
        input.setText(oldIp);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        int p = dp(14); input.setPadding(p, dp(10), p, dp(10));
        input.setBackground(round(CARD2, dp(12), Color.rgb(51, 82, 120)));
        addDialogInput(menu, input);

        LinearLayout row = dialogActionRow(menu);
        final PopupWindow[] ref = new PopupWindow[1];
        row.addView(actionButton("Cancel", Color.rgb(71, 85, 105), () -> showBitaxeDialog()), actionButtonLp(false));
        row.addView(actionButton("Save", GOOD, () -> {
            String newIp = cleanIp(input.getText().toString());
            if (newIp.isEmpty()) return;
            List<String> ips = loadIps();
            if (!newIp.equals(oldIp) && ips.contains(newIp)) {
                Toast.makeText(this, "Device already exists", Toast.LENGTH_SHORT).show();
                return;
            }
            int idx = ips.indexOf(oldIp);
            if (idx >= 0) ips.set(idx, newIp);
            saveIps(ips);
            removeLastDevice(oldIp);
            CardHolder h = cards.remove(oldIp);
            if (h != null) deviceList.removeView(h.card);
            refresh();
            showBitaxeDialog();
        }), actionButtonLp(true));
        ref[0] = showOverlayCard(menu, 300);
    }

    private void confirmDeleteIp(String ip) {
        LinearLayout menu = menuCard("Delete device?");
        TextView msg = text(ip, 15, MUTED, false);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(dp(14), 0, dp(14), dp(2));
        menu.addView(msg, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = dialogActionRow(menu);
        final PopupWindow[] ref = new PopupWindow[1];
        row.addView(actionButton("Cancel", Color.rgb(71, 85, 105), () -> showBitaxeDialog()), actionButtonLp(false));
        row.addView(actionButton("Delete", Color.rgb(248, 113, 113), () -> {
            List<String> ips = loadIps();
            ips.remove(ip);
            saveIps(ips);
            removeLastDevice(ip);
            CardHolder h = cards.remove(ip);
            if (h != null) deviceList.removeView(h.card);
            refresh();
            showBitaxeDialog();
        }), actionButtonLp(true));
        ref[0] = showOverlayCard(menu, 300);
    }

    private List<String> loadIps() {
        List<String> out = new ArrayList<>();
        try { JSONArray a = new JSONArray(prefs.getString(KEY_IPS, "[]")); for (int i=0;i<a.length();i++) out.add(a.getString(i)); } catch(Exception ignored) {}
        return out;
    }
    private void saveIps(List<String> ips) { JSONArray a = new JSONArray(); for(String ip:ips) a.put(ip); prefs.edit().putString(KEY_IPS, a.toString()).apply(); }

    private String readText(InputStream stream) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        try {
            while ((line = br.readLine()) != null) sb.append(line);
        } finally {
            br.close();
        }
        return sb.toString();
    }

    private void saveLastDevice(Device d) {
        if (d == null || d.ip == null || d.ip.isEmpty()) return;
        try { prefs.edit().putString(lastDeviceKey(d.ip), deviceToJson(d).toString()).apply(); } catch(Exception ignored) {}
    }

    private Device loadLastDevice(String ip) {
        try {
            String raw = prefs.getString(lastDeviceKey(ip), "");
            if (raw == null || raw.isEmpty()) return null;
            return deviceFromJson(new JSONObject(raw));
        } catch(Exception ignored) {
            return null;
        }
    }

    private void removeLastDevice(String ip) {
        prefs.edit().remove(lastDeviceKey(ip)).apply();
    }

    private String lastDeviceKey(String ip) {
        return KEY_LAST_DEVICE_PREFIX + cleanIp(ip);
    }

    private JSONObject deviceToJson(Device d) throws Exception {
        JSONObject j = new JSONObject();
        j.put("ip", d.ip); j.put("name", d.name); j.put("model", d.model); j.put("firmware", d.firmware); j.put("chip", d.chip);
        j.put("block", d.block); j.put("bestDiff", d.bestDiff); j.put("sessionDiff", d.sessionDiff); j.put("poolDiff", d.poolDiff);
        j.put("wifi", d.wifi); j.put("uptime", d.uptime); j.put("latency", d.latency); j.put("sharesAccepted", d.sharesAccepted);
        j.put("sharesRejected", d.sharesRejected); j.put("hashRate", d.hashRate); j.put("hashRate10m", d.hashRate10m);
        j.put("responseTime", d.responseTime); j.put("temp", d.temp); j.put("vrTemp", d.vrTemp); j.put("power", d.power);
        j.put("voltage", d.voltage); j.put("frequency", d.frequency); j.put("coreMv", d.coreMv); j.put("fanSpeed", d.fanSpeed);
        j.put("updatedAt", d.updatedAt);
        return j;
    }

    private Device deviceFromJson(JSONObject j) {
        Device d = new Device();
        d.ip = str(j, "ip", ""); d.name = str(j, "name", d.ip); d.model = str(j, "model", ""); d.firmware = str(j, "firmware", ""); d.chip = str(j, "chip", "");
        d.block = str(j, "block", "–"); d.bestDiff = str(j, "bestDiff", "–"); d.sessionDiff = str(j, "sessionDiff", "–"); d.poolDiff = str(j, "poolDiff", "–");
        d.wifi = str(j, "wifi", "–"); d.uptime = str(j, "uptime", "–"); d.latency = (long) num(j, "latency"); d.sharesAccepted = (long) num(j, "sharesAccepted");
        d.sharesRejected = (long) num(j, "sharesRejected"); d.hashRate = num(j, "hashRate"); d.hashRate10m = num(j, "hashRate10m");
        d.responseTime = num(j, "responseTime"); d.temp = num(j, "temp"); d.vrTemp = num(j, "vrTemp"); d.power = num(j, "power");
        d.voltage = num(j, "voltage"); d.frequency = num(j, "frequency"); d.coreMv = num(j, "coreMv"); d.fanSpeed = num(j, "fanSpeed");
        d.updatedAt = (long) num(j, "updatedAt");
        return d;
    }

    private static String cleanIp(String s) { return s == null ? "" : s.trim().replace("http://", "").replace("https://", "").replaceAll("/.*$", ""); }
    private static String str(JSONObject j, String k, String def) { return j.has(k) && !j.isNull(k) ? j.optString(k, def) : def; }
    private static String raw(JSONObject j, String k, String def) { Object o = j.opt(k); return o == null || JSONObject.NULL.equals(o) ? def : String.valueOf(o); }
    private static double num(JSONObject j, String k) { return j.optDouble(k, 0); }
    private static double numAny(JSONObject j, String... keys) { for(String k:keys) if(j.has(k) && !j.isNull(k)) return j.optDouble(k, 0); return 0; }
    private static String one(double v) { return String.format(Locale.US, "%.1f", v); }
    private static String zer(double v) { return String.format(Locale.US, "%.0f", v); }
    private static String fmtHash(double v) { if(v<=0) return "–"; if(v>1_000_000_000) return String.format(Locale.US,"%.2f GH/s",v/1_000_000_000); if(v>1_000_000) return String.format(Locale.US,"%.2f MH/s",v/1_000_000); return zer(v)+" GH/s"; }
    private static String fmtTopHash(double v) { if(v<=0) return "–"; return groupThousands(Math.round(v)) + " GH/s"; }
    private static String groupThousands(long v) { return String.format(Locale.US, "%,d", v).replace(',', ' '); }
    private static String wifiPercent(double rssi) { if(rssi == 0) return "–"; int p = Math.max(0, Math.min(100, (int)Math.round(((rssi + 90) / 60.0) * 100))); return p + "%"; }
    private static String fmtUptime(long s) { if(s<=0) return "–"; long d=s/86400,h=(s%86400)/3600,m=(s%3600)/60; if(d>0)return d+"d "+h+"h"; if(h>0)return h+"h "+m+"m"; return m+"m"; }
    private static String fmtDiff(String val) { try { double n=diffNumber(val); if(n == 0 && (val == null || !val.matches(".*[0-9].*"))) return val == null ? "–" : val; double a=Math.abs(n); if(a>=1e15)return trim(n/1e15)+"P"; if(a>=1e12)return trim(n/1e12)+"T"; if(a>=1e9)return trim(n/1e9)+"G"; if(a>=1e6)return trim(n/1e6)+"M"; if(a>=1e3)return trim(n/1e3)+"K"; return trim(n); } catch(Exception e) { return val == null ? "–" : val; } }
    private static double diffNumber(String val) { try { if(val == null) return 0; String raw = val.trim(); double mult = 1; if(raw.matches(".*[kK]\\s*$")) mult = 1e3; else if(raw.matches(".*[mM]\\s*$")) mult = 1e6; else if(raw.matches(".*[gG]\\s*$")) mult = 1e9; else if(raw.matches(".*[tT]\\s*$")) mult = 1e12; else if(raw.matches(".*[pP]\\s*$")) mult = 1e15; return Double.parseDouble(raw.replaceAll("[^0-9.\\-]", "")) * mult; } catch(Exception e) { return 0; } }
    private static String trim(double n) { if(Math.abs(n)>=100)return String.format(Locale.US,"%.0f",n); if(Math.abs(n)>=10)return String.format(Locale.US,"%.1f",n).replace(".0",""); return String.format(Locale.US,"%.2f",n).replaceAll("0+$","").replaceAll("\\.$",""); }

    private static String formatLong(long n) { return String.format(Locale.US, "%,d", n).replace(',', ' '); }

    private ImageButton iconButton(int iconRes, int bgColor, String description) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(iconRes);
        b.setColorFilter(Color.WHITE);
        b.setBackground(round(bgColor, dp(14), 0));
        b.setPadding(dp(5), dp(5), dp(5), dp(5));
        b.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        b.setContentDescription(description);
        return b;
    }

    private LinearLayout.LayoutParams deviceIconButtonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
        lp.setMargins(dp(8), 0, 0, 0);
        return lp;
    }

    private LinearLayout.LayoutParams deviceIconButtonLp(boolean ignored) {
        return deviceIconButtonLp();
    }

    private Button dialogButton(String label, int bgColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setBackground(round(bgColor, dp(13), 0));
        return b;
    }

    private TextView text(String s, int sp, int color, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); if(bold)t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t; }
    private GradientDrawable round(int color, int radius, int stroke) { GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(radius); if(stroke!=0) g.setStroke(dp(1), stroke); return g; }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    private static class CardHolder { LinearLayout card, body; LinearLayout[] rows; TextView name, sub, footer; TextView[] labels, values; ImageButton pauseResume; String ip = "", deviceName = ""; long lastSharesAccepted = -1; boolean expanded = false, pauseResumeBusy = false; Device currentDevice = null; }
    private static class MetricBox { LinearLayout box; TextView label, value; }

    private static class Device {
        String ip, name="", model="", firmware="", axeOSVersion="", chip="", block="–", bestDiff="–", sessionDiff="–", poolDiff="–", wifi="–", uptime="–";
        boolean online, miningPaused; long latency, updatedAt, sharesAccepted, sharesRejected; double hashRate, hashRate10m, responseTime, temp, vrTemp, power, voltage, frequency, coreMv, fanSpeed;
    }
}
