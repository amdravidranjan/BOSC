// Acoustic features of a voice clip (Float32Array, 16 kHz mono), pure JS, no model download.
// Used for two things:
//   - synthetic-voice score (small logistic model trained on these features, see voice-models.json)
//   - voice-print for the trusted-voice (impersonation) check
// Frame: 32 ms Hann window, 10 ms hop. Pitch by normalised autocorrelation (60-400 Hz).
// Works in a Worker or on the main thread.

export const SR = 16000;
const N = 512, HOP = 160, NB = 24;            // FFT size, hop, mel bands
const F0_MIN = 60, F0_MAX = 400;

let hann = null, melFilters = null;
function init() {
  if (hann) return;
  hann = new Float32Array(N);
  for (let i = 0; i < N; i++) hann[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (N - 1));
  // triangular mel filters, 80-3800 Hz (phone band only, so the features survive a phone line / speakerphone)
  const mel = (f) => 2595 * Math.log10(1 + f / 700), imel = (m) => 700 * (10 ** (m / 2595) - 1);
  const lo = mel(80), hi = mel(3800);
  const pts = [];
  for (let i = 0; i <= NB + 1; i++) pts.push(Math.round((imel(lo + ((hi - lo) * i) / (NB + 1)) / (SR / 2)) * (N / 2)));
  melFilters = [];
  for (let b = 0; b < NB; b++) {
    const [a, c, d] = [pts[b], pts[b + 1], pts[b + 2]];
    const f = new Float32Array(N / 2 + 1);
    for (let k = a; k < c; k++) f[k] = (k - a) / Math.max(1, c - a);
    for (let k = c; k <= d; k++) f[k] = (d - k) / Math.max(1, d - c);
    melFilters.push(f);
  }
}

// in-place radix-2 FFT (re, im)
function fft(re, im) {
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) { [re[i], re[j]] = [re[j], re[i]]; [im[i], im[j]] = [im[j], im[i]]; }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = (-2 * Math.PI) / len, wr = Math.cos(ang), wi = Math.sin(ang);
    for (let i = 0; i < n; i += len) {
      let cr = 1, ci = 0;
      for (let k = 0; k < len / 2; k++) {
        const ur = re[i + k], ui = im[i + k];
        const vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci;
        const vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr;
        re[i + k] = ur + vr; im[i + k] = ui + vi;
        re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi;
        const t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
      }
    }
  }
}

function stats(arr) {
  const n = arr.length;
  if (!n) return { mean: 0, std: 0 };
  let s = 0; for (const v of arr) s += v;
  const mean = s / n;
  let v = 0; for (const x of arr) v += (x - mean) ** 2;
  return { mean, std: Math.sqrt(v / n) };
}
function median(arr) { const a = Array.from(arr).sort((x, y) => x - y); return a.length ? a[a.length >> 1] : 0; }

// Per-frame analysis: energy, pitch (f0 or 0), pitch clarity, mel log-energies, centroid, flatness, rolloff.
function analyse(x) {
  init();
  const frames = [];
  const re = new Float32Array(N), im = new Float32Array(N);
  const minLag = Math.floor(SR / F0_MAX), maxLag = Math.ceil(SR / F0_MIN);
  for (let p = 0; p + N <= x.length; p += HOP) {
    let e = 0, mean = 0;
    for (let i = 0; i < N; i++) mean += x[p + i];
    mean /= N;
    for (let i = 0; i < N; i++) { const v = x[p + i] - mean; re[i] = v * hann[i]; im[i] = 0; e += v * v; }
    const rms = Math.sqrt(e / N);
    // pitch: normalised autocorrelation on the raw (de-meaned) frame
    let bestLag = 0, best = 0;
    if (rms > 1e-4) {
      let e0 = 0; for (let i = 0; i < N - maxLag; i++) e0 += (x[p + i] - mean) ** 2;
      for (let lag = minLag; lag <= maxLag; lag++) {
        let c = 0, e1 = 0;
        for (let i = 0; i < N - maxLag; i++) { const a = x[p + i] - mean, b = x[p + i + lag] - mean; c += a * b; e1 += b * b; }
        const r = c / Math.sqrt((e0 + 1e-9) * (e1 + 1e-9));
        if (r > best) { best = r; bestLag = lag; }
      }
    }
    fft(re, im);
    const mag = new Float32Array(N / 2 + 1);
    let tot = 0, cen = 0, logSum = 0;
    for (let k = 0; k <= N / 2; k++) { mag[k] = Math.sqrt(re[k] * re[k] + im[k] * im[k]); tot += mag[k]; cen += k * mag[k]; logSum += Math.log(mag[k] + 1e-9); }
    const centroid = tot > 0 ? ((cen / tot) * SR) / N : 0;
    const flat = tot > 0 ? Math.exp(logSum / (N / 2 + 1)) / (tot / (N / 2 + 1)) : 0;
    let acc = 0, roll = 0;
    for (let k = 0; k <= N / 2; k++) { acc += mag[k]; if (acc >= 0.85 * tot) { roll = (k * SR) / N; break; } }
    const mel = new Float32Array(NB);
    for (let b = 0; b < NB; b++) { let s = 0; const f = melFilters[b]; for (let k = 0; k <= N / 2; k++) s += f[k] * mag[k]; mel[b] = Math.log(s + 1e-6); }
    frames.push({ rms, f0: best > 0.55 && bestLag ? SR / bestLag : 0, clarity: best, mel, centroid, flat, roll });
  }
  return frames;
}

export const FEATURE_NAMES = [
  "dur", "voiced_ratio", "f0_mean", "f0_std_rel", "f0_range_rel", "jitter", "shimmer", "clarity_mean", "clarity_std",
  "centroid_mean", "centroid_std", "flat_mean", "flat_std", "roll_mean", "energy_std_rel", "pause_ratio", "pause_count_ps",
  "mel_slope", "mel_hf_ratio", "dmel_mean", "f0_jump_rate",
];

// Compact feature vector for the synthetic-voice classifier (order = FEATURE_NAMES).
export function voiceFeatures(x) {
  const fr = analyse(x);
  const dur = x.length / SR;
  if (fr.length < 20) return null;
  const rmsAll = fr.map((f) => f.rms);
  const thr = Math.max(1e-4, median(rmsAll) * 0.3);
  const speech = fr.filter((f) => f.rms > thr);
  const voiced = speech.filter((f) => f.f0 > 0);
  const vr = speech.length ? voiced.length / speech.length : 0;
  // pitch stats on voiced frames
  const f0s = voiced.map((f) => f.f0);
  const f0 = stats(f0s);
  const f0Med = median(f0s) || 1;
  // jitter: relative change of f0 between consecutive voiced frames (within runs)
  let jit = [], shim = [], jumps = 0, runs = 0;
  for (let i = 1; i < fr.length; i++) {
    const a = fr[i - 1], b = fr[i];
    if (a.f0 > 0 && b.f0 > 0) {
      const d = Math.abs(b.f0 - a.f0) / ((a.f0 + b.f0) / 2);
      if (d < 0.25) { jit.push(d); shim.push(Math.abs(b.rms - a.rms) / ((a.rms + b.rms) / 2 + 1e-9)); }
      else jumps++;
      runs++;
    }
  }
  const pauses = fr.map((f) => f.rms <= thr);
  let pc = 0;
  for (let i = 1; i < pauses.length; i++) if (pauses[i] && !pauses[i - 1]) pc++;
  const clar = stats(voiced.map((f) => f.clarity));
  const cen = stats(speech.map((f) => f.centroid));
  const flat = stats(speech.map((f) => f.flat));
  const roll = stats(speech.map((f) => f.roll));
  const en = stats(speech.map((f) => f.rms));
  // long-term mel spectrum shape
  const melMean = new Float32Array(NB);
  for (const f of speech) for (let b = 0; b < NB; b++) melMean[b] += f.mel[b] / speech.length;
  let slope = 0;
  for (let b = 1; b < NB; b++) slope += (melMean[b] - melMean[b - 1]) / (NB - 1);
  let lo = 0, hi = 0;
  for (let b = 0; b < NB / 2; b++) lo += melMean[b];
  for (let b = NB / 2; b < NB; b++) hi += melMean[b];
  // frame-to-frame spectral change (speech dynamics)
  let dm = 0, dn = 0;
  for (let i = 1; i < speech.length; i++) { let d = 0; for (let b = 0; b < NB; b++) d += Math.abs(speech[i].mel[b] - speech[i - 1].mel[b]); dm += d / NB; dn++; }
  return [
    Math.min(dur, 15), vr, f0.mean, f0.std / f0Med, (f0s.length ? (Math.max(...f0s) - Math.min(...f0s)) / f0Med : 0),
    stats(jit).mean, stats(shim).mean, clar.mean, clar.std,
    cen.mean / 1000, cen.std / 1000, flat.mean, flat.std, roll.mean / 1000, en.std / (en.mean + 1e-9),
    fr.length ? pauses.filter(Boolean).length / fr.length : 0, pc / dur,
    slope, (hi - lo) / NB, dn ? dm / dn : 0, runs ? jumps / runs : 0,
  ];
}

// Voice-print for speaker comparison: long-term mel shape (level-normalised) + pitch statistics.
// Compared with cosine similarity after z-scoring with population stats (voice-models.json).
export function voicePrint(x) {
  const fr = analyse(x);
  const thr = Math.max(1e-4, median(fr.map((f) => f.rms)) * 0.3);
  const speech = fr.filter((f) => f.rms > thr);
  if (speech.length < 30) return null;
  const voiced = speech.filter((f) => f.f0 > 0);
  const m = new Float32Array(NB), s = new Float32Array(NB);
  for (const f of speech) { let lvl = 0; for (let b = 0; b < NB; b++) lvl += f.mel[b] / NB; for (let b = 0; b < NB; b++) m[b] += (f.mel[b] - lvl) / speech.length; }
  for (const f of speech) { let lvl = 0; for (let b = 0; b < NB; b++) lvl += f.mel[b] / NB; for (let b = 0; b < NB; b++) s[b] += ((f.mel[b] - lvl - m[b]) ** 2) / speech.length; }
  const f0s = voiced.map((f) => f.f0);
  const f0 = stats(f0s);
  const q = (p) => { const a = f0s.slice().sort((x, y) => x - y); return a.length ? a[Math.floor(p * (a.length - 1))] : 0; };
  return [...m, ...Array.from(s, Math.sqrt), f0.mean / 100, f0.std / 100, q(0.2) / 100, q(0.8) / 100,
    stats(speech.map((f) => f.centroid)).mean / 1000, stats(speech.map((f) => f.flat)).mean];
}

export function sigmoid(z) { return 1 / (1 + Math.exp(-z)); }

// Logistic model from voice-models.json: {mean, std, coef, intercept}
export function syntheticScore(feat, model) {
  if (!feat || !model) return null;
  let z = model.intercept;
  for (let i = 0; i < feat.length; i++) z += model.coef[i] * ((feat[i] - model.mean[i]) / (model.std[i] || 1));
  return sigmoid(z);
}

export function printSimilarity(a, b, model) {
  if (!a || !b) return null;
  let dot = 0, na = 0, nb = 0;
  for (let i = 0; i < a.length; i++) {
    const sd = (model && model.print_std && model.print_std[i]) || 1, mu = (model && model.print_mean && model.print_mean[i]) || 0;
    const x = (a[i] - mu) / sd, y = (b[i] - mu) / sd;
    dot += x * y; na += x * x; nb += y * y;
  }
  return dot / (Math.sqrt(na * nb) + 1e-9);
}

export function meanPrint(prints) {
  const out = new Array(prints[0].length).fill(0);
  for (const p of prints) for (let i = 0; i < p.length; i++) out[i] += p[i] / prints.length;
  return out;
}
