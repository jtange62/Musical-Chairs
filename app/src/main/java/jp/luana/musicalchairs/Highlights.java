package jp.luana.musicalchairs;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Finds the catchy part of a song: its main theme or chorus.
// The idea: the memorable tune is the part whose notes come back the most often,
// while intros, bridges and endings happen only once. Quiet parts count for less.
// Same method as desktop/highlights.js.
final class Highlights {

    static final int RATE = 11025;      // songs are decoded at this rate for analysis
    private static final int FRAME = 2048;  // ~0.19 s
    private static final int HOP = 1024;    // a frame every ~0.09 s
    private static final int GROUP = 5;     // frames per step, ~0.46 s

    private Highlights() { }

    // ---------- Decoding ----------

    // Decodes a song to mono samples at RATE
    static float[] decode(Context context, Uri uri) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, uri, null);
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    format = f;
                    break;
                }
            }
            if (format == null) throw new IOException("No audio in " + uri);

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            Resampler out = new Resampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            while (true) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int size = extractor.readSampleData(buf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    out.setInputRate(f.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                } else if (index >= 0) {
                    ByteBuffer buf = codec.getOutputBuffer(index);
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    buf.order(ByteOrder.nativeOrder());
                    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        FloatBuffer fb = buf.asFloatBuffer();
                        while (fb.remaining() >= channels) {
                            float sum = 0;
                            for (int c = 0; c < channels; c++) sum += fb.get();
                            out.add(sum / channels);
                        }
                    } else {
                        ShortBuffer sb = buf.asShortBuffer();
                        while (sb.remaining() >= channels) {
                            float sum = 0;
                            for (int c = 0; c < channels; c++) sum += sb.get() / 32768f;
                            out.add(sum / channels);
                        }
                    }
                    codec.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            return out.toArray();
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (IllegalStateException ignored) { }
                codec.release();
            }
            extractor.release();
        }
    }

    // Brings samples down to RATE by averaging each group of input samples
    private static final class Resampler {
        private float[] data = new float[RATE * 60];
        private int size = 0;
        private double step;
        private double pos = 0;
        private float sum = 0;
        private int count = 0;

        Resampler(int inputRate) { setInputRate(inputRate); }

        void setInputRate(int inputRate) { step = (double) inputRate / RATE; }

        void add(float s) {
            sum += s;
            count++;
            pos += 1;
            if (pos >= step) {
                if (size == data.length) data = Arrays.copyOf(data, data.length * 2);
                data[size++] = sum / count;
                sum = 0;
                count = 0;
                pos -= step;
            }
        }

        float[] toArray() { return Arrays.copyOf(data, size); }
    }

    // ---------- Analysis ----------

    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int k = 0; k < half; k++) {
                    int a = i + k, b = a + half;
                    float tr = re[b] * cr - im[b] * ci;
                    float ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr; im[a] += ti;
                    float ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }

    // Returns the start times (seconds) of the catchy part: every place in the song
    // where the most repeated, loud-enough tune begins, earliest first.
    static double[] find(float[] samples, int rate) {
        // Per frame: loudness, and how strong each of the 12 notes is (chroma)
        float[] window = new float[FRAME];
        for (int i = 0; i < FRAME; i++) window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / FRAME));
        // Which note each FFT bin belongs to (only ~65 Hz to ~2 kHz, where melodies live)
        int[] binNote = new int[FRAME / 2];
        for (int k = 0; k < FRAME / 2; k++) {
            double f = (double) k * rate / FRAME;
            binNote[k] = k > 0 && f >= 65 && f <= 2100
                    ? ((int) Math.round(12 * Math.log(f / 440) / Math.log(2)) + 69) % 12 : -1;
        }

        int frames = Math.max(0, (samples.length - FRAME) / HOP + 1);
        float[] frameRms = new float[frames];
        float[] frameChroma = new float[frames * 12];
        float[] re = new float[FRAME], im = new float[FRAME];
        for (int f = 0; f < frames; f++) {
            int off = f * HOP;
            double energy = 0;
            for (int i = 0; i < FRAME; i++) {
                float s = samples[off + i];
                energy += s * s;
                re[i] = s * window[i];
                im[i] = 0;
            }
            frameRms[f] = (float) Math.sqrt(energy / FRAME);
            fft(re, im);
            for (int k = 1; k < FRAME / 2; k++) {
                if (binNote[k] >= 0) frameChroma[f * 12 + binNote[k]] += (float) Math.hypot(re[k], im[k]);
            }
        }

        // Group frames into steps; log-compress the notes, then scale to length 1
        int n = frames / GROUP;
        double stepSec = (double) HOP * GROUP / rate;
        double frameSec = (double) HOP / rate;
        if (n * stepSec < 20) return new double[0];
        float[] chroma = new float[n * 12];
        float[] rms = new float[n];
        for (int t = 0; t < n; t++) {
            for (int g = 0; g < GROUP; g++) {
                int f = t * GROUP + g;
                rms[t] += frameRms[f] / GROUP;
                for (int c = 0; c < 12; c++) chroma[t * 12 + c] += frameChroma[f * 12 + c];
            }
            double norm = 0;
            for (int c = 0; c < 12; c++) {
                float v = (float) Math.log1p(chroma[t * 12 + c]);
                chroma[t * 12 + c] = v;
                norm += v * v;
            }
            norm = Math.sqrt(norm);
            for (int c = 0; c < 12; c++) chroma[t * 12 + c] = norm > 1e-6 ? (float) (chroma[t * 12 + c] / norm) : 0;
        }

        // Compare every 3 s stretch with the stretch `lag` steps later
        int minLag = Math.max(1, (int) Math.round(4 / stepSec));  // repeats at least 4 s apart
        int win = Math.max(1, (int) Math.round(3 / stepSec));
        int minRun = (int) Math.round(5 / stepSec);               // a repeat lasts 8 s or more in all
        List<float[]> diags = new ArrayList<>();
        List<Integer> lags = new ArrayList<>();
        int total = 0;
        for (int lag = minLag; lag < n - win; lag++) {
            int len = n - lag;
            int m = len - win + 1;
            if (m <= 0) continue;
            float[] d = new float[len];
            for (int i = 0; i < len; i++) {
                float dot = 0;
                for (int c = 0; c < 12; c++) dot += chroma[i * 12 + c] * chroma[(i + lag) * 12 + c];
                d[i] = dot;
            }
            float[] sm = new float[m];
            float sum = 0;
            for (int i = 0; i < len; i++) {
                sum += d[i];
                if (i >= win) sum -= d[i - win];
                if (i >= win - 1) sm[i - win + 1] = sum / win;
            }
            diags.add(sm);
            lags.add(lag);
            total += m;
        }
        if (total == 0) return new double[0];

        // "Similar enough to be a repeat" = in the top 10% of all comparisons in this song
        float[] all = new float[total];
        int at = 0;
        for (float[] sm : diags) {
            System.arraycopy(sm, 0, all, at, sm.length);
            at += sm.length;
        }
        Arrays.sort(all);
        float threshold = all[(int) (all.length * 0.9)];

        // A run is a stretch [a, b) that sounds the same again `lag` steps later
        float[] rep = new float[n];
        List<int[]> runs = new ArrayList<>();
        for (int k = 0; k < diags.size(); k++) {
            float[] sm = diags.get(k);
            int lag = lags.get(k);
            int start = -1;
            for (int i = 0; i <= sm.length; i++) {
                boolean above = i < sm.length && sm[i] > threshold;
                if (above && start < 0) start = i;
                if (!above && start >= 0) {
                    if (i - start >= minRun) {
                        int end = i + win - 1;
                        runs.add(new int[]{start, end, lag});
                        for (int t = start; t < end; t++) {
                            rep[t]++;
                            if (t + lag < n) rep[t + lag]++;
                        }
                    }
                    start = -1;
                }
            }
        }

        float maxRms = 0;
        for (int t = 0; t < n; t++) maxRms = Math.max(maxRms, rms[t]);
        float[] score = new float[n];
        for (int t = 0; t < n; t++) {
            score[t] = rep[t] * Math.min(1, maxRms > 0 ? rms[t] / (0.6f * maxRms) : 0);
        }

        // Total score of each 8 s stretch; the best stretch is the catchy part...
        int w = (int) Math.round(8 / stepSec);
        float[] sums = new float[n];
        float sum = 0;
        for (int t = n - 1; t >= 0; t--) {
            sum += score[t];
            if (t + w < n) sum -= score[t + w];
            sums[t] = sum;
        }
        int p = -1;
        float best = 0;
        for (int t = 0; t + w <= n; t++) {
            if (sums[t] > best) { best = sums[t]; p = t; }
        }
        if (p < 0) return new double[0];

        // ...and the runs that cover it say where else the same tune is played
        List<Integer> found = new ArrayList<>();
        found.add(p);
        for (int[] r : runs) {
            int a = r[0], b = r[1], lag = r[2];
            if (a <= p && p < b && p + lag < n) found.add(p + lag);
            if (a + lag <= p && p < b + lag) found.add(p - lag);
        }
        found.sort(null);
        List<List<Integer>> places = new ArrayList<>();
        int gap = (int) Math.round(6 / stepSec);
        for (int t : found) {
            List<Integer> last = places.isEmpty() ? null : places.get(places.size() - 1);
            if (last != null && t - last.get(last.size() - 1) < gap) {
                last.add(t);
            } else {
                List<Integer> place = new ArrayList<>();
                place.add(t);
                places.add(place);
            }
        }

        // Repeats tend to begin where a phrase begins: count where runs start
        float[] votes = new float[n];
        int half = Math.round(win / 2f);
        for (int[] r : runs) {
            if (r[0] + half < n) votes[r[0] + half]++;
            if (r[0] + r[2] + half < n) votes[r[0] + r[2] + half]++;
        }

        List<Double> starts = new ArrayList<>();
        for (List<Integer> place : places) {
            int mid = place.get(place.size() / 2);
            // Skip quiet versions of the tune
            float loud = 0;
            for (int t = mid; t < Math.min(n, mid + w); t++) loud += rms[t];
            if (loud / w < 0.4f * maxRms) continue;
            // Move to the most likely phrase start up to 10 s before (or 2 s after)...
            int s = mid;
            float bestVotes = 1;
            for (int t = Math.max(1, mid - (int) Math.round(10 / stepSec));
                 t <= Math.min(n - 2, mid + (int) Math.round(2 / stepSec)); t++) {
                float v = votes[t - 1] + votes[t] + votes[t + 1];
                if (v > bestVotes) { bestVotes = v; s = t; }
            }
            // ...then onto the nearest note attack (biggest jump in loudness within 0.75 s)
            int f0 = s * GROUP;
            int reach = (int) Math.round(0.75 / frameSec);
            int bestF = f0;
            float bestJump = Float.NEGATIVE_INFINITY;
            for (int f = Math.max(1, f0 - reach); f <= Math.min(frameRms.length - 1, f0 + reach); f++) {
                float jump = frameRms[f] - frameRms[f - 1];
                if (jump > bestJump) { bestJump = jump; bestF = f; }
            }
            double time = Math.round(bestF * frameSec * 10) / 10.0;
            if (starts.isEmpty() || time - starts.get(starts.size() - 1) >= 6) starts.add(time);
        }

        double[] result = new double[Math.min(8, starts.size())];
        for (int i = 0; i < result.length; i++) result[i] = starts.get(i);
        return result;
    }
}
