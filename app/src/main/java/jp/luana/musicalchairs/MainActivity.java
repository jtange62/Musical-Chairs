package jp.luana.musicalchairs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private enum State { IDLE, PLAYING, STOPPED, PAUSED }

    private static final int PICK_MUSIC = 42;
    private static final int COLOR_IDLE = Color.parseColor("#37474F");
    private static final int COLOR_PLAY = Color.parseColor("#2E7D32");
    private static final int COLOR_STOP = Color.parseColor("#C62828");
    private static final int COLOR_PAUSE = Color.parseColor("#F9A825");
    // One colour per song in "Switch songs" mode (no red or yellow, those mean stop and pause)
    private static final String[] SONG_COLORS = {"#2E7D32", "#1565C0", "#6A1B9A", "#EF6C00",
            "#00838F", "#AD1457", "#4E342E", "#283593"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final List<Uri> songs = new ArrayList<>();
    // Where each song left off, for "Switch songs" mode (song index -> ms)
    private final Map<Integer, Integer> positions = new HashMap<>();

    private MediaPlayer player;
    private int songIndex = 0;
    private State state = State.IDLE;
    private State pausedFrom = State.IDLE;
    private int countdown = 0;
    private boolean keepingAlive = false;

    // Settings (seconds)
    private int minPlay = 10;
    private int maxPlay = 30;
    private int restTime = 10;
    private boolean autoRestart = true;
    private boolean switchMode = false;  // "Switch songs" instead of musical chairs
    private int switchPause = 3;         // STOP screen between songs, 0 = fade straight over
    // "Switch songs": where each song starts - "catchy" part, "random" spot, or "resume" where it left off
    private String startAt = "catchy";
    // Per song: {"starts": [seconds of the catchy part], "mark": seconds chosen by hand}
    private JSONObject highlights = new JSONObject();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean analyzing = false;
    private int analyzeDone = 0;
    private int analyzeTotal = 0;

    private SharedPreferences prefs;

    private LinearLayout root;
    private TextView bigText;
    private TextView subText;
    private TextView songText;
    private SeekBar seekBar;
    private TextView timeText;
    private boolean userSeeking = false;
    private Button mainButton;
    private Button chairsButton;
    private Button switchButton;
    private LinearLayout minRow;
    private LinearLayout maxRow;
    private LinearLayout restRow;
    private LinearLayout pauseRow;
    // Redraw each stepper's value after a reset to defaults
    private final List<Runnable> stepperRefreshers = new ArrayList<>();
    private Switch autoSwitch;
    private LinearLayout startRow;
    private Button startCatchy;
    private Button startRandom;
    private Button startResume;
    private LinearLayout markRow;
    private Button unmarkButton;

    // Sound pads
    private static final int PADS = 8;
    private static final int RECORD_PERMISSION = 2;
    private static final String[] PAD_COLORS = {"#E91E63", "#8E24AA", "#3949AB", "#039BE5",
            "#00897B", "#7CB342", "#FB8C00", "#6D4C41"};
    private final Button[] padButtons = new Button[PADS];
    private final int[] padSounds = new int[PADS];  // SoundPool sound for each pad, 0 = empty pad
    private SoundPool soundPool;
    private int pendingRecordPad = -1;  // pad to record once the microphone is allowed

    // The pad being recorded (the recording thread checks stop/cancel)
    private static final class Recording {
        final int pad;
        final long started = SystemClock.uptimeMillis();
        volatile boolean stop;
        volatile boolean cancel;
        Recording(int pad) { this.pad = pad; }
    }
    private Recording recording;
    private final Runnable recordTick = new Runnable() {
        @Override public void run() {
            showPads();
            if (recording != null && !recording.stop) handler.postDelayed(this, 250);
        }
    };

    private final Runnable stopRunnable = this::switchTime;
    private int fadeStep = 0;
    private final Runnable fadeRunnable = new Runnable() {
        @Override public void run() {
            fadeStep++;
            float v = Math.max(0f, 1f - fadeStep / 10f);
            if (player != null) player.setVolume(v, v);
            if (fadeStep >= 10) {
                switchSong();
            } else {
                handler.postDelayed(this, 40);
            }
        }
    };
    private final Runnable progressRunnable = new Runnable() {
        @Override public void run() {
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };
    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() {
            countdown--;
            if (countdown <= 0) {
                startPlaying();
            } else {
                updateUi();
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        loadSettings();
        String saved = prefs.getString("songs", "");
        if (!saved.isEmpty()) {
            for (String s : saved.split("\n")) songs.add(Uri.parse(s));
        }

        // Lets the "game in progress" notification show on Android 13+
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        buildUi();
        setUpPads();
        if (!songs.isEmpty()) preparePlayer(0);
        updateUi();
        if (switchMode && startAt.equals("catchy")) findCatchyParts();
    }

    // Reads the saved settings; anything not saved gets its default
    private void loadSettings() {
        minPlay = prefs.getInt("minPlay", 10);
        maxPlay = prefs.getInt("maxPlay", 30);
        restTime = prefs.getInt("restTime", 10);
        autoRestart = prefs.getBoolean("autoRestart", true);
        switchMode = prefs.getBoolean("switchMode", false);
        switchPause = prefs.getInt("switchPause", 3);
        startAt = prefs.getString("startAt", "catchy");
        try {
            highlights = new JSONObject(prefs.getString("highlights", "{}"));
        } catch (JSONException ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(progressRunnable);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(progressRunnable);
        super.onPause();
    }

    // ---------- UI ----------

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView text(float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, float sp) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setAllCaps(false);
        return b;
    }

    private interface IntSetter { void set(int v); }
    private interface IntGetter { int get(); }

    private LinearLayout stepper(String label, IntGetter getter, IntSetter setter, int min, int max, int step) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView name = text(18, false);
        name.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        name.setText(label);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView value = text(20, true);
        value.setText(getter.get() + " s");

        Button minus = button("−", 22);
        Button plus = button("+", 22);
        minus.setOnClickListener(v -> {
            setter.set(Math.max(min, getter.get() - step));
            value.setText(getter.get() + " s");
            saveSettings();
        });
        plus.setOnClickListener(v -> {
            setter.set(Math.min(max, getter.get() + step));
            value.setText(getter.get() + " s");
            saveSettings();
        });

        row.addView(minus, new LinearLayout.LayoutParams(dp(56), dp(56)));
        row.addView(value, new LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(plus, new LinearLayout.LayoutParams(dp(56), dp(56)));
        stepperRefreshers.add(() -> value.setText(getter.get() + " s"));
        return row;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(20));
        scroll.addView(root);

        bigText = text(64, true);
        root.addView(bigText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        subText = text(28, true);
        root.addView(subText);

        songText = text(15, false);
        songText.setPadding(0, dp(8), 0, dp(4));
        root.addView(songText);

        seekBar = new SeekBar(this);
        seekBar.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
        seekBar.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && player != null) {
                    timeText.setText(formatTime(progress) + " / " + formatTime(player.getDuration()));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                if (player != null) player.seekTo(sb.getProgress());
            }
        });
        root.addView(seekBar);

        timeText = text(14, false);
        timeText.setPadding(0, 0, 0, dp(16));
        root.addView(timeText);

        markRow = new LinearLayout(this);
        markRow.setOrientation(LinearLayout.HORIZONTAL);
        markRow.setPadding(0, 0, 0, dp(8));
        Button mark = button("📍 Start this song here", 15);
        mark.setOnClickListener(v -> markSpot());
        unmarkButton = button("Undo", 15);
        unmarkButton.setOnClickListener(v -> unmarkSpot());
        markRow.addView(mark, new LinearLayout.LayoutParams(0, dp(52), 3));
        markRow.addView(unmarkButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(markRow);

        mainButton = button("Start", 30);
        mainButton.setOnClickListener(v -> onMainButton());
        root.addView(mainButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(96)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button reset = button("Reset", 18);
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Reset everything?")
                .setMessage("This clears your chosen music, settings, saved song spots and sound pads. Your music files are not deleted.")
                .setPositiveButton("Reset", (d, w) -> resetToDefaults())
                .setNegativeButton("Cancel", null)
                .show());
        Button pick = button("Choose music", 18);
        pick.setOnClickListener(v -> pickMusic());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(64), 1);
        row.addView(reset, half);
        row.addView(pick, new LinearLayout.LayoutParams(0, dp(64), 1));
        root.addView(row);

        TextView padsTitle = text(16, true);
        padsTitle.setText("Sound pads");
        padsTitle.setPadding(0, dp(20), 0, dp(4));
        root.addView(padsTitle);
        for (int r = 0; r < 2; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 4; c++) {
                final int i = r * 4 + c;
                Button pad = button("", 22);
                pad.setTypeface(Typeface.DEFAULT_BOLD);
                pad.setTextColor(Color.WHITE);
                // Sounds play the moment the pad is touched; holding a full pad opens its menu
                pad.setOnTouchListener((v, e) -> {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) padTap(i);
                    return false;
                });
                pad.setOnLongClickListener(v -> {
                    padMenu(i);
                    return true;
                });
                padButtons[i] = pad;
                line.addView(pad, new LinearLayout.LayoutParams(0, dp(68), 1));
            }
            root.addView(line);
        }
        TextView padsHint = text(13, false);
        padsHint.setText("Tap 🎙 to record, tap again to stop. Hold a pad to record it again or delete it.");
        padsHint.setAlpha(0.8f);
        root.addView(padsHint);

        TextView settingsTitle = text(16, true);
        settingsTitle.setText("Settings");
        settingsTitle.setPadding(0, dp(20), 0, dp(4));
        root.addView(settingsTitle);

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        chairsButton = button("Musical chairs", 16);
        chairsButton.setOnClickListener(v -> setSwitchMode(false));
        switchButton = button("Switch songs", 16);
        switchButton.setOnClickListener(v -> setSwitchMode(true));
        modes.addView(chairsButton, new LinearLayout.LayoutParams(0, dp(56), 1));
        modes.addView(switchButton, new LinearLayout.LayoutParams(0, dp(56), 1));
        root.addView(modes);

        minRow = stepper("", () -> minPlay, v -> minPlay = v, 3, 300, 1);
        maxRow = stepper("", () -> maxPlay, v -> maxPlay = v, 3, 300, 1);
        restRow = stepper("Stop time before restart", () -> restTime, v -> restTime = v, 1, 120, 1);
        root.addView(minRow);
        root.addView(maxRow);
        root.addView(restRow);
        pauseRow = stepper("Stop between songs", () -> switchPause, v -> switchPause = v, 0, 60, 1);
        root.addView(pauseRow);

        Switch auto = new Switch(this);
        autoSwitch = auto;
        auto.setText("Restart music automatically");
        auto.setTextColor(Color.WHITE);
        auto.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        auto.setChecked(autoRestart);
        auto.setPadding(0, dp(8), 0, dp(8));
        auto.setOnCheckedChangeListener((b, checked) -> {
            autoRestart = checked;
            saveSettings();
            if (state == State.STOPPED) {
                handler.removeCallbacks(tickRunnable);
                countdown = restTime;
                if (autoRestart) handler.postDelayed(tickRunnable, 1000);
                updateUi();
            }
        });
        root.addView(auto);

        startRow = new LinearLayout(this);
        startRow.setOrientation(LinearLayout.VERTICAL);
        startRow.setPadding(0, dp(8), 0, dp(8));
        TextView startLabel = text(18, false);
        startLabel.setGravity(Gravity.START);
        startLabel.setText("Songs start at");
        startRow.addView(startLabel);
        LinearLayout starts = new LinearLayout(this);
        starts.setOrientation(LinearLayout.HORIZONTAL);
        startCatchy = button("Catchy part", 14);
        startCatchy.setOnClickListener(v -> setStartAt("catchy"));
        startRandom = button("Random spot", 14);
        startRandom.setOnClickListener(v -> setStartAt("random"));
        startResume = button("Where it left off", 14);
        startResume.setOnClickListener(v -> setStartAt("resume"));
        starts.addView(startCatchy, new LinearLayout.LayoutParams(0, dp(56), 1));
        starts.addView(startRandom, new LinearLayout.LayoutParams(0, dp(56), 1));
        starts.addView(startResume, new LinearLayout.LayoutParams(0, dp(56), 1));
        startRow.addView(starts);
        root.addView(startRow);

        showMode();
        setContentView(scroll);
    }

    private void setStartAt(String s) {
        startAt = s;
        saveSettings();
        showMode();
        if (startAt.equals("catchy")) findCatchyParts();
    }

    private void setSwitchMode(boolean on) {
        if (on == switchMode) return;
        switchMode = on;
        resetGame();
        saveSettings();
        showMode();
        updateUi();
        if (switchMode && startAt.equals("catchy")) findCatchyParts();
    }

    private void showMode() {
        chairsButton.setAlpha(switchMode ? 0.5f : 1f);
        switchButton.setAlpha(switchMode ? 1f : 0.5f);
        chairsButton.setTypeface(switchMode ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
        switchButton.setTypeface(switchMode ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        ((TextView) minRow.getChildAt(0)).setText(switchMode ? "Switch song after at least" : "Music plays at least");
        ((TextView) maxRow.getChildAt(0)).setText(switchMode ? "Switch song after at most" : "Music plays at most");
        restRow.setVisibility(switchMode ? View.GONE : View.VISIBLE);
        pauseRow.setVisibility(switchMode ? View.VISIBLE : View.GONE);
        autoSwitch.setVisibility(switchMode ? View.GONE : View.VISIBLE);
        startRow.setVisibility(switchMode ? View.VISIBLE : View.GONE);
        Button[] startButtons = {startCatchy, startRandom, startResume};
        String[] startValues = {"catchy", "random", "resume"};
        for (int i = 0; i < 3; i++) {
            boolean on = startAt.equals(startValues[i]);
            startButtons[i].setAlpha(on ? 1f : 0.5f);
            startButtons[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        showMark();
    }

    // ---------- Catchy parts ----------

    // Analyzes any chosen songs not analyzed yet, one at a time, in the background
    private void findCatchyParts() {
        if (analyzing) return;
        List<Uri> todo = new ArrayList<>();
        for (Uri u : songs) {
            JSONObject h = highlights.optJSONObject(u.toString());
            if (h == null || !h.has("starts")) todo.add(u);
        }
        if (todo.isEmpty()) return;
        analyzing = true;
        analyzeDone = 0;
        analyzeTotal = todo.size();
        updateUi();
        worker.execute(() -> {
            for (Uri u : todo) {
                double[] found;
                try {
                    found = Highlights.find(Highlights.decode(this, u), Highlights.RATE);
                } catch (Exception | OutOfMemoryError e) {
                    found = new double[0];
                }
                final double[] result = found;
                handler.post(() -> {
                    JSONArray starts = new JSONArray();
                    for (double s : result) {
                        try { starts.put(s); } catch (JSONException ignored) { }
                    }
                    // Skip a song that was cleared by a reset while it was being analyzed
                    if (songs.contains(u)) putHighlight(u, "starts", starts);
                    analyzeDone++;
                    // The first song was cued up before its analysis finished: cue it to the catchy part now
                    if (!songs.isEmpty() && u.equals(songs.get(songIndex)) && state == State.IDLE && player != null) {
                        player.seekTo(startPosition(songIndex));
                    }
                    updateUi();
                });
            }
            handler.post(() -> {
                analyzing = false;
                updateUi();
                findCatchyParts();  // songs chosen in the meantime
            });
        });
    }

    private void putHighlight(Uri song, String key, Object value) {
        try {
            JSONObject h = highlights.optJSONObject(song.toString());
            if (h == null) {
                h = new JSONObject();
                highlights.put(song.toString(), h);
            }
            if (value == null) h.remove(key); else h.put(key, value);
        } catch (JSONException ignored) { }
        prefs.edit().putString("highlights", highlights.toString()).apply();
    }

    private void showMark() {
        boolean show = switchMode && startAt.equals("catchy") && player != null;
        markRow.setVisibility(show ? View.VISIBLE : View.GONE);
        JSONObject h = songs.isEmpty() ? null : highlights.optJSONObject(songs.get(songIndex).toString());
        unmarkButton.setVisibility(h != null && h.has("mark") ? View.VISIBLE : View.GONE);
    }

    // Sets where the current song starts from now on (just before now, to allow for reaction time)
    private void markSpot() {
        if (player == null) return;
        double at = Math.max(0, Math.round((player.getCurrentPosition() - 300) / 100.0) / 10.0);
        putHighlight(songs.get(songIndex), "mark", at);
        Toast.makeText(this, songTitle(songs.get(songIndex)) + " will start at " + formatTime((int) (at * 1000)),
                Toast.LENGTH_SHORT).show();
        updateUi();
    }

    private void unmarkSpot() {
        putHighlight(songs.get(songIndex), "mark", null);
        Toast.makeText(this, songTitle(songs.get(songIndex)) + " will start at its catchy part",
                Toast.LENGTH_SHORT).show();
        updateUi();
    }

    private void updateUi() {
        setKeepAlive(state == State.PLAYING || state == State.STOPPED);
        int bg;
        bigText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
        switch (state) {
            case PLAYING:
                if (switchMode) {
                    bg = Color.parseColor(SONG_COLORS[songIndex % SONG_COLORS.length]);
                    bigText.setText(songTitle(songs.get(songIndex)));
                    bigText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48);
                } else {
                    bg = COLOR_PLAY;
                    bigText.setText("🎵 Walk! 🎵");
                }
                subText.setText("");
                mainButton.setText("Pause");
                break;
            case STOPPED:
                bg = COLOR_STOP;
                bigText.setText("STOP!\n🪑");
                subText.setText(autoNext()
                        ? (switchMode ? "Next song in " : "Music in ") + countdown + "…"
                        : "Tap “Play” when ready");
                mainButton.setText("Play now");
                break;
            case PAUSED:
                bg = COLOR_PAUSE;
                bigText.setText("Paused");
                subText.setText("");
                mainButton.setText("Resume");
                break;
            default:
                bg = COLOR_IDLE;
                bigText.setText("Musical\nChairs");
                subText.setText(songs.isEmpty() ? "Choose music to begin"
                        : switchMode && songs.size() < 2 ? "Choose 2 or more songs" : "");
                mainButton.setText("Start");
        }
        root.setBackgroundColor(bg);
        getWindow().getDecorView().setBackgroundColor(bg);
        songText.setText(songs.isEmpty() ? "No music chosen"
                : "♪ " + songName(songs.get(songIndex)) + (songs.size() > 1
                    ? "  (" + (songIndex + 1) + "/" + songs.size() + ")" : ""));
        if (analyzing && switchMode && startAt.equals("catchy")) {
            songText.append("  ·  finding catchy parts " + analyzeDone + "/" + analyzeTotal + "…");
        }
        showMark();
        updateProgress();
    }

    private void updateProgress() {
        seekBar.setEnabled(player != null);
        if (player == null) {
            seekBar.setMax(1);
            seekBar.setProgress(0);
            timeText.setText("0:00 / 0:00");
            return;
        }
        int duration = player.getDuration();
        if (!userSeeking) {
            seekBar.setMax(Math.max(duration, 1));
            seekBar.setProgress(player.getCurrentPosition());
        }
        timeText.setText(formatTime(seekBar.getProgress()) + " / " + formatTime(duration));
    }

    private String formatTime(int ms) {
        int s = Math.max(ms, 0) / 1000;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    // Runs a foreground service while a game is active so it survives the screen turning off.
    private void setKeepAlive(boolean on) {
        if (on == keepingAlive) return;
        keepingAlive = on;
        Intent i = new Intent(this, KeepAliveService.class);
        if (!on) {
            stopService(i);
        } else if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    // ---------- Game logic ----------

    private void onMainButton() {
        switch (state) {
            case IDLE: startPlaying(); break;
            case PLAYING: pauseGame(); break;
            case PAUSED: resumeGame(); break;
            case STOPPED: startPlaying(); break;
        }
    }

    private void startPlaying() {
        if (songs.isEmpty()) {
            Toast.makeText(this, "Choose music first", Toast.LENGTH_SHORT).show();
            pickMusic();
            return;
        }
        if (switchMode && songs.size() < 2) {
            Toast.makeText(this, "Choose 2 or more songs for “Switch songs”", Toast.LENGTH_SHORT).show();
            pickMusic();
            return;
        }
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(stopRunnable);
        cancelSwitch();
        // After the STOP screen in "Switch songs", carry on with a different song
        if (switchMode && state == State.STOPPED && !preparePlayer(pickNext())) return;
        if (player == null && !preparePlayer(songIndex)) return;
        player.setVolume(1f, 1f);
        player.start();
        state = State.PLAYING;
        scheduleStop();
        updateUi();
    }

    private void scheduleStop() {
        int lo = Math.min(minPlay, maxPlay);
        int hi = Math.max(minPlay, maxPlay);
        // random time in milliseconds between lo and hi seconds
        long ms = lo * 1000L + (long) (random.nextDouble() * (hi - lo) * 1000L);
        handler.postDelayed(stopRunnable, ms);
    }

    // The random moment: stop (musical chairs, or "Switch songs" with a stop), or fade to the next song.
    private void switchTime() {
        handler.removeCallbacks(stopRunnable);
        if (switchMode && switchPause == 0) {
            fadeStep = 0;
            handler.post(fadeRunnable);
        } else {
            doStop();
        }
    }

    // Whether the STOP countdown runs by itself ("Switch songs" always moves on)
    private boolean autoNext() {
        return switchMode || autoRestart;
    }

    // A random song other than the current one
    private int pickNext() {
        int next = random.nextInt(songs.size() - 1);
        return next >= songIndex ? next + 1 : next;
    }

    private void savePosition() {
        if (player == null) return;
        int pos = player.getCurrentPosition();
        // A song that has finished starts again from the top next time
        positions.put(songIndex, pos >= player.getDuration() - 500 ? 0 : pos);
    }

    // A random spot in the song (ms) that leaves enough music to play until the next switch
    private int randomSpot(int durationMs) {
        int room = durationMs - (Math.max(minPlay, maxPlay) + 2) * 1000;
        return room > 0 ? random.nextInt(room) : 0;
    }

    // Where song i starts in "Switch songs" mode (ms); the player has song i loaded
    private int startPosition(int i) {
        if (startAt.equals("resume")) {
            Integer saved = positions.get(i);
            return saved != null ? saved : 0;
        }
        JSONObject h = highlights.optJSONObject(songs.get(i).toString());
        if (startAt.equals("catchy") && h != null) {
            if (h.has("mark")) return (int) (h.optDouble("mark") * 1000);
            // One of the places the catchy part is played, picked at random
            JSONArray starts = h.optJSONArray("starts");
            if (starts != null && starts.length() > 0) {
                return (int) (starts.optDouble(random.nextInt(starts.length())) * 1000);
            }
        }
        return randomSpot(player.getDuration());
    }

    // Carries on with a different song from where that song left off (after the fade).
    private void switchSong() {
        if (state != State.PLAYING) return;
        if (player != null && player.isPlaying()) player.pause();
        savePosition();
        if (!preparePlayer(pickNext())) return;
        player.start();
        scheduleStop();
        updateUi();
    }

    // Stops a song fade that is part way through (on pause or reset).
    private void cancelSwitch() {
        handler.removeCallbacks(fadeRunnable);
    }

    private void doStop() {
        if (player != null && player.isPlaying()) player.pause();
        if (switchMode) savePosition();
        beep();
        state = State.STOPPED;
        countdown = switchMode ? switchPause : restTime;
        updateUi();
        if (autoNext()) handler.postDelayed(tickRunnable, 1000);
    }

    private void pauseGame() {
        if (state == State.PLAYING) {
            handler.removeCallbacks(stopRunnable);
            cancelSwitch();
            if (player != null && player.isPlaying()) player.pause();
        } else if (state == State.STOPPED) {
            handler.removeCallbacks(tickRunnable);
        }
        pausedFrom = state;
        state = State.PAUSED;
        updateUi();
    }

    private void resumeGame() {
        if (pausedFrom == State.STOPPED) {
            state = State.STOPPED;
            updateUi();
            if (autoNext()) handler.postDelayed(tickRunnable, 1000);
        } else {
            startPlaying();
        }
    }

    private void resetGame() {
        handler.removeCallbacks(stopRunnable);
        handler.removeCallbacks(tickRunnable);
        cancelSwitch();
        if (player != null) {
            if (player.isPlaying()) player.pause();
            player.seekTo(switchMode && !startAt.equals("resume") ? startPosition(songIndex) : 0);
        }
        state = State.IDLE;
        updateUi();
    }

    // The Reset button: back to how the app is on first install - no music, default settings, no saved spots.
    private void resetToDefaults() {
        resetGame();
        releasePlayer();
        songs.clear();
        songIndex = 0;
        positions.clear();
        prefs.edit().clear().apply();
        loadSettings();
        for (Runnable refresh : stepperRefreshers) refresh.run();
        autoSwitch.setChecked(autoRestart);
        cancelRecording();
        for (int i = 0; i < PADS; i++) deletePad(i);
        showMode();
        updateUi();
        Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show();
    }

    // ---------- Sound pads ----------

    private File padFile(int i) {
        return new File(new File(getFilesDir(), "pads"), "pad" + (i + 1) + ".wav");
    }

    private void setUpPads() {
        soundPool = new SoundPool.Builder()
                .setMaxStreams(PADS)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        for (int i = 0; i < PADS; i++) {
            File f = padFile(i);
            if (f.exists()) padSounds[i] = soundPool.load(f.getPath(), 1);
        }
        showPads();
    }

    private void showPads() {
        for (int i = 0; i < PADS; i++) {
            Recording rec = recording != null && recording.pad == i ? recording : null;
            int color;
            String label;
            if (rec != null) {
                color = Color.parseColor("#D50000");
                label = rec.stop ? "…" : "● " + (SystemClock.uptimeMillis() - rec.started) / 1000 + "s";
            } else if (padSounds[i] != 0) {
                color = Color.parseColor(PAD_COLORS[i]);
                label = String.valueOf(i + 1);
            } else {
                color = Color.argb(70, 255, 255, 255);
                label = "🎙";
            }
            padButtons[i].setBackgroundTintList(ColorStateList.valueOf(color));
            padButtons[i].setText(label);
        }
    }

    // A tap: play a full pad, record into an empty one, or stop the recording
    private void padTap(int i) {
        if (recording != null) {
            if (recording.pad == i) stopRecording();
            return;
        }
        if (padSounds[i] != 0) {
            soundPool.play(padSounds[i], 1f, 1f, 1, 0, 1f);
        } else {
            startRecording(i);
        }
    }

    private void padMenu(int i) {
        if (recording != null || padSounds[i] == 0) return;
        new AlertDialog.Builder(this)
                .setTitle("Sound pad " + (i + 1))
                .setItems(new String[]{"Record again", "Delete"}, (d, which) -> {
                    if (recording != null) return;
                    if (which == 0) startRecording(i); else deletePad(i);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deletePad(int i) {
        if (padSounds[i] != 0) soundPool.unload(padSounds[i]);
        padSounds[i] = 0;
        padFile(i).delete();
        showPads();
    }

    private void startRecording(int i) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingRecordPad = i;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, RECORD_PERMISSION);
            return;
        }
        final AudioRecord mic;
        try {
            int min = AudioRecord.getMinBufferSize(Samples.RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            mic = new AudioRecord(MediaRecorder.AudioSource.MIC, Samples.RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, Samples.RATE));
            if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
                mic.release();
                throw new IllegalStateException("microphone not ready");
            }
            mic.startRecording();
        } catch (RuntimeException e) {
            Toast.makeText(this, "Couldn't use the microphone.", Toast.LENGTH_LONG).show();
            return;
        }
        final Recording rec = new Recording(i);
        final File file = padFile(i);
        recording = rec;
        handler.post(recordTick);
        new Thread(() -> {
            short[] pcm = new short[Samples.RATE * Samples.MAX_SECONDS];
            int n = 0;
            while (!rec.stop && !rec.cancel && n < pcm.length) {
                int got = mic.read(pcm, n, Math.min(2048, pcm.length - n));
                if (got <= 0) break;
                n += got;
            }
            try { mic.stop(); } catch (IllegalStateException ignored) { }
            mic.release();
            short[] sound = rec.cancel ? null : Samples.tidy(pcm, n);
            boolean saved = false;
            if (sound != null && !rec.cancel) {
                try {
                    Samples.writeWav(file, sound);
                    saved = true;
                } catch (IOException ignored) { }
            }
            final boolean heard = sound != null;
            final boolean ok = saved;
            handler.post(() -> finishRecording(rec, heard, ok));
        }).start();
    }

    private void stopRecording() {
        if (recording == null) return;
        recording.stop = true;
        showPads();
    }

    private void finishRecording(Recording rec, boolean heard, boolean saved) {
        if (recording == rec) recording = null;
        if (rec.cancel) return;
        if (!heard) {
            Toast.makeText(this, "Didn't hear anything. Try again a bit closer to the microphone.",
                    Toast.LENGTH_LONG).show();
        } else if (!saved) {
            Toast.makeText(this, "Couldn't save that sound. Please try again.", Toast.LENGTH_LONG).show();
        } else {
            if (padSounds[rec.pad] != 0) soundPool.unload(padSounds[rec.pad]);
            padSounds[rec.pad] = soundPool.load(padFile(rec.pad).getPath(), 1);
        }
        showPads();
    }

    // Throws away a recording in progress (on reset or when the app closes)
    private void cancelRecording() {
        if (recording != null) recording.cancel = true;
        recording = null;
        handler.removeCallbacks(recordTick);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != RECORD_PERMISSION) return;
        int pad = pendingRecordPad;
        pendingRecordPad = -1;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (pad >= 0 && recording == null) startRecording(pad);
        } else {
            Toast.makeText(this, "Sound pads need the microphone to record.", Toast.LENGTH_LONG).show();
        }
    }

    // Soft two-note chime ("ding-dong")
    private void beep() {
        try {
            int rate = 44100;
            short[] pcm = new short[(int) (rate * 1.3)];
            addChimeNote(pcm, rate, 784, 0);
            addChimeNote(pcm, rate, 523, 0.28);
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(pcm.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(pcm, 0, pcm.length);
            track.play();
            handler.postDelayed(track::release, 1600);
        } catch (RuntimeException ignored) { }
    }

    // Adds one bell-like note: fades in quickly, then dies away
    private static void addChimeNote(short[] pcm, int rate, double hz, double startSec) {
        int start = (int) (startSec * rate);
        int len = Math.min(pcm.length - start, rate);
        for (int i = 0; i < len; i++) {
            double t = (double) i / rate;
            double env = Math.min(1, t / 0.02) * Math.exp(-t * 5);
            double s = pcm[start + i] + Math.sin(2 * Math.PI * hz * t) * env * 0.3 * Short.MAX_VALUE;
            pcm[start + i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s));
        }
    }

    // ---------- Music ----------

    private boolean preparePlayer(int index) {
        releasePlayer();
        for (int tries = 0; tries < songs.size(); tries++) {
            int i = (index + tries) % songs.size();
            MediaPlayer mp = null;
            try { mp = MediaPlayer.create(this, songs.get(i)); } catch (Exception ignored) { }
            if (mp != null) {
                songIndex = i;
                player = mp;
                if (switchMode) player.seekTo(startPosition(i));
                if (songs.size() == 1) {
                    player.setLooping(true);
                } else {
                    player.setOnCompletionListener(done -> {
                        if (switchMode) {
                            if (state == State.PLAYING) {
                                cancelSwitch();
                                switchTime();
                            }
                            return;
                        }
                        boolean wasPlaying = state == State.PLAYING;
                        if (preparePlayer((songIndex + 1) % songs.size()) && wasPlaying) {
                            player.start();
                        }
                        updateUi();
                    });
                }
                return true;
            }
        }
        Toast.makeText(this, "Couldn't open the music. Please choose it again.", Toast.LENGTH_LONG).show();
        return false;
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void pickMusic() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK_MUSIC);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_MUSIC || resultCode != RESULT_OK || data == null) return;

        List<Uri> picked = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                picked.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            picked.add(data.getData());
        }
        if (picked.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        for (Uri u : picked) {
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }
            if (sb.length() > 0) sb.append("\n");
            sb.append(u.toString());
        }
        prefs.edit().putString("songs", sb.toString()).apply();

        resetGame();
        releasePlayer();
        songs.clear();
        songs.addAll(picked);
        songIndex = 0;
        positions.clear();
        preparePlayer(0);
        updateUi();
        if (switchMode && startAt.equals("catchy")) findCatchyParts();
    }

    private String songName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last != null ? last : "Song";
    }

    // File name without the extension, e.g. "Happy.mp3" -> "Happy"
    private String songTitle(Uri uri) {
        return songName(uri).replaceAll("\\.[^.]+$", "");
    }

    private void saveSettings() {
        prefs.edit()
                .putInt("minPlay", minPlay)
                .putInt("maxPlay", maxPlay)
                .putInt("restTime", restTime)
                .putBoolean("autoRestart", autoRestart)
                .putBoolean("switchMode", switchMode)
                .putInt("switchPause", switchPause)
                .putString("startAt", startAt)
                .apply();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        setKeepAlive(false);
        releasePlayer();
        cancelRecording();
        if (soundPool != null) soundPool.release();
        super.onDestroy();
    }
}
