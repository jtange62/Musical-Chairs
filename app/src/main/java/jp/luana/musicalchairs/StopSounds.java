package jp.luana.musicalchairs;

import java.util.Random;

// The built-in stop sounds, made as sound samples (the same sounds as the Windows app's)
final class StopSounds {

    static final int RATE = 44100;
    static final String[] KEYS = {"chime", "sleigh", "bell", "jingle", "sparkle"};
    static final String[] NAMES = {"Chime", "🛷 Sleigh bells", "🔔 Big bell", "🎶 Jingle Bells tune", "✨ Magic sparkle"};

    private static final Random random = new Random();

    private StopSounds() { }

    static boolean has(String key) {
        for (String k : KEYS) if (k.equals(key)) return true;
        return false;
    }

    static String name(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return NAMES[i];
        return key;
    }

    // The sound as 16-bit samples
    static short[] make(String key) {
        float[] mix = new float[(int) (RATE * 3.6)];
        switch (key) {
            case "sleigh":
                // A few shakes, each a cluster of tiny bells
                for (double at : new double[]{0, 0.3, 0.6, 0.75, 0.9}) {
                    rattle(mix, at, 0.25);
                    for (int k = 0; k < 6; k++) {
                        note(mix, 3000 + random.nextDouble() * 4000, at + random.nextDouble() * 0.05,
                                0.06, 0.25, true, 0.4);
                    }
                }
                break;
            case "bell":
                // A church bell's mix of tones; the low ones ring longest
                double[][] partials = {{0.5, 0.11, 3}, {1, 0.14, 2.2}, {1.19, 0.07, 1.6}, {1.5, 0.05, 1.3},
                        {2, 0.07, 1.1}, {2.74, 0.03, 0.8}, {3.76, 0.02, 0.6}};
                for (double[] p : partials) note(mix, 196 * p[0], 0, p[1], p[2], false, 3.5);
                break;
            case "jingle": {
                // "Jingle bells, jingle bells, jingle all the way" on bells
                double e = 1318.5, g = 1568, c = 1046.5, d = 1174.7;
                double[][] tune = {{e, 1}, {e, 1}, {e, 2}, {e, 1}, {e, 1}, {e, 2}, {e, 1}, {g, 1}, {c, 1.5}, {d, 0.5}, {e, 4}};
                double beat = 0.2;
                double at = 0;
                for (double[] n : tune) {
                    note(mix, n[0], at, 0.22, 0.5, false, 1);
                    note(mix, n[0] * 2, at, 0.04, 0.2, false, 1);
                    at += n[1] * beat;
                }
                break;
            }
            case "sparkle":
                // Quick rising twinkles
                for (int k = 0; k < 14; k++) {
                    note(mix, 1500 + k * 180 + random.nextDouble() * 300, k * 0.05, 0.1, 0.4, false, 0.6);
                }
                break;
            default:
                // Chime: a soft two-note "ding-dong"
                note(mix, 784, 0, 0.3, 0.6, false, 1);
                note(mix, 523, 0.28, 0.3, 0.6, false, 1);
        }
        return toPcm(mix);
    }

    // One bell-like note: fades in over 10 ms, then dies away over about `decay` seconds
    private static void note(float[] mix, double hz, double at, double vol, double decay, boolean triangle, double len) {
        int start = (int) (at * RATE);
        int n = Math.min((int) (len * RATE), mix.length - start);
        double tc = decay / 3;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double env = t < 0.01 ? vol * t / 0.01 : vol * Math.exp(-(t - 0.01) / tc);
            double phase = hz * t % 1;
            double wave = triangle ? 1 - 4 * Math.abs(phase - 0.5) : Math.sin(2 * Math.PI * phase);
            mix[start + i] += (float) (wave * env);
        }
    }

    // A short burst of high hiss, like bells rattling
    private static void rattle(float[] mix, double at, double vol) {
        int start = (int) (at * RATE);
        int n = Math.min((int) (0.12 * RATE), mix.length - start);
        // High-pass filter at 6 kHz (a standard two-pole "biquad")
        double w = 2 * Math.PI * 6000 / RATE;
        double alpha = Math.sin(w) / (2 * Math.sqrt(0.5));
        double cos = Math.cos(w);
        double a0 = 1 + alpha;
        double b0 = (1 + cos) / 2 / a0, b1 = -(1 + cos) / a0, b2 = b0;
        double a1 = -2 * cos / a0, a2 = (1 - alpha) / a0;
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < n; i++) {
            double x = (random.nextDouble() * 2 - 1) * Math.pow(1 - (double) i / n, 3);
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            mix[start + i] += (float) (y * vol);
        }
    }

    // Converts to 16-bit samples, leaving off the silence at the end
    private static short[] toPcm(float[] mix) {
        int end = mix.length;
        while (end > 0 && Math.abs(mix[end - 1]) < 0.0005) end--;
        short[] pcm = new short[end];
        for (int i = 0; i < end; i++) {
            pcm[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mix[i] * Short.MAX_VALUE));
        }
        return pcm;
    }
}
