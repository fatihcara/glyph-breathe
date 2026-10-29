package org.glyphbreathe.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** A deliberately narrow, root-only controller for one scalar Glyph brightness node. */
public final class MainActivity extends Activity {
    private static final String NODE = "/sys/class/leds/aw210xx_led/all_white_leds_br";
    private static final String BACKUP_DIR = "/data/adb/glyph_breathe";
    private static final String BACKUP = BACKUP_DIR + "/originals";
    private static final String PREFS = "glyph_breathe_recovery";
    private static final String PREF_ORIGINAL = "original_value";

    private static final int BG = Color.rgb(16, 19, 18);
    private static final int PANEL = Color.rgb(29, 34, 32);
    private static final int FG = Color.rgb(241, 245, 243);
    private static final int MUTED = Color.rgb(174, 187, 181);
    private static final int ACCENT = Color.rgb(185, 243, 228);

    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private android.content.SharedPreferences prefs;
    private TextView status;
    private TextView targetLabel;
    private SeekBar seek;
    private Button setButton;
    private Button offButton;
    private Button startButton;
    private Button stopButton;
    private Button restoreButton;
    private Button restoreAllButton;
    private Button refreshButton;

    private volatile boolean rootOk;
    private volatile boolean nodeOk;
    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile int maxBrightness = 255;
    private volatile int peak = 160;
    private volatile Thread animator;

    @Override public void onCreate(Bundle state) {
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

        page.addView(text("Glyph Breathe", 28, FG, true), lp());
        TextView subtitle = text("Root-powered brightness control", 14, MUTED, false);
        LinearLayout.LayoutParams sub = lp(); sub.topMargin = dp(4);
        page.addView(subtitle, sub);

        status = text("Checking root and Glyph node…", 14, FG, false);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        status.setBackground(panel());
        LinearLayout.LayoutParams statLp = lp(); statLp.topMargin = dp(18);
        page.addView(status, statLp);

        TextView pathTitle = text("Supported Glyph interface", 18, FG, true);
        LinearLayout.LayoutParams titleLp = lp(); titleLp.topMargin = dp(24);
        page.addView(pathTitle, titleLp);
        TextView path = text(NODE, 12, MUTED, false);
        path.setTextIsSelectable(true);
        LinearLayout.LayoutParams pathLp = lp(); pathLp.topMargin = dp(8);
        page.addView(path, pathLp);

        refreshButton = button("Check again");
        page.addView(refreshButton, buttonLp());

        TextView brightnessTitle = text("Brightness / breathing peak", 18, FG, true);
        LinearLayout.LayoutParams btLp = lp(); btLp.topMargin = dp(24);
        page.addView(brightnessTitle, btLp);
        targetLabel = text("Target level: 160", 16, ACCENT, true);
        LinearLayout.LayoutParams vlp = lp(); vlp.topMargin = dp(8);
        page.addView(targetLabel, vlp);
        seek = new SeekBar(this);
        seek.setMax(255);
        seek.setProgress(160);
        LinearLayout.LayoutParams seekLp = lp(); seekLp.topMargin = dp(4);
        page.addView(seek, seekLp);

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
                "Before any change, the first original value is saved in a root-protected backup. " +
                "Leaving this screen attempts restoration. Android cannot reliably run cleanup " +
                "during uninstall: restore first, or use restore-glyph.sh afterward.",
                13, MUTED, false);
        note.setPadding(dp(12), dp(12), dp(12), dp(12));
        note.setBackground(panel());
        LinearLayout.LayoutParams noteLp = lp(); noteLp.topMargin = dp(20);
        page.addView(note, noteLp);

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                targetLabel.setText("Target level: " + value + " / " + maxBrightness);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        refreshButton.setOnClickListener(v -> refreshNode());
        setButton.setOnClickListener(v -> applyBrightness(seek.getProgress()));
        offButton.setOnClickListener(v -> applyBrightness(0));
        startButton.setOnClickListener(v -> startBreathing());
        stopButton.setOnClickListener(v -> stopAndRestore());
        restoreButton.setOnClickListener(v -> stopAndRestore());
        restoreAllButton.setOnClickListener(v -> stopAndRestore());

        setContentView(scroll);
        setControls(false);
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private Button button(String s) {
        Button b = new Button(this); b.setText(s); b.setTextColor(FG); b.setAllCaps(false); return b;
    }
    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams p = lp(); p.topMargin = dp(7); return p;
    }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private GradientDrawable panel() {
        GradientDrawable d = new GradientDrawable(); d.setColor(PANEL); d.setCornerRadius(dp(14)); return d;
    }

    private void setControls(boolean enabled) {
        if (setButton == null) return;
        boolean can = enabled && rootOk && nodeOk;
        setButton.setEnabled(can);
        offButton.setEnabled(can);
        startButton.setEnabled(can && !running);
        stopButton.setEnabled(rootOk);
        restoreButton.setEnabled(rootOk);
        restoreAllButton.setEnabled(rootOk);
        refreshButton.setEnabled(true);
        seek.setEnabled(can);
    }

    private void refreshNode() {
        status.setText("Checking root access and the single supported Glyph node…");
        setControls(false);
        io.execute(() -> {
            String id = rootCommand("id");
            boolean root = id != null && id.contains("uid=0");
            String present = root ? rootCommand("[ -f '" + NODE + "' ] && echo PRESENT || echo MISSING") : null;
            boolean found = present != null && present.contains("PRESENT") && !present.contains("MISSING");
            int published = found ? readPublishedMax() : -1;
            int max = published > 0 ? Math.min(65535, published) : 255;
            runOnUiThread(() -> {
                rootOk = root; nodeOk = found; maxBrightness = max;
                seek.setMax(max);
                seek.setProgress(Math.min(seek.getProgress(), max));
                targetLabel.setText("Target level: " + seek.getProgress() + " / " + max);
                if (!root) status.setText("Root unavailable. Approve the superuser prompt, then tap Check again.");
                else if (!found) status.setText("Root works, but the expected node is absent. No LED was changed.");
                else status.setText("Root available · Glyph node found · reported max " + max);
                setControls(root && found);
            });
        });
    }

    private void applyBrightness(int value) {
        if (!rootOk || !nodeOk || value < 0 || value > maxBrightness) {
            toast("Root, Glyph node, or brightness range is unavailable.");
            return;
        }
        setControls(false);
        io.execute(() -> {
            boolean ok = ensureBackup() && writeValue(value);
            runOnUiThread(() -> {
                setControls(rootOk && nodeOk);
                toast(ok ? (value == 0 ? "Glyph off. Original value saved." : "Brightness applied; original value saved.")
                        : "Write refused or failed. The recovery backup is retained when available.");
            });
        });
    }

    private void startBreathing() {
        if (!rootOk || !nodeOk || running) { toast("Root or supported Glyph node unavailable."); return; }
        peak = Math.max(1, Math.min(seek.getProgress(), maxBrightness));
        setControls(false);
        io.execute(() -> {
            boolean saved = ensureBackup();
            runOnUiThread(() -> {
                if (!saved) {
                    setControls(rootOk && nodeOk);
                    toast("Could not save a verified recovery point; animation not started.");
                    return;
                }
                stopRequested = false;
                running = true;
                setControls(true);
                animator = new Thread(this::animate, "GlyphBreatheAnimation");
                animator.start();
            });
        });
    }

    private void animate() {
        RootPipe pipe = null;
        try {
            pipe = new RootPipe();
            int top = Math.max(1, Math.min(peak, maxBrightness));
            int low = Math.min(4, top);
            int step = Math.max(1, top / 25);
            while (!mustStop()) {
                for (int v = low; v <= top && !mustStop(); v += step) {
                    pipe.write(v); Thread.sleep(50);
                }
                if (mustStop()) break;
                pipe.write(top);
                for (int v = top; v >= low && !mustStop(); v -= step) {
                    pipe.write(v); Thread.sleep(50);
                }
                if (mustStop()) break;
                pipe.write(0); Thread.sleep(2000);
            }
        } catch (Exception e) {
            runOnUiThread(() -> toast("Animation stopped because the root writer or Glyph driver failed."));
        } finally {
            if (pipe != null) pipe.close();
            running = false;
            boolean restored = restoreSaved();
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    setControls(rootOk && nodeOk);
                    if (restored) toast("Animation stopped; original value restored.");
                }
            });
        }
    }

    private boolean mustStop() { return stopRequested || Thread.currentThread().isInterrupted(); }

    private void stopAndRestore() {
        stopRequested = true;
        Thread t = animator;
        if (t != null) t.interrupt();
        io.execute(() -> {
            if (t != null) {
                try { t.join(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            boolean restored = restoreSaved();
            runOnUiThread(() -> {
                setControls(rootOk && nodeOk);
                toast(restored ? "Saved original brightness restored." :
                        "No valid saved value was found, or restore failed. Backup retained where possible.");
            });
        });
    }

    /** Saves the first original value and refuses any unexpected backup record. */
    private synchronized boolean ensureBackup() {
        if (!rootOk || !nodeOk || !isSupportedNode(NODE)) return false;
        String listing = rootCommand("[ -f '" + BACKUP + "' ] && cat '" + BACKUP + "' || echo GLYPH_NO_BACKUP");
        if (listing == null) return false;
        Integer existing = parseBackup(listing);
        if (existing != null) {
            if (!validSavedValue(existing)) return false;
            prefs.edit().putInt(PREF_ORIGINAL, existing).commit();
            return true;
        }
        if (!isNoBackup(listing)) return false;

        Integer local = prefs.contains(PREF_ORIGINAL) ? Integer.valueOf(prefs.getInt(PREF_ORIGINAL, -1)) : null;
        Integer current = readCurrent();
        int original = local != null && local >= 0 ? local : (current == null ? -1 : current);
        if (!validSavedValue(original)) return false;

        String cmd = "umask 077; mkdir -p '" + BACKUP_DIR + "' && chmod 700 '" + BACKUP_DIR + "' && " +
                "if [ -s '" + BACKUP + "' ]; then exit 18; fi; " +
                "printf '%s|%s\\n' '" + NODE + "' '" + original + "' > '" + BACKUP + ".tmp' && " +
                "chmod 600 '" + BACKUP + ".tmp' && mv '" + BACKUP + ".tmp' '" + BACKUP + "' && echo GLYPH_BACKUP_OK";
        String out = rootCommand(cmd);
        if (out == null || !out.contains("GLYPH_BACKUP_OK")) return false;
        return prefs.edit().putInt(PREF_ORIGINAL, original).commit();
    }

    private synchronized boolean restoreSaved() {
        String listing = rootCommand("[ -f '" + BACKUP + "' ] && cat '" + BACKUP + "' || echo GLYPH_NO_BACKUP");
        if (listing == null) return false;
        Integer saved = parseBackup(listing);
        if (saved == null) {
            if (!isNoBackup(listing)) return false;
            if (!prefs.contains(PREF_ORIGINAL)) return false;
            saved = prefs.getInt(PREF_ORIGINAL, -1);
        }
        if (!validSavedValue(saved) || !writeValue(saved)) return false;
        String clear = rootCommand("rm -f '" + BACKUP + "' '" + BACKUP + ".tmp' && rmdir '" + BACKUP_DIR + "' 2>/dev/null; echo GLYPH_BACKUP_CLEARED");
        if (clear == null || !clear.contains("GLYPH_BACKUP_CLEARED")) return false;
        prefs.edit().remove(PREF_ORIGINAL).commit();
        return true;
    }

    private Integer parseBackup(String listing) {
        Integer result = null;
        for (String line : listing.split("\\r?\\n")) {
            if (line.trim().isEmpty() || line.trim().equals("GLYPH_NO_BACKUP")) continue;
            int split = line.lastIndexOf('|');
            if (split <= 0 || !isSupportedNode(line.substring(0, split).trim())) return null;
            String value = line.substring(split + 1).trim();
            if (value.isEmpty() || value.length() > 5) return null;
            for (int i = 0; i < value.length(); i++) if (!Character.isDigit(value.charAt(i))) return null;
            if (result != null) return null;
            try { result = Integer.valueOf(value); } catch (Exception e) { return null; }
        }
        return result;
    }

    private boolean isNoBackup(String s) {
        return s.trim().isEmpty() || s.trim().equals("GLYPH_NO_BACKUP");
    }

    private Integer readCurrent() {
        String out = rootCommand("[ -f '" + NODE + "' ] && cat '" + NODE + "' || exit 9");
        if (out == null) return null;
        try { return Integer.valueOf(out.trim()); } catch (Exception e) { return null; }
    }

    private int readPublishedMax() {
        String out = rootCommand("[ -r /sys/class/leds/aw210xx_led/max_brightness ] && cat /sys/class/leds/aw210xx_led/max_brightness || echo NO_MAX");
        if (out == null) return -1;
        try {
            int value = Integer.parseInt(out.trim());
            return value > 0 ? Math.min(65535, value) : -1;
        } catch (Exception e) { return -1; }
    }

    private boolean validSavedValue(int value) {
        if (!isSupportedNode(NODE) || value < 0 || value > 65535) return false;
        int published = readPublishedMax();
        return published < 0 || value <= published;
    }

    private boolean writeValue(int value) {
        if (!isSupportedNode(NODE) || value < 0 || value > 65535) return false;
        int published = readPublishedMax();
        if (published > 0 && value > published) return false;
        String out = rootCommand("[ -f '" + NODE + "' ] && printf '%s\\n' '" + value + "' > '" + NODE + "' && echo GLYPH_WRITE_OK || exit 7");
        return out != null && out.contains("GLYPH_WRITE_OK");
    }

    private static boolean isSupportedNode(String node) { return NODE.equals(node); }

    private String rootCommand(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            try (java.io.InputStream in = process.getInputStream();
                 java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) != -1) bytes.write(buf, 0, n);
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return null;
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private final class RootPipe {
        private final Process process;
        private final BufferedWriter writer;
        RootPipe() throws Exception {
            String script = "while IFS= read -r v; do case \"$v\" in ''|*[!0-9]*) continue;; esac; " +
                    "printf '%s\\n' \"$v\" > '" + NODE + "' || { echo GLYPH_WRITE_FAILED; exit 9; }; done";
            process = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            if (!process.isAlive()) throw new IllegalStateException("Root writer exited immediately");
        }
        synchronized void write(int value) throws Exception {
            if (value < 0 || value > maxBrightness || value > 65535 || !process.isAlive())
                throw new IllegalStateException("Invalid brightness or root writer exited");
            writer.write(Integer.toString(value)); writer.newLine(); writer.flush();
        }
        synchronized void close() {
            try { writer.close(); } catch (Exception ignored) { }
            try {
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            } catch (Exception e) { process.destroyForcibly(); }
        }
    }

    @Override protected void onStop() {
        super.onStop();
        stopRequested = true;
        Thread t = animator;
        if (t != null) t.interrupt();
        Thread restore = new Thread(() -> {
            if (t != null) {
                try { t.join(4500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            restoreSaved();
        }, "GlyphBreatheLifecycleRestore");
        restore.setDaemon(true);
        restore.start();
    }

    @Override protected void onDestroy() {
        stopRequested = true;
        Thread t = animator;
        if (t != null) t.interrupt();
        io.shutdown();
        super.onDestroy();
    }
}
