package com.app.aio;

import static com.app.aio.BuildConfig.RAPIDAPI_HOST;
import static com.app.aio.BuildConfig.RAPIDAPI_KEY;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import android.content.ActivityNotFoundException;
import android.webkit.MimeTypeMap;

import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;

// This project is using Rapid API for downloading Instagram etc. videos.
// See this API for more info on how to use it: https://rapidapi.com/coder2077/api/instagram-downloader-download-instagram-videos-stories1
// You have to generate your own API key and store it in local.properties file as below example
// RAPIDAPI_KEY=YOUR_API_KEY
// RAPIDAPI_HOST=instagram-downloader-download-instagram-videos-stories1.p.rapidapi.com
// Do not expose your API key publicly.
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AIOMediaDownloader";

    private static final String PREFS = "aio_prefs";
    private static final String KEY_REMAINING = "credits_remaining";
    private static final String KEY_LIMIT     = "credits_limit";
    private static final String KEY_UPDATED_AT = "credits_updated_at";

    private final OkHttpClient client = new OkHttpClient();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Long, DownloadCard> activeDownloads = new HashMap<>();
    private boolean polling = false;

    private TextInputEditText etUrl;
    private MaterialButton btnFetch;
    private ProgressBar progressBar;
    private LinearLayout llResultsContainer;
    private TextView tvResultsHeader, tvCreditStatus;

    private BroadcastReceiver downloadReceiver;

    /** Holds the views of one live download card. */
    private static class DownloadCard {
        final MaterialCardView card;
        final ProgressBar bar;
        final TextView status;
        final TextView pctLabel;
        final TextView badge;
        DownloadCard(MaterialCardView card, ProgressBar bar, TextView status,
                     TextView pctLabel, TextView badge) {
            this.card = card; this.bar = bar; this.status = status;
            this.pctLabel = pctLabel; this.badge = badge;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etUrl = findViewById(R.id.etUrl);
        btnFetch = findViewById(R.id.btnFetch);
        progressBar = findViewById(R.id.progressBar);
        llResultsContainer = findViewById(R.id.llResultsContainer);
        tvResultsHeader = findViewById(R.id.tvResultsHeader);
        tvCreditStatus = findViewById(R.id.tvCreditStatus);

        loadSavedCredits();
        registerDownloadReceiver();
        restoreRunningDownloads();

        btnFetch.setOnClickListener(v -> {
            CharSequence input = etUrl.getText();
            String url = input == null ? "" : input.toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, R.string.please_paste_url, Toast.LENGTH_SHORT).show();
                return;
            }
            fetchMediaLinks(url);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
        handler.removeCallbacksAndMessages(null);
    }

    // ───────────────────────────── CREDITS ─────────────────────────────

    private void loadSavedCredits() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String rem = p.getString(KEY_REMAINING, null);
        String lim = p.getString(KEY_LIMIT, null);
        long at = p.getLong(KEY_UPDATED_AT, 0L);
        if (rem != null && lim != null) {
            renderCredits(rem, lim, at);
        } else {
            tvCreditStatus.setText(R.string.credits_no_data);
        }
    }

    private void updateCreditBanner(String remaining, String limit) {
        long now = System.currentTimeMillis();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_REMAINING, remaining)
                .putString(KEY_LIMIT, limit)
                .putLong(KEY_UPDATED_AT, now)
                .apply();
        renderCredits(remaining, limit, now);
    }

    private void renderCredits(String remaining, String limit, long updatedAt) {
        String ago = updatedAt > 0 ? " · updated " + timeAgo(updatedAt) : "";
        tvCreditStatus.setText(String.format("Credits: %s of %s remaining%s", remaining, limit, ago));
        try {
            int rem = Integer.parseInt(remaining);
            int lim = Integer.parseInt(limit);
            if (lim > 0 && (rem * 100 / lim) < 20) {
                tvCreditStatus.setTextColor(getColor(android.R.color.holo_red_dark));
            } else {
                tvCreditStatus.setTextColor(getColor(android.R.color.holo_blue_dark));
            }
        } catch (NumberFormatException ignored) {}
    }

    private String timeAgo(long ts) {
        long diff = System.currentTimeMillis() - ts;
        long m = diff / 60_000L;
        if (m < 1) return "just now";
        if (m < 60) return m + "m ago";
        long h = m / 60;
        if (h < 24) return h + "h ago";
        return (h / 24) + "d ago";
    }

    // ───────────────────────────── API CALL ─────────────────────────────

    private void fetchMediaLinks(String socialUrl) {
        setLoadingState(true);
        clearFinishedCards();

        String encodedUrl = Uri.encode(socialUrl);
        String apiUrl = "https://" + RAPIDAPI_HOST + "/fetch?url=" + encodedUrl;

        Request request = new Request.Builder()
                .url(apiUrl)
                .get()
                .addHeader("x-rapidapi-key", RAPIDAPI_KEY)
                .addHeader("x-rapidapi-host", RAPIDAPI_HOST)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> {
                    setLoadingState(false);
                    Toast.makeText(MainActivity.this,
                            "Network error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    // 1. Credits from headers (never null in RapidAPI responses, but guard anyway)
                    String remaining = response.header("x-ratelimit-requests-remaining");
                    String limit     = response.header("x-ratelimit-requests-limit");
                    if (remaining != null && limit != null) {
                        runOnUiThread(() -> updateCreditBanner(remaining, limit));
                    }

                    // 2. Body may be null per OkHttp's @Nullable contract.
                    //    Pull it into a local so we call .body() exactly once
                    //    and every subsequent use works on a non-null reference.
                    ResponseBody body = response.body();
                    if (body == null) {
                        runOnUiThread(() -> {
                            setLoadingState(false);
                            Toast.makeText(MainActivity.this,
                                    "Empty response from server", Toast.LENGTH_LONG).show();
                        });
                        return;
                    }

                    // 3. Error path — body is now guaranteed non-null
                    if (!response.isSuccessful()) {
                        String err = body.string();
                        runOnUiThread(() -> {
                            setLoadingState(false);
                            Toast.makeText(MainActivity.this,
                                    "API Error: " + response.code(), Toast.LENGTH_LONG).show();
                            Log.e(TAG, "API Error Body: " + err);
                        });
                        return;
                    }

                    // 4. Success path — consume the body exactly once
                    String jsonData = body.string();
                    Log.d(TAG, "Raw JSON Response: " + jsonData);
                    runOnUiThread(() -> {
                        setLoadingState(false);
                        try {
                            parseAndDownload(jsonData);
                        } catch (JSONException e) {
                            Toast.makeText(MainActivity.this,
                                    "Failed to parse response", Toast.LENGTH_SHORT).show();
                            Log.e(TAG, "JSON parse error", e);
                        }
                    });
                }
            }
        });
    }

    // ───────────────────────── PARSING → DOWNLOAD ──────────────────────

    private void parseAndDownload(String json) throws JSONException {
        JSONObject root = new JSONObject(json);

        if (root.has("ok") && !root.optBoolean("ok", true)) {
            Toast.makeText(this, "API Error: " + root.optString("error", "Request failed"), Toast.LENGTH_LONG).show();
            return;
        }
        if (root.has("error")) {
            Toast.makeText(this, "API Error: " + root.optString("error"), Toast.LENGTH_LONG).show();
            return;
        }

        // Single-media response (this endpoint's format)
        if (root.has("download_url") || root.has("url")) {
            startFromItem(root);
            return;
        }

        // Array fallback (other platforms / future endpoint versions)
        JSONArray medias = null;
        for (String key : new String[]{"medias", "media", "media_files", "items"}) {
            medias = root.optJSONArray(key);
            if (medias != null) break;
        }
        if (medias == null || medias.length() == 0) {
            Toast.makeText(this, "No downloadable media found.", Toast.LENGTH_LONG).show();
            return;
        }
        for (int i = 0; i < medias.length(); i++) {
            startFromItem(medias.getJSONObject(i));
        }
    }

    private void startFromItem(JSONObject item) {
        String url = item.optString("download_url", item.optString("url", ""));
        if (url.isEmpty()) return;

        String type    = item.optString("type", "video");
        String quality = buildQualityLabel(item.optInt("width", 0), item.optInt("height", 0));
        String ext     = guessExtension(url, type);
        String source  = item.optString("source", "");
        String fileName = buildFileName(source, quality, ext);

        startDownload(url, fileName, quality, type, source);
    }

    private String buildQualityLabel(int width, int height) {
        return (width > 0 && height > 0) ? width + "×" + height : "HD";
    }

    private String guessExtension(String url, String type) {
        String path = url.split("\\?")[0];
        int dot = path.lastIndexOf('.');
        if (dot > 0 && dot < path.length() - 1) {
            String ext = path.substring(dot + 1).toLowerCase(Locale.US);
            if (ext.length() <= 5 && ext.matches("[a-z0-9]+")) return ext;
        }
        if ("video".equalsIgnoreCase(type)) return "mp4";
        if ("image".equalsIgnoreCase(type)) return "jpg";
        if ("audio".equalsIgnoreCase(type)) return "mp3";
        return "bin";
    }

    private String buildFileName(String source, String quality, String ext) {
        String src = source.isEmpty()
                ? "media"
                : source.replace(".com", "").replaceAll("[^a-zA-Z0-9]", "");
        return "aio_" + src + "_" + System.currentTimeMillis() + "_"
                + quality.replace("×", "x") + "." + ext;
    }

    // ───────────────────────── DOWNLOAD MANAGER ────────────────────────

    private void startDownload(String url, String fileName, String quality, String type, String source) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(fileName);
            req.setDescription("Saving to Downloads");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            req.setAllowedOverMetered(true);

            long id = dm.enqueue(req);

            String srcLabel = source.isEmpty()
                    ? "Media"
                    : capitalize(source.replace(".com", ""));
            String headline = String.format("%s • %s • %s", srcLabel, quality, type.toUpperCase(Locale.US));

            DownloadCard ui = addProgressCard(headline, fileName, id, source);
            activeDownloads.put(id, ui);
            startPolling();

            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not start download: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Log.e(TAG, "enqueue failed", e);
        }
    }

    private DownloadCard addProgressCard(String headline, String subline, long id, String source) {
        tvResultsHeader.setVisibility(View.VISIBLE);

        // ── Card shell ──
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardParams);
        card.setRadius(dp(16));
        card.setCardElevation(dp(1));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(0xFFE5E7EB);
        card.setCardBackgroundColor(0xFFFFFFFF);
        card.setTag(id);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> openFile(id));

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(18), dp(18), dp(18), dp(18));

        // ── Top row: circular platform badge + text stack ──
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(this);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        badge.setLayoutParams(badgeParams);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(sourceColor(source));
        badge.setBackground(badgeBg);
        badge.setText(sourceInitial(source));
        badge.setTextColor(0xFFFFFFFF);
        badge.setTextSize(16f);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        topRow.addView(badge);

        LinearLayout textStack = new LinearLayout(this);
        textStack.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams stackParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        stackParams.leftMargin = dp(14);
        textStack.setLayoutParams(stackParams);

        TextView title = new TextView(this);
        title.setText(headline);
        title.setTextSize(14f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF0F172A);

        TextView fileTv = new TextView(this);
        fileTv.setText(subline);
        fileTv.setTextSize(11f);
        fileTv.setTextColor(0xFF64748B);
        fileTv.setMaxLines(1);
        fileTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        fileTv.setPadding(0, dp(3), 0, 0);

        textStack.addView(title);
        textStack.addView(fileTv);
        topRow.addView(textStack);
        inner.addView(topRow);

        // ── Rounded progress bar ──
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        bar.setProgressDrawable(roundedProgressDrawable(0xFF4F46E5));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        barParams.topMargin = dp(16);
        bar.setLayoutParams(barParams);
        inner.addView(bar);

        // ── Bottom row: status (left, flexible) + percentage (right, bold) ──
        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bottomParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bottomParams.topMargin = dp(10);
        bottomRow.setLayoutParams(bottomParams);

        TextView status = new TextView(this);
        status.setText(R.string.starting_download);
        status.setTextSize(12f);
        status.setTextColor(0xFF64748B);
        status.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView pctLabel = new TextView(this);
        pctLabel.setText(R.string.progress_zero);
        pctLabel.setTextSize(12f);
        pctLabel.setTypeface(null, Typeface.BOLD);
        pctLabel.setTextColor(0xFF4F46E5);

        bottomRow.addView(status);
        bottomRow.addView(pctLabel);
        inner.addView(bottomRow);

        card.addView(inner);
        llResultsContainer.addView(card);

        return new DownloadCard(card, bar, status, pctLabel, badge);
    }

    private String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ───────────────────────── PROGRESS TRACKING ───────────────────────

    private void startPolling() {
        if (!polling) {
            polling = true;
            handler.post(progressRunnable);
        }
    }

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (activeDownloads.isEmpty()) {
                polling = false;
                return;
            }
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            Iterator<Map.Entry<Long, DownloadCard>> it = activeDownloads.entrySet().iterator();

            while (it.hasNext()) {
                Map.Entry<Long, DownloadCard> entry = it.next();
                long id = entry.getKey();
                DownloadCard ui = entry.getValue();

                DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
                try (Cursor c = dm.query(q)) {
                    if (c == null || !c.moveToFirst()) {
                        it.remove();
                        continue;
                    }
                    int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    long soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        ui.bar.setProgressDrawable(roundedProgressDrawable(0xFF10B981));
                        ui.bar.setProgress(100);
                        ui.status.setText(total > 0
                                ? getString(R.string.status_completed_with_size, formatBytes(total))
                                : getString(R.string.status_completed));
                        ui.status.setTextColor(0xFF10B981);
                        ui.pctLabel.setText(R.string.progress_full);
                        ui.pctLabel.setTextColor(0xFF10B981);

                        GradientDrawable greenBadge = new GradientDrawable();
                        greenBadge.setShape(GradientDrawable.OVAL);
                        greenBadge.setColor(0xFF10B981);
                        ui.badge.setBackground(greenBadge);
                        ui.badge.setText(R.string.badge_completed);

                        it.remove();
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        int reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                        ui.status.setText(getString(R.string.status_failed_with_reason, reason));
                        ui.status.setTextColor(0xFFEF4444);
                        ui.pctLabel.setText(R.string.progress_none);
                        ui.pctLabel.setTextColor(0xFFEF4444);
                        it.remove();
                    } else {
                        int pct = total > 0 ? (int) (soFar * 100 / total) : 0;
                        ui.bar.setProgress(pct);
                        ui.pctLabel.setText(getString(R.string.progress_percent, pct));
                        ui.status.setText(getString(
                                R.string.status_downloading,
                                formatBytes(soFar),
                                total > 0 ? formatBytes(total) : getString(R.string.file_unknown_size)));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "poll failed for " + id, e);
                }
            }

            if (!activeDownloads.isEmpty()) {
                handler.postDelayed(this, 500);
            } else {
                polling = false;
            }
        }
    };

    private void registerDownloadReceiver() {
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (id != -1L) finishDownload(id);
            }
        };
        // ACTION_DOWNLOAD_COMPLETE is a system broadcast → EXPORTED flag is required on API 33+/34.
        ContextCompat.registerReceiver(
                this, downloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED);
    }

    private void finishDownload(long id) {
        DownloadCard ui = activeDownloads.get(id);
        if (ui == null) return;
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
        try (Cursor c = dm.query(q)) {
            if (c != null && c.moveToFirst()) {
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    ui.bar.setProgressDrawable(roundedProgressDrawable(0xFF10B981));
                    ui.bar.setProgress(100);
                    ui.status.setText(total > 0
                            ? getString(R.string.status_completed_with_size, formatBytes(total))
                            : getString(R.string.status_completed));
                    ui.status.setTextColor(0xFF10B981);
                    ui.pctLabel.setText(R.string.progress_full);
                    ui.pctLabel.setTextColor(0xFF10B981);

                    GradientDrawable greenBadge = new GradientDrawable();
                    greenBadge.setShape(GradientDrawable.OVAL);
                    greenBadge.setColor(0xFF10B981);
                    ui.badge.setBackground(greenBadge);
                    ui.badge.setText("✓");
                } else if (status == DownloadManager.STATUS_FAILED) {
                    ui.status.setText(R.string.status_failed);
                    ui.status.setTextColor(0xFFEF4444);
                    ui.pctLabel.setText("—");
                    ui.pctLabel.setTextColor(0xFFEF4444);
                }
            }
        } catch (Exception ignored) {}
        activeDownloads.remove(id);
    }

    /** Rebuilds progress cards for downloads still running from a previous session. */
    private void restoreRunningDownloads() {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query q = new DownloadManager.Query();
        q.setFilterByStatus(DownloadManager.STATUS_RUNNING
                | DownloadManager.STATUS_PENDING
                | DownloadManager.STATUS_PAUSED);

        try (Cursor c = dm.query(q)) {
            while (c != null && c.moveToNext()) {
                long id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                String title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                if (title == null || title.isEmpty()) title = "Download #" + id;

                DownloadCard ui = addProgressCard(title, "", id, "");
                activeDownloads.put(id, ui);
            }
        } catch (Exception e) {
            Log.w(TAG, "restore failed", e);
        }
        if (!activeDownloads.isEmpty()) startPolling();
    }

    // ───────────────────────────── HELPERS ─────────────────────────────

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        double b = bytes;
        int u = 0;
        while (b >= 1024 && u < units.length - 1) { b /= 1024; u++; }
        return String.format(Locale.US, "%.1f %s", b, units[u]);
    }

    private void setLoadingState(boolean isLoading) {
        progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        btnFetch.setEnabled(!isLoading);
    }

    /** Removes finished cards but keeps cards for downloads still in flight. */
    private void clearFinishedCards() {
        for (int i = llResultsContainer.getChildCount() - 1; i >= 0; i--) {
            View child = llResultsContainer.getChildAt(i);
            Object tag = child.getTag();
            if (!(tag instanceof Long) || !activeDownloads.containsKey((Long) tag)) {
                llResultsContainer.removeViewAt(i);
            }
        }
        if (llResultsContainer.getChildCount() == 0) {
            tvResultsHeader.setVisibility(View.GONE);
        }
    }

    /**
     * Opens the downloaded file in whichever app the user picks from the
     * system chooser. Filtered by MIME type, so a .mp4 offers video players,
     * a .jpg offers image viewers, etc.
     */
    private void openFile(long downloadId) {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

        // 1. Confirm the download is really complete and grab its filename
        int status = -1;
        String fileName = null;
        DownloadManager.Query q = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor c = dm.query(q)) {
            if (c != null && c.moveToFirst()) {
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                fileName = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
            }
        } catch (Exception e) {
            Log.w(TAG, "query failed for " + downloadId, e);
        }

        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            Toast.makeText(this, R.string.file_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }

        // 2. Ask DownloadManager for a content:// URI we can share safely.
        //    No FileProvider / READ_EXTERNAL_STORAGE needed — we own the download.
        Uri fileUri;
        try {
            fileUri = dm.getUriForDownloadedFile(downloadId);
        } catch (SecurityException e) {
            Log.w(TAG, "getUriForDownloadedFile denied", e);
            fileUri = null;
        }
        if (fileUri == null) {
            Toast.makeText(this, R.string.file_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        // 3. Build a typed ACTION_VIEW and let the user choose
        String mime = mimeFromName(fileName);
        if (mime == null) mime = "*/*";

        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(fileUri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(Intent.createChooser(view, getString(R.string.open_with)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show();
        }
    }

    /** Maps a filename extension to a MIME type. Returns null if unknown. */
    private String mimeFromName(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.US);
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int sourceColor(String source) {
        if (source == null) return 0xFF4F46E5;
        String s = source.toLowerCase(Locale.US);
        if (s.contains("instagram")) return 0xFFE1306C;
        if (s.contains("tiktok"))    return 0xFF010101;
        if (s.contains("youtube"))   return 0xFFFF0000;
        if (s.contains("facebook"))  return 0xFF1877F2;
        if (s.contains("twitter") || s.equals("x.com")) return 0xFF0F172A;
        if (s.contains("pinterest")) return 0xFFE60023;
        return 0xFF4F46E5;
    }

    private String sourceInitial(String source) {
        if (source == null || source.isEmpty()) return getString(R.string.badge_media);
        String cleaned = source.replace(".com", "").trim();
        if (cleaned.isEmpty()) return getString(R.string.badge_media);
        return String.valueOf(Character.toUpperCase(cleaned.charAt(0)));
    }

    private Drawable roundedProgressDrawable(int color) {
        int r = dp(4);

        GradientDrawable track = new GradientDrawable();
        track.setShape(GradientDrawable.RECTANGLE);
        track.setCornerRadius(r);
        track.setColor(0xFFE5E7EB);

        GradientDrawable progress = new GradientDrawable();
        progress.setShape(GradientDrawable.RECTANGLE);
        progress.setCornerRadius(r);
        progress.setColor(color);

        ClipDrawable clip = new ClipDrawable(progress, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{track, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        return layers;
    }
}