package jp.luana.musicalchairs;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
    private Switch autoSwitch;

    private final Runnable stopRunnable = this::doStop;
    private final Runnable switchRunnable = this::fadeAndSwitch;
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
        minPlay = prefs.getInt("minPlay", 10);
        maxPlay = prefs.getInt("maxPlay", 30);
        restTime = prefs.getInt("restTime", 10);
        autoRestart = prefs.getBoolean("autoRestart", true);
        switchMode = prefs.getBoolean("switchMode", false);
        String saved = prefs.getString("songs", "");
        if (!saved.isEmpty()) {
            for (String s : saved.split("\n")) songs.add(Uri.parse(s));
        }

        // Lets the "game in progress" notification show on Android 13+
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        buildUi();
        if (!songs.isEmpty()) preparePlayer(0);
        updateUi();
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

        mainButton = button("Start", 30);
        mainButton.setOnClickListener(v -> onMainButton());
        root.addView(mainButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(96)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button reset = button("Reset", 18);
        reset.setOnClickListener(v -> resetGame());
        Button pick = button("Choose music", 18);
        pick.setOnClickListener(v -> pickMusic());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(64), 1);
        row.addView(reset, half);
        row.addView(pick, new LinearLayout.LayoutParams(0, dp(64), 1));
        root.addView(row);

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

        showMode();
        setContentView(scroll);
    }

    private void setSwitchMode(boolean on) {
        if (on == switchMode) return;
        resetGame();
        switchMode = on;
        saveSettings();
        showMode();
        updateUi();
    }

    private void showMode() {
        chairsButton.setAlpha(switchMode ? 0.5f : 1f);
        switchButton.setAlpha(switchMode ? 1f : 0.5f);
        chairsButton.setTypeface(switchMode ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
        switchButton.setTypeface(switchMode ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        ((TextView) minRow.getChildAt(0)).setText(switchMode ? "Switch song after at least" : "Music plays at least");
        ((TextView) maxRow.getChildAt(0)).setText(switchMode ? "Switch song after at most" : "Music plays at most");
        restRow.setVisibility(switchMode ? View.GONE : View.VISIBLE);
        autoSwitch.setVisibility(switchMode ? View.GONE : View.VISIBLE);
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
                subText.setText(autoRestart ? "Music in " + countdown + "…" : "Tap “Play” when ready");
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
        handler.removeCallbacks(switchRunnable);
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
        handler.postDelayed(switchMode ? switchRunnable : stopRunnable, ms);
    }

    // "Switch songs" mode: fade out over 0.4 s, then switchSong()
    private void fadeAndSwitch() {
        fadeStep = 0;
        handler.post(fadeRunnable);
    }

    // Carries on with a different song from where that song left off.
    private void switchSong() {
        if (state != State.PLAYING) return;
        if (player != null) {
            if (player.isPlaying()) player.pause();
            int pos = player.getCurrentPosition();
            // A song that has finished starts again from the top next time
            positions.put(songIndex, pos >= player.getDuration() - 500 ? 0 : pos);
        }
        int next = random.nextInt(songs.size() - 1);
        if (next >= songIndex) next++;
        if (!preparePlayer(next)) return;
        player.start();
        scheduleStop();
        updateUi();
    }

    // Stops a song switch that is part way through (on pause or reset).
    private void cancelSwitch() {
        handler.removeCallbacks(switchRunnable);
        handler.removeCallbacks(fadeRunnable);
    }

    private void doStop() {
        if (player != null && player.isPlaying()) player.pause();
        beep();
        state = State.STOPPED;
        countdown = restTime;
        updateUi();
        if (autoRestart) handler.postDelayed(tickRunnable, 1000);
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
            if (autoRestart) handler.postDelayed(tickRunnable, 1000);
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
            player.seekTo(0);
        }
        state = State.IDLE;
        updateUi();
    }

    private void beep() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500);
            handler.postDelayed(tg::release, 800);
        } catch (RuntimeException ignored) { }
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
                if (switchMode && positions.containsKey(i)) player.seekTo(positions.get(i));
                if (songs.size() == 1) {
                    player.setLooping(true);
                } else {
                    player.setOnCompletionListener(done -> {
                        if (switchMode) {
                            if (state == State.PLAYING) {
                                cancelSwitch();
                                switchSong();
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
                .apply();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        setKeepAlive(false);
        releasePlayer();
        super.onDestroy();
    }
}
