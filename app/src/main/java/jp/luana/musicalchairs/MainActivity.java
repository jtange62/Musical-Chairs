package jp.luana.musicalchairs;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.Uri;
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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MainActivity extends Activity {

    private enum State { IDLE, PLAYING, STOPPED, PAUSED }

    private static final int PICK_MUSIC = 42;
    private static final int COLOR_IDLE = Color.parseColor("#37474F");
    private static final int COLOR_PLAY = Color.parseColor("#2E7D32");
    private static final int COLOR_STOP = Color.parseColor("#C62828");
    private static final int COLOR_PAUSE = Color.parseColor("#F9A825");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final List<Uri> songs = new ArrayList<>();

    private MediaPlayer player;
    private int songIndex = 0;
    private State state = State.IDLE;
    private State pausedFrom = State.IDLE;
    private int countdown = 0;

    // Settings (seconds)
    private int minPlay = 10;
    private int maxPlay = 30;
    private int restTime = 10;
    private boolean autoRestart = true;

    private SharedPreferences prefs;

    private LinearLayout root;
    private TextView bigText;
    private TextView subText;
    private TextView songText;
    private Button mainButton;

    private final Runnable stopRunnable = this::doStop;
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
        String saved = prefs.getString("songs", "");
        if (!saved.isEmpty()) {
            for (String s : saved.split("\n")) songs.add(Uri.parse(s));
        }

        buildUi();
        updateUi();
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
        songText.setPadding(0, dp(8), 0, dp(16));
        root.addView(songText);

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

        root.addView(stepper("Music plays at least", () -> minPlay, v -> minPlay = v, 3, 300, 1));
        root.addView(stepper("Music plays at most", () -> maxPlay, v -> maxPlay = v, 3, 300, 1));
        root.addView(stepper("Stop time before restart", () -> restTime, v -> restTime = v, 1, 120, 1));

        Switch auto = new Switch(this);
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

        setContentView(scroll);
    }

    private void updateUi() {
        int bg;
        switch (state) {
            case PLAYING:
                bg = COLOR_PLAY;
                bigText.setText("🎵 Walk! 🎵");
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
                subText.setText(songs.isEmpty() ? "Choose music to begin" : "");
                mainButton.setText("Start");
        }
        root.setBackgroundColor(bg);
        getWindow().getDecorView().setBackgroundColor(bg);
        songText.setText(songs.isEmpty() ? "No music chosen"
                : "♪ " + songName(songs.get(songIndex)) + (songs.size() > 1
                    ? "  (" + (songIndex + 1) + "/" + songs.size() + ")" : ""));
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
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(stopRunnable);
        if (player == null && !preparePlayer(songIndex)) return;
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
                if (songs.size() == 1) {
                    player.setLooping(true);
                } else {
                    player.setOnCompletionListener(done -> {
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

    private void saveSettings() {
        prefs.edit()
                .putInt("minPlay", minPlay)
                .putInt("maxPlay", maxPlay)
                .putInt("restTime", restTime)
                .putBoolean("autoRestart", autoRestart)
                .apply();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        super.onDestroy();
    }
}
