package org.glyphbreathe.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Root-only controller for one specifically allow-listed Glyph scalar brightness node.
 * No services, receivers, networking, or boot persistence are used.
 */
public final class MainActivity extends Activity {
    private static final String NODE =
            "/sys/class/leds/aw210xx_led/all_white_leds_br";
    private static final String BACKUP_DIR = "/data/adb/glyph_breathe";
    private static final String BACKUP_FILE = BACKUP_DIR + "/originals";
    private static final String PREFS = "glyph_restore_point";
    private static final String PREF_ORIGINAL = "original_value";

    private static final int BG = Color.rgb(16, 19, 18);
    private static final int PANEL = Color.rgb(29, 34, 32);
    private static final int FG = Color.rgb(241, 245, 243);
    private static final int MUTED = Color.rgb(174, 187, 181);
    private static final int ACCENT = Color.rgb(185, 243, 228);

    private android.content.SharedPreferences prefs;
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private TextView rootStatus;
    private TextView valueLabel;
    private TextView nodeLabel;
    private SeekBar seekBar;
    private Button setButton;
    private Button offButton;
    private Button startButton;
    private Button stopButton;
    private Button restoreButton;
    private Button restoreAllButton;
    private Button refreshButton;

    private volatile boolean rootAvailable;
    private volatile boolean nodeAvailable;
    private volatile boolean animationRunning;
    private volatile boolean stopAnimation;
    private volatile Thread animationThread;
    private volatile int maxBrightness = 255;
    private volatile int animationPeak = 160;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
        refreshNode();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(page);

        TextView title = text("Glyph Breathe", 28, FG, true);
        page.addView(title, lp());
        TextView subtitle = text("Root-powered brightness control", 14, MUTED, false);
        LinearLayout.LayoutParams subLp = lp();
        subLp.topMargin = dp(4);
        page.addView(subtitle, subLp);

        rootStatus = text("Checking root and Glyph interface…", 14, FG, false);
        rootStatus.setPadding(dp(12), dp(12), dp(12), dp(12));
        rootStatus.setBackground(roundBg(PANEL));
        LinearLayout.LayoutParams statusLp = lp();
        statusLp.topMargin = dp(18);
        page.addView(rootStatus, statusLp);

        TextView section = text("Supported interface", 18, FG, true);
        LinearLayout.LayoutParams sectionLp = lp();
        sectionLp.topMargin = dp(24);
        page.addView(section, sectionLp);
        nodeLabel = text(NODE, 12, MUTED, false);
        nodeLabel.setTextIsSelectable(true);
        LinearLayout.LayoutParams nodeLp = lp();
        nodeLp.topMargin = dp(8);
        page.addView(nodeLabel, nodeLp);
        refreshButton = button("Check again");
        page.addView(refreshButton, buttonLp());

        TextView brightnessTitle = text("Brightness / breathing peak", 18, FG, true);
        LinearLayout.LayoutParams btLp = lp();
        btLp.topMargin = dp(24);
        page.addView(brightnessTitle, btLp);

        valueLabel = text("Target level: 160", 16, ACCENT, true);
        LinearLayout.LayoutParams vlp = lp();
        vlp.topMargin = dp(8);
        page.addView(valueLabel, vlp);

        seekBar = new SeekBar(this);
        seekBar.setMax(255);
        seekBar.setProgress(160);
        LinearLayout.LayoutParams slp = lp();
        slp.topMargin = dp(5);
        page.addView(seekBar, slp);

        setButton = button("Set brightness");
        offButton = button("Turn Glyph off");
        startButton = button("Start breathing");
        stopButton = button("Stop animation");
        restoreButton = button("Restore original brightness");
        restoreAllButton = button("Restore all saved values");
        page.addView(setButton, buttonLp());
        page.addView(offButton, buttonLp());
        page.addView(startButton, buttonLp());
        page.addView(stopButton, buttonLp());
        page.addView(restoreButton, buttonLp());
        page.addView(restoreAllButton, buttonLp());

        TextView note = text(
                "Before any write, the original value is saved in a root-protected backup. " +
                "Leaving the app attempts restoration. Android does not run app cleanup " +
                "reliably during uninstall; use Restore All first or the separate recovery script.",
                13, MUTED, false);
        note.setPadding(dp(12), dp(12), dp(12), dp(12));
        note.setBackground(roundBg(PANEL));
        LinearLayout.LayoutParams noteLp = lp();
        noteLp.topMargin = dp(20);
        page.addView(note, noteLp);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                valueLabel.setText("Target level: " + value + " / " + maxBrightness);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        refreshButton.setOnClickListener(v -> refreshNode());
        setButton.setOnClickListener(v -> setBrightness(seekBar.getProgress()));
        offButton.setOnClickListener(v -> setBrightness(0));
        startButton.setOnClickListener(v -> startBreathing());
        stopButton.setOnClickListener(v -> stopAndRestore());
        restoreButton.setOnClickListener(v -> restoreOne());
        restoreAllButton.setOnClickListener(v -> restoreAll());

        setContentView(scroll);
        setControls(false);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(FG);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams p = lp();
        p.topMargin = dp(7);
        return p;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable roundBg(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(14));
        return d;
    }

    private void setControls(boolean enabled) {
        if (setButton == null) return;
        boolean canControl = enabled && rootAvailable && nodeAvailable;
        setButton.setEnabled(canControl);
        offButton.setEnabled(canControl);
        startButton.setEnabled(canControl && !animationRunning);
        stopButton.setEnabled(canControl);
        restoreButton.setEnabled(rootAvailable);
        restoreAllButton.setEnabled(rootAvailable);
        refreshButton.setEnabled(true);
        seekBar.setEnabled(canControl);
    }

    private void refreshNode() {
        rootStatus.setText("Checking root access and the single supported Glyph node…");
        setControls(false);
        io.execute(() -> {
            String who = rootCommand("id");
            boolean root = who != null && who.contains("uid=0");
            String found = root ? rootCommand(
                    "[ -f '" + NODE + "' ] && echo GLYPH_NODE_PRESENT || echo GLYPH_NODE_MISSING") : null;
            boolean exists = found != null && found.contains("GLYPH_NODE_PRESENT");
            int publishedMax = exists ? readPublishedMax() : -1;
            int max = publishedMax > 0 ? Math.min(65535, publishedMax) : 255;
            runOnUiThread(() -> {
                rootAvailable = root;
                nodeAvailable = exists;
                maxBrightness = max;
                seekBar.setMax(max);
                int cur = Math.min(seekBar.getProgress(), max);
                seekBar.setProgress(cur);
                valueLabel.setText("Target level: " + cur + " / " + max);
                if (!root) {
                    rootStatus.setText("Root unavailable. Approve the superuser prompt and press Check again.");
                } else if (!exists) {
                    rootStatus.setText("Root is available, but the expected node is absent. No LED was changed.");
                } else {
                    rootStatus.setText("Root available · supported Glyph node found · max " + max);
                }
                setControls(root && exists);
            });
        });
    }

    private void setBrightness(int value) {
        if (!canWrite(value)) {
            toast("Root or the supported Glyph node is unavailable, or the value is invalid.");
            return;
        }
        setControls(false);
        io.execute(() -> {
            boolean saved = saveOriginalSync();
            boolean ok = saved && writeValue(value);
            runOnUiThread(() -> {
                setControls(true);
                toast(ok ? (value == 0 ? "Glyph turned off. Original value is saved." : "Brightness applied.")
                        : "Write refused or failed. No new LED value was applied unless the driver rejected a write.");
            });
        });
    }

    private boolean canWrite(int value) {
        return rootAvailable && nodeAvailable && value >= 0 && value <= 65535
                && (readPublishedMax() < 0 || value <= readPublishedMax());
    }

    private void startBreathing() {
        if (!rootAvailable || !nodeAvailable || animationRunning) {
            toast("Root or the supported Glyph node is unavailable.");
            return;
        }
        animationPeak = Math.max(1, Math.min(seekBar.getProgress(), maxBrightness));
        setControls(false);
        io.execute(() -> {
            boolean saved = saveOriginalSync();
            runOnUiThread(() -> {
                if (!saved) {
                    setControls(true);
                    toast("Could not save a verified recovery point. Animation was not started.");
                    return;
                }
                stopAnimation = false;
                animationRunning = true;
                setControls(true);
                startButton.setEnabled(false);
                animationThread = new Thread(this::runAnimation, "GlyphBreatheAnimation");
                animationThread.start();
            });
        });
    }

    private void runAnimation() {
        RootPipe pipe = null;
        boolean restored = false;
        try {
            pipe = new RootPipe();
            int peak = Math.max(1, Math.min(animationPeak, maxBrightness));
            int low = Math.min(4, peak);
            int step = Math.max(1, peak / 25);
            while (!stopAnimation && !Thread.currentThread().isInterrupted()) {
                for (int i = low; i <= peak && !shouldStop(); i += step) {
                    pipe.write(i);
                    Thread.sleep(50);
                }
                if (shouldStop()) break;
                pipe.write(peak);
                for (int i = peak; i >= low && !shouldStop(); i -= step) {
                    pipe.write(i);
                    Thread.sleep(50);
                }
                if (shouldStop()) break;
                pipe.write(0);
                Thread.sleep(2000);
            }
        } catch (Exception e) {
            runOnUiThread(() -> toast("Animation stopped: root writer or Glyph driver failed."));
        } finally {
            if (pipe != null) pipe.close();
            animationRunning = false;
            // A dedicated thread restores even if the animation writer fails unexpectedly.
            restoreOriginalSync();
            restored = true;
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    setControls(rootAvailable && nodeAvailable);
                    toast("Animation stopped; original brightness restoration was attempted.");
                }
            });
        }
    }

    private boolean shouldStop() {
        return stopAnimation || Thread.currentThread().isInterrupted();
    }

    private void stopAndRestore() {
        stopAnimation = true;
        Thread t = animationThread;
        if (t != null) t.interrupt();
        io.execute(() -> {
            if (t != null && t != Thread.currentThread()) {
                try { t.join(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            boolean ok = restoreOriginalSync();
            runOnUiThread(() -> {
                setControls(rootAvailable && nodeAvailable);
                toast(ok ? "Original brightness restored." : "No valid saved value was found, or restoration failed. Backup retained where possible.");
            });
        });
    }

    private void restoreOne() {
        stopAnimation = true;
        Thread t = animationThread;
        if (t != null) t.interrupt();
        io.execute(() -> {
            if (t != null) {
                try { t.join(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            boolean ok = restoreOriginalSync();
            runOnUiThread(() -> {
                setControls(rootAvailable && nodeAvailable);
                toast(ok ? "Original brightness restored." : "No valid saved value was found, or restoration failed. Backup retained where possible.");
            });
        });
    }

    private void restoreAll() {
        stopAnimation = true;
        Thread t = animationThread;
        if (t != null) t.interrupt();
        io.execute(() -> {
            if (t != null) {
                try { t.join(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            boolean ok = restoreOriginalSync();
            runOnUiThread(() -> {
                setControls(rootAvailable && nodeAvailable);
                toast(ok ? "All saved Glyph values restored." : "No valid saved value was found, or restoration failed. Backup retained where possible.");
            });
        });
    }

    /**
     * Save the first original value, never the current animated value.
     * The only accepted node is NODE; unknown backup records are rejected.
     */
    private boolean saveOriginalSync() {
        if (!rootAvailable || !nodeAvailable || !isSupportedNode(NODE)) return false;
        String listing = rootCommand("[ -f '" + BACKUP_FILE + "' ] && cat '" + BACKUP_FILE + "' || echo GLYPH_NO_BACKUP");
        if (listing == null) return false;
        String saved = parseSavedValue(listing);
        if (saved != null) {
            try {
                int original = Integer.parseInt(saved);
                if (!validValue(original)) return false;
                prefs.edit().putInt(PREF_ORIGINAL, original).commit();
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        if (!listing.trim().equals("GLYPH_NO_BACKUP") && !listing.trim().isEmpty()) {
            return false;
        }

        Integer localOriginal = prefs.contains(PREF_ORIGINAL) ? prefs.getInt(PREF_ORIGINAL, -1) : null;
        Integer current = readCurrent();
        int original = localOriginal != null && localOriginal >= 0 ? localOriginal
                : (current == null ? -1 : current);
        if (!validValue(original)) return false;

        String command = "umask 077; mkdir -p '" + BACKUP_DIR + "' && chmod 700 '" + BACKUP_DIR + "' && " +
                "if [ -s '" + BACKUP_FILE + "' ]; then exit 18; fi; " +
                "printf '%s|%s\\n' '" + NODE + "' '" + original + "' > '" + BACKUP_FILE + ".tmp' && " +
                "chmod 600 '" + BACKUP_FILE + ".tmp' && mv '" + BACKUP_FILE + ".tmp' '" + BACKUP_FILE + "' && echo GLYPH_BACKUP_OK";
        String result = rootCommand(command);
        if (result == null || !result.contains("GLYPH_BACKUP_OK")) return false;
        return prefs.edit().putInt(PREF_ORIGINAL, original).commit();
    }

    private String parseSavedValue(String listing) {
        String found = null;
        for (String line : listing.split("\\r?\\n")) {
            if (line.trim().isEmpty() || line.trim().equals("GLYPH_NO_BACKUP")) continue;
            int sep = line.lastIndexOf('|');
            if (sep <= 0) return null;
            String path = line.substring(0, sep).trim();
            String value = line.substring(sep + 1).trim();
            if (!isSupportedNode(path) || value.isEmpty()) return null;
            for (int i = 0; i < value.length(); i++) if (!Character.isDigit(value.charAt(i))) return null;
            if (found != null) return null;
            found = value;
        }
        return found;
    }

    private boolean restoreOriginalSync() {
        Integer saved = null;
        String listing = rootCommand("[ -f '" + BACKUP_FILE + "' ] && cat '" + BACKUP_FILE + "' || echo GLYPH_NO_BACKUP");
        if (listing == null) return false;
        String fromFile = parseSavedValue(listing);
        if (fromFile != null) {
            try { saved = Integer.parseInt(fromFile); } catch (Exception ignored) { return false; }
        } else if (!listing.trim().equals("GLYPH_NO_BACKUP") && !listing.trim().isEmpty()) {
            return false;
        }
        if (saved == null && prefs.contains(PREF_ORIGINAL)) saved = prefs.getInt(PREF_ORIGINAL, -1);
        if (saved == null || !validValue(saved)) return false;
        if (!writeValue(saved)) return false;

        String clear = rootCommand("rm -f '" + BACKUP_FILE + "' '" + BACKUP_FILE + ".tmp' && rmdir '" + BACKUP_DIR + "' 2>/dev/null; echo GLYPH_BACKUP_CLEARED");
        if (clear == null || !clear.contains("GLYPH_BACKUP_CLEARED")) return false;
        prefs.edit().remove(PREF_ORIGINAL).commit();
        return true;
    }

    private Integer readCurrent() {
        String out = rootCommand("[ -f '" + NODE + "' ] && cat '" + NODE + "' || exit 9");
        if (out == null) return null;
        try { return Integer.parseInt(out.trim()); } catch (Exception e) { return null; }
    }

    private int readPublishedMax() {
        String out = rootCommand("if [ -r /sys/class/leds/aw210xx_led/max_brightness ]; then cat /sys/class/leds/aw210xx_led/max_brightness; else echo NO_MAX; fi");
        if (out == null) return -1;
        try {
            int value = Integer.parseInt(out.trim());
            return value > 0 ? Math.min(65535, value) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean validValue(int value) {
        if (!isSupportedNode(NODE) || value < 0 || value > 65535) return false;
        int published = readPublishedMax();
        return published < 0 || value <= published;
    }

    private boolean writeValue(int value) {
        if (!validValue(value)) return false;
        String out = rootCommand("[ -f '" + NODE + "' ] && printf '%s\\n' '" + value + "' > '" + NODE + "' && echo GLYPH_WRITE_OK || exit 7");
        return out != null && out.contains("GLYPH_WRITE_OK");
    }

    private static boolean isSupportedNode(String node) {
        return NODE.equals(node);
    }

    private String rootCommand(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            try (java.io.InputStream input = process.getInputStream();
                 java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
                byte[] data = new byte[1024];
                int n;
                while ((n = input.read(data)) != -1) buffer.write(data, 0, n);
                return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return null;
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private final class RootPipe {
        private final Process process;
        private final BufferedWriter writer;

        RootPipe() throws Exception {
            String script = "while IFS= read -r v; do " +
                    "case \"$v\" in ''|*[!0-9]*) continue;; esac; " +
                    "printf '%s\\n' \"$v\" > '" + NODE + "' || { echo GLYPH_WRITE_FAILED; exit 9; }; " +
                    "done";
            process = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            if (!process.isAlive()) throw new IllegalStateException("Root writer exited immediately");
        }

        synchronized void write(int value) throws Exception {
            if (!validValue(value) || !process.isAlive()) throw new IllegalStateException("Invalid value or root writer exited");
            writer.write(Integer.toString(value));
            writer.newLine();
            writer.flush();
        }

        synchronized void close() {
            try { writer.close(); } catch (Exception ignored) { }
            try {
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            } catch (Exception e) {
                process.destroyForcibly();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopAnimation = true;
        Thread t = animationThread;
        if (t != null) t.interrupt();
        Thread restore = new Thread(() -> {
            if (t != null && t != Thread.currentThread()) {
                try { t.join(4500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            restoreOriginalSync();
        }, "GlyphBreatheLifecycleRestore");
        restore.setDaemon(true);
        restore.start();
    }

    @Override
    protected void onDestroy() {
        stopAnimation = true;
        Thread t = animationThread;
        if (t != null) t.interrupt();
        io.shutdown();
        super.onDestroy();
    }
}
