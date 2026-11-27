// Sound pads: tidying up a recorded sample and saving it as a WAV file.
const Pads = {
  COUNT: 8,
  MAX_SECONDS: 10,

  // Cuts the silence before and after the sound and makes it loud and clear.
  // samples: mono Float32Array. Returns a new Float32Array, or null if nothing was heard.
  tidy(samples, rate) {
    let peak = 0;
    for (let i = 0; i < samples.length; i++) peak = Math.max(peak, Math.abs(samples[i]));
    if (peak < 0.01) return null;
    const threshold = Math.max(0.01, peak / 10);
    let start = 0;
    let end = samples.length;
    while (start < end && Math.abs(samples[start]) < threshold) start++;
    while (end > start && Math.abs(samples[end - 1]) < threshold) end--;
    if (end - start < rate * 0.05) return null;
    // Keep a moment before (so the start isn't clipped) and after (so it dies away naturally)
    start = Math.max(0, start - Math.round(rate * 0.01));
    end = Math.min(samples.length, end + Math.round(rate * 0.1));
    const out = samples.slice(start, end);
    const gain = Math.min(0.9 / peak, 8);
    // A very short fade at each end so the sample doesn't click
    const fade = Math.min(Math.round(rate * 0.005), out.length >> 1);
    for (let i = 0; i < out.length; i++) {
      let g = gain;
      if (i < fade) g *= i / fade;
      if (i >= out.length - fade) g *= (out.length - 1 - i) / fade;
      out[i] *= g;
    }
    return out;
  },

  // 16-bit mono WAV file bytes
  wav(samples, rate) {
    const bytes = new Uint8Array(44 + samples.length * 2);
    const v = new DataView(bytes.buffer);
    const text = (at, s) => { for (let i = 0; i < s.length; i++) v.setUint8(at + i, s.charCodeAt(i)); };
    text(0, 'RIFF'); v.setUint32(4, 36 + samples.length * 2, true); text(8, 'WAVE');
    text(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
    v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
    text(36, 'data'); v.setUint32(40, samples.length * 2, true);
    for (let i = 0; i < samples.length; i++) {
      v.setInt16(44 + i * 2, Math.round(Math.max(-1, Math.min(1, samples[i])) * 32767), true);
    }
    return bytes;
  },
};
