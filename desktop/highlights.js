// Finds the catchy part of a song: its main theme or chorus.
// The idea: the memorable tune is the part whose notes come back the most often,
// while intros, bridges and endings happen only once. Quiet parts count for less.
const Highlights = (() => {
  const RATE = 11025;  // songs are decoded at this rate for analysis
  const FRAME = 2048;  // ~0.19 s
  const HOP = 1024;    // a frame every ~0.09 s
  const GROUP = 5;     // frames per step, ~0.46 s

  function fft(re, im) {
    const n = re.length;
    for (let i = 1, j = 0; i < n; i++) {
      let bit = n >> 1;
      for (; j & bit; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) {
        [re[i], re[j]] = [re[j], re[i]];
        [im[i], im[j]] = [im[j], im[i]];
      }
    }
    for (let len = 2; len <= n; len <<= 1) {
      const ang = -2 * Math.PI / len;
      const wr = Math.cos(ang), wi = Math.sin(ang);
      const half = len >> 1;
      for (let i = 0; i < n; i += len) {
        let cr = 1, ci = 0;
        for (let k = 0; k < half; k++) {
          const a = i + k, b = a + half;
          const tr = re[b] * cr - im[b] * ci;
          const ti = re[b] * ci + im[b] * cr;
          re[b] = re[a] - tr; im[b] = im[a] - ti;
          re[a] += tr; im[a] += ti;
          const ncr = cr * wr - ci * wi;
          ci = cr * wi + ci * wr;
          cr = ncr;
        }
      }
    }
  }

  // Per step: how strong each of the 12 notes is (chroma), and how loud it is
  function features(samples, rate) {
    const window = new Float32Array(FRAME);
    for (let i = 0; i < FRAME; i++) window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / FRAME);
    // Which note each FFT bin belongs to (only ~65 Hz to ~2 kHz, where melodies live)
    const binNote = new Int8Array(FRAME / 2).fill(-1);
    for (let k = 1; k < FRAME / 2; k++) {
      const f = k * rate / FRAME;
      if (f >= 65 && f <= 2100) binNote[k] = ((Math.round(12 * Math.log2(f / 440)) + 69) % 12 + 12) % 12;
    }

    const frames = Math.max(0, Math.floor((samples.length - FRAME) / HOP) + 1);
    const frameRms = new Float32Array(frames);
    const frameChroma = new Float32Array(frames * 12);
    const re = new Float32Array(FRAME), im = new Float32Array(FRAME);
    for (let f = 0; f < frames; f++) {
      const off = f * HOP;
      let energy = 0;
      for (let i = 0; i < FRAME; i++) {
        const s = samples[off + i];
        energy += s * s;
        re[i] = s * window[i];
        im[i] = 0;
      }
      frameRms[f] = Math.sqrt(energy / FRAME);
      fft(re, im);
      for (let k = 1; k < FRAME / 2; k++) {
        const note = binNote[k];
        if (note >= 0) frameChroma[f * 12 + note] += Math.hypot(re[k], im[k]);
      }
    }

    const n = Math.floor(frames / GROUP);
    const chroma = new Float32Array(n * 12);
    const rms = new Float32Array(n);
    for (let t = 0; t < n; t++) {
      for (let g = 0; g < GROUP; g++) {
        const f = t * GROUP + g;
        rms[t] += frameRms[f] / GROUP;
        for (let c = 0; c < 12; c++) chroma[t * 12 + c] += frameChroma[f * 12 + c];
      }
      // Log-compress, then scale to length 1 so only the note balance matters
      let norm = 0;
      for (let c = 0; c < 12; c++) {
        const v = Math.log1p(chroma[t * 12 + c]);
        chroma[t * 12 + c] = v;
        norm += v * v;
      }
      norm = Math.sqrt(norm);
      for (let c = 0; c < 12; c++) chroma[t * 12 + c] = norm > 1e-6 ? chroma[t * 12 + c] / norm : 0;
    }
    return { chroma, rms, frameRms, n, stepSec: HOP * GROUP / rate, frameSec: HOP / rate };
  }

  // How many times each step is part of a stretch that repeats somewhere else in the song
  function repetition(chroma, n, stepSec) {
    const minLag = Math.max(1, Math.round(4 / stepSec));  // repeats at least 4 s apart
    const win = Math.max(1, Math.round(3 / stepSec));     // compare 3 s stretches
    const minRun = Math.round(5 / stepSec);               // a repeat lasts 8 s or more in all

    const diags = [];
    let total = 0;
    for (let lag = minLag; lag < n - win; lag++) {
      const len = n - lag;
      const m = len - win + 1;
      if (m <= 0) continue;
      const sm = new Float32Array(m);
      let sum = 0;
      const d = new Float32Array(len);
      for (let i = 0; i < len; i++) {
        let dot = 0;
        for (let c = 0; c < 12; c++) dot += chroma[i * 12 + c] * chroma[(i + lag) * 12 + c];
        d[i] = dot;
      }
      for (let i = 0; i < len; i++) {
        sum += d[i];
        if (i >= win) sum -= d[i - win];
        if (i >= win - 1) sm[i - win + 1] = sum / win;
      }
      diags.push([lag, sm]);
      total += m;
    }

    // "Similar enough to be a repeat" = in the top 10% of all comparisons in this song
    const all = new Float32Array(total);
    let at = 0;
    for (const [, sm] of diags) { all.set(sm, at); at += sm.length; }
    all.sort();
    const threshold = all[Math.floor(all.length * 0.9)];

    // A run is a stretch [a, b) that sounds the same again `lag` steps later
    const rep = new Float32Array(n);
    const runs = [];
    for (const [lag, sm] of diags) {
      let start = -1;
      for (let i = 0; i <= sm.length; i++) {
        const above = i < sm.length && sm[i] > threshold;
        if (above && start < 0) start = i;
        if (!above && start >= 0) {
          if (i - start >= minRun) {
            const end = i + win - 1;
            runs.push({ a: start, b: end, lag });
            for (let t = start; t < end; t++) {
              rep[t]++;
              if (t + lag < n) rep[t + lag]++;
            }
          }
          start = -1;
        }
      }
    }
    return { rep, runs, win };
  }

  // Returns the start times (seconds) of the catchy part: every place in the song
  // where the most repeated, loud-enough tune begins, earliest first.
  function find(samples, rate) {
    const { chroma, rms, frameRms, n, stepSec, frameSec } = features(samples, rate);
    if (n * stepSec < 20) return { starts: [] };
    const { rep, runs, win } = repetition(chroma, n, stepSec);
    const sec = (s) => Math.round(s / stepSec);

    let maxRms = 0;
    for (let t = 0; t < n; t++) maxRms = Math.max(maxRms, rms[t]);
    const score = new Float32Array(n);
    for (let t = 0; t < n; t++) score[t] = rep[t] * Math.min(1, rms[t] / (0.6 * maxRms || 1));

    // Total score of each 8 s stretch
    const W = Math.round(8 / stepSec);
    const sums = new Float32Array(n);
    let sum = 0;
    for (let t = n - 1; t >= 0; t--) {
      sum += score[t];
      if (t + W < n) sum -= score[t + W];
      sums[t] = sum;
    }

    // The best stretch is the catchy part...
    let p = -1, best = 0;
    for (let t = 0; t + W <= n; t++) {
      if (sums[t] > best) { best = sums[t]; p = t; }
    }
    if (p < 0) return { starts: [], score, stepSec };

    // ...and the runs that cover it say where else the same tune is played
    const found = [p];
    for (const { a, b, lag } of runs) {
      if (a <= p && p < b && p + lag < n) found.push(p + lag);
      if (a + lag <= p && p < b + lag) found.push(p - lag);
    }
    found.sort((x, y) => x - y);
    const places = [];
    for (const t of found) {
      if (places.length && t - places[places.length - 1].last < sec(6)) {
        places[places.length - 1].all.push(t);
        places[places.length - 1].last = t;
      } else {
        places.push({ all: [t], last: t });
      }
    }

    // Repeats tend to begin where a phrase begins: count where runs start
    const votes = new Float32Array(n);
    const half = Math.round(win / 2);
    for (const { a, lag } of runs) {
      if (a + half < n) votes[a + half]++;
      if (a + lag + half < n) votes[a + lag + half]++;
    }

    const starts = [];
    for (const place of places) {
      const at = place.all[Math.floor(place.all.length / 2)];
      // Skip quiet versions of the tune
      let loud = 0;
      for (let t = at; t < Math.min(n, at + W); t++) loud += rms[t];
      if (loud / W < 0.4 * maxRms) continue;
      // Move to the most likely phrase start up to 10 s before (or 2 s after)...
      let s = at, bestVotes = 1;
      for (let t = Math.max(1, at - sec(10)); t <= Math.min(n - 2, at + sec(2)); t++) {
        const v = votes[t - 1] + votes[t] + votes[t + 1];
        if (v > bestVotes) { bestVotes = v; s = t; }
      }
      // ...then onto the nearest note attack (biggest jump in loudness within 0.75 s)
      const f0 = s * GROUP;
      const reach = Math.round(0.75 / frameSec);
      let bestF = f0, bestJump = -Infinity;
      for (let f = Math.max(1, f0 - reach); f <= Math.min(frameRms.length - 1, f0 + reach); f++) {
        const jump = frameRms[f] - frameRms[f - 1];
        if (jump > bestJump) { bestJump = jump; bestF = f; }
      }
      const time = Math.round(bestF * frameSec * 10) / 10;
      if (!starts.length || time - starts[starts.length - 1] >= 6) starts.push(time);
    }
    return { starts: starts.slice(0, 8), score, stepSec };
  }

  return { RATE, find };
})();
