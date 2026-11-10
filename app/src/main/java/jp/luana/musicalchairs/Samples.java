package jp.luana.musicalchairs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Sound pads: tidying up a recorded sample and saving it as a WAV file. */
final class Samples {
    static final int RATE = 44100;
    static final int MAX_SECONDS = 10;

    private Samples() { }

    /**
     * Cuts the silence before and after the sound and makes it loud and clear.
     * Returns null if nothing was heard.
     */
    static short[] tidy(short[] pcm, int n) {
        int peak = 0;
        for (int i = 0; i < n; i++) peak = Math.max(peak, Math.abs(pcm[i]));
        if (peak < 330) return null;  // about 1% of full volume
        int threshold = Math.max(330, peak / 10);
        int start = 0;
        int end = n;
        while (start < end && Math.abs(pcm[start]) < threshold) start++;
        while (end > start && Math.abs(pcm[end - 1]) < threshold) end--;
        if (end - start < RATE / 20) return null;
        // Keep a moment before (so the start isn't clipped) and after (so it dies away naturally)
        start = Math.max(0, start - RATE / 100);
        end = Math.min(n, end + RATE / 10);
        short[] out = new short[end - start];
        double gain = Math.min(0.9 * 32767 / peak, 8);
        // A very short fade at each end so the sample doesn't click
        int fade = Math.min(RATE / 200, out.length / 2);
        for (int i = 0; i < out.length; i++) {
            double g = gain;
            if (i < fade) g *= (double) i / fade;
            if (i >= out.length - fade) g *= (double) (out.length - 1 - i) / fade;
            long s = Math.round(pcm[start + i] * g);
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s));
        }
        return out;
    }

    /** Saves 16-bit mono sound as a WAV file. */
    static void writeWav(File file, short[] pcm) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length * 2);
        b.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        b.putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2);
        b.putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length * 2);
        for (short s : pcm) b.putShort(s);
        File dir = file.getParentFile();
        if (dir != null) dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(b.array());
        }
    }
}
