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
                // "Jingle - jingle - jingle": a strap of six bells shaken three times;
                // each shake strikes every bell once or twice
                for (double at : new double[]{0, 0.35, 0.7}) {
                    for (double hz : new double[]{1250, 1480, 1700, 1950, 2250, 2600}) {
                        int hits = 1 + random.nextInt(2);
                        for (int h = 0; h < hits; h++) {
                            jingleBell(mix, hz * (0.99 + random.nextDouble() * 0.02), at + random.nextDouble() * 0.08,
                                    0.06 * (0.5 + random.nextDouble() * 0.5), 0.4);
                        }
                    }
                    rattle(mix, at, 0.13, 0.043);
                }
                break;
            case "bell":
                // A church bell's mix of tones; the low ones ring longest
                double[][] partials = {{0.5, 0.11, 3}, {1, 0.14, 2.2}, {1.19, 0.07, 1.6}, {1.5, 0.05, 1.3},
                        {2, 0.07, 1.1}, {2.74, 0.03, 0.8}, {3.76, 0.02, 0.6}};
                for (double[] p : partials) note(mix, 196 * p[0], 0, p[1], p[2], 3.5);
                break;
            case "jingle": {
                // "Jingle bells, jingle bells, jingle all the way" on bells
                double e = 1318.5, g = 1568, c = 1046.5, d = 1174.7;
                double[][] tune = {{e, 1}, {e, 1}, {e, 2}, {e, 1}, {e, 1}, {e, 2}, {e, 1}, {g, 1}, {c, 1.5}, {d, 0.5}, {e, 4}};
                double beat = 0.2;
                double at = 0;
                for (double[] n : tune) {
                    note(mix, n[0], at, 0.22, 0.5, 1);
                    note(mix, n[0] * 2, at, 0.04, 0.2, 1);
                    at += n[1] * beat;
                }
                break;
            }
            case "sparkle":
                // Quick rising twinkles
                for (int k = 0; k < 14; k++) {
                    note(mix, 1500 + k * 180 + random.nextDouble() * 300, k * 0.05, 0.1, 0.4, 0.6);
                }
                break;
            default:
                // Chime: a soft two-note "ding-dong"
                note(mix, 784, 0, 0.3, 0.6, 1);
                note(mix, 523, 0.28, 0.3, 0.6, 1);
        }
        return toPcm(mix);
    }

    // One bell-like note: fades in over 10 ms, then dies away over about `decay` seconds
    private static void note(float[] mix, double hz, double at, double vol, double decay, double len) {
        tone(mix, hz, at, vol, 0.01, decay / 3, len);
    }

    // A sine tone that fades in over `attack` seconds, then dies away with time constant `tc`
    private static void tone(float[] mix, double hz, double at, double vol, double attack, double tc, double len) {
        int start = (int) (at * RATE);
        int n = Math.min((int) (len * RATE), mix.length - start);
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double env = t < attack ? vol * t / attack : vol * Math.exp(-(t - attack) / tc);
            mix[start + i] += (float) (Math.sin(2 * Math.PI * (hz * t % 1)) * env);
        }
    }

    // One jingle bell struck: a few out-of-tune metal tones; the high ones die away first
    private static void jingleBell(float[] mix, double hz, double at, double vol, double decay) {
        double[][] partials = {{1, 1}, {2.32, 0.5}, {4.25, 0.25}, {6.8, 0.12}};
        for (double[] p : partials) tone(mix, hz * p[0], at, vol * p[1], 0.002, decay / (1 + p[0] * 0.6), decay * 4);
    }

    // The rattle of the pellets inside the bells: a short band of hiss
    private static void rattle(float[] mix, double at, double len, double vol) {
        int start = (int) (at * RATE);
        int n = Math.min((int) (len * RATE), mix.length - start);
        // Band-pass filter around 8 kHz (a standard two-pole "biquad", like the Windows app's)
        double w = 2 * Math.PI * 8000 / RATE;
        double alpha = Math.sin(w) / (2 * 1.5);
        double cos = Math.cos(w);
        double a0 = 1 + alpha;
        double b0 = alpha / a0, b1 = 0, b2 = -alpha / a0;
        double a1 = -2 * cos / a0, a2 = (1 - alpha) / a0;
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < n; i++) {
            double x = (random.nextDouble() * 2 - 1) * Math.pow(1 - (double) i / n, 2);
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
