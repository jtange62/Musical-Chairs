const COLOR_IDLE = '#37474F';
const COLOR_PLAY = '#2E7D32';
const COLOR_STOP = '#C62828';
const COLOR_PAUSE = '#F9A825';
// One colour per song in "Switch songs" mode (no red or yellow, those mean stop and pause)
const SONG_COLORS = ['#2E7D32', '#1565C0', '#6A1B9A', '#EF6C00', '#00838F', '#AD1457', '#4E342E', '#283593'];

const $ = (id) => document.getElementById(id);
const bigText = $('big');
const subText = $('sub');
const songText = $('song');
const seekBar = $('seek');
const timeText = $('time');
const mainButton = $('main');

const audio = new Audio();
let songs = [];        // file paths
let songIndex = 0;
let positions = [];    // where each song left off, for "Switch songs" mode
let ready = false;     // audio has a song loaded
let state = 'IDLE';    // IDLE, PLAYING, STOPPED, PAUSED
let pausedFrom = 'IDLE';
let countdown = 0;
let stopTimer = null;
let tickTimer = null;
let userSeeking = false;

// Settings (seconds)
function loadSetting(key, def) {
  try {
    const v = localStorage.getItem(key);
    return v === null ? def : JSON.parse(v);
  } catch { return def; }
}
let minPlay, maxPlay, restTime, autoRestart, mode, switchPause, startAt, highlights, padModes;
// Reads the saved settings; anything not saved gets its default
function loadSettings() {
  minPlay = loadSetting('minPlay', 10);
  maxPlay = loadSetting('maxPlay', 30);
  restTime = loadSetting('restTime', 10);
  autoRestart = loadSetting('autoRestart', true);
  mode = loadSetting('mode', 'chairs');  // 'chairs' or 'switch'
  switchPause = loadSetting('switchPause', 3);  // STOP screen between songs, 0 = fade straight over
  // "Switch songs": where each song starts - 'catchy' part, 'random' spot, or 'resume' where it left off
  startAt = loadSetting('startAt', 'catchy');
  // Per song file: { starts: [seconds of the catchy part], mark: seconds chosen by hand }
  highlights = loadSetting('highlights', {});
  // Sound pads: 'gate' plays only while held; anything else plays all the way through (one-shot)
  padModes = loadSetting('padModes', []);
}
loadSettings();

function saveSettings() {
  localStorage.setItem('startAt', JSON.stringify(startAt));
  localStorage.setItem('mode', JSON.stringify(mode));
  localStorage.setItem('switchPause', JSON.stringify(switchPause));
  localStorage.setItem('minPlay', JSON.stringify(minPlay));
  localStorage.setItem('maxPlay', JSON.stringify(maxPlay));
  localStorage.setItem('restTime', JSON.stringify(restTime));
  localStorage.setItem('autoRestart', JSON.stringify(autoRestart));
}

// ---------- UI ----------

let toastTimer = null;
function toast(msg, ms = 2000) {
  const t = $('toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), ms);
}

const stepperRefreshers = [];  // redraw each stepper's value after a reset to defaults

function stepper(label, get, set, min, max, step) {
  const row = document.createElement('div');
  row.className = 'stepper';
  row.innerHTML = '<span class="name"></span><button>−</button><span class="value"></span><button>+</button>';
  const [minus, plus] = row.querySelectorAll('button');
  const value = row.querySelector('.value');
  row.querySelector('.name').textContent = label;
  const show = () => { value.textContent = get() + ' s'; };
  minus.onclick = () => { set(Math.max(min, get() - step)); show(); saveSettings(); };
  plus.onclick = () => { set(Math.min(max, get() + step)); show(); saveSettings(); };
  show();
  stepperRefreshers.push(show);
  return row;
}

let minRow, maxRow, restRow, pauseRow;

function buildUi() {
  minRow = stepper('', () => minPlay, (v) => { minPlay = v; }, 3, 300, 1);
  maxRow = stepper('', () => maxPlay, (v) => { maxPlay = v; }, 3, 300, 1);
  restRow = stepper('Stop time before restart', () => restTime, (v) => { restTime = v; }, 1, 120, 1);
  pauseRow = stepper('Stop between songs', () => switchPause, (v) => { switchPause = v; }, 0, 60, 1);
  $('steppers').append(minRow, maxRow, restRow, pauseRow);

  $('modeChairs').onclick = () => setMode('chairs');
  $('modeSwitch').onclick = () => setMode('switch');
  showMode();

  const auto = $('auto');
  auto.checked = autoRestart;
  auto.onchange = () => {
    autoRestart = auto.checked;
    saveSettings();
    if (state === 'STOPPED') {
      clearTimeout(tickTimer);
      countdown = restTime;
      if (autoRestart) tickTimer = setTimeout(tick, 1000);
      updateUi();
    }
  };

  $('startCatchy').onclick = () => setStartAt('catchy');
  $('startRandom').onclick = () => setStartAt('random');
  $('startResume').onclick = () => setStartAt('resume');
  $('mark').onclick = markSpot;
  $('unmark').onclick = unmarkSpot;

  mainButton.onclick = onMainButton;
  $('reset').onclick = async () => {
    if (await window.desktop.confirmReset()) resetToDefaults();
  };
  $('pick').onclick = pickMusic;

  seekBar.addEventListener('input', () => {
    userSeeking = true;
    timeText.textContent = formatTime(+seekBar.value) + ' / ' + formatTime(audio.duration);
  });
  seekBar.addEventListener('change', () => {
    userSeeking = false;
    if (ready) audio.currentTime = +seekBar.value;
  });

  // Keep focus off buttons so Space always works the main button.
  document.addEventListener('mousedown', (e) => {
    if (e.target.tagName === 'BUTTON') e.preventDefault();
  });
  document.addEventListener('keydown', (e) => {
    if (e.repeat || e.target.tagName === 'INPUT') return;
    if (e.code === 'Space') {
      e.preventDefault();
      onMainButton();
    } else if (/^[1-8]$/.test(e.key)) {
      e.preventDefault();
      padDown(+e.key - 1);
    }
  });
  document.addEventListener('keyup', (e) => {
    if (/^[1-8]$/.test(e.key)) padUp(+e.key - 1);
  });

  buildPads();
}

function setMode(m) {
  if (m === mode) return;
  mode = m;
  resetGame();
  saveSettings();
  showMode();
  updateUi();
  if (mode === 'switch' && startAt === 'catchy') findCatchyParts();
}

function showMode() {
  const switching = mode === 'switch';
  $('modeChairs').classList.toggle('on', !switching);
  $('modeSwitch').classList.toggle('on', switching);
  minRow.querySelector('.name').textContent = switching ? 'Switch song after at least' : 'Music plays at least';
  maxRow.querySelector('.name').textContent = switching ? 'Switch song after at most' : 'Music plays at most';
  restRow.hidden = switching;
  pauseRow.hidden = !switching;
  $('autoRow').hidden = switching;
  $('startRow').hidden = !switching;
  $('startCatchy').classList.toggle('on', startAt === 'catchy');
  $('startRandom').classList.toggle('on', startAt === 'random');
  $('startResume').classList.toggle('on', startAt === 'resume');
  showMark();
}

function setStartAt(s) {
  startAt = s;
  saveSettings();
  showMode();
  if (startAt === 'catchy') findCatchyParts();
}

// ---------- Catchy parts ----------

let analyzing = null;  // { done, total } while songs are being analyzed

// Analyzes any chosen songs not analyzed yet, one at a time, in the background
async function findCatchyParts() {
  if (analyzing) return;
  const todo = () => songs.filter((p) => !highlights[p] || !highlights[p].starts);
  analyzing = { done: 0, total: todo().length };
  updateUi();
  let path;
  while ((path = todo()[0])) {
    let starts = [];
    try {
      starts = await analyzeSong(path);
    } catch (e) {
      console.error('Could not analyze', path, e);
    }
    // Skip a song that was cleared by a reset while it was being analyzed
    if (songs.includes(path)) {
      highlights[path] = { ...highlights[path], starts };
      try { localStorage.setItem('highlights', JSON.stringify(highlights)); } catch { /* storage full */ }
    }
    // The first song was cued up before its analysis finished: cue it to the catchy part now
    if (path === songs[songIndex] && state === 'IDLE' && ready) audio.currentTime = startPosition(songIndex);
    analyzing.done++;
    updateUi();
  }
  analyzing = null;
  updateUi();
}

async function analyzeSong(path) {
  const bytes = await window.desktop.readFile(path);
  const ctx = new OfflineAudioContext(1, 1, Highlights.RATE);
  const buf = await ctx.decodeAudioData(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));
  return Highlights.find(toMono(buf), buf.sampleRate).starts;
}

// All channels of an AudioBuffer mixed into one
function toMono(buf) {
  const mono = new Float32Array(buf.length);
  for (let c = 0; c < buf.numberOfChannels; c++) {
    const data = buf.getChannelData(c);
    for (let i = 0; i < data.length; i++) mono[i] += data[i] / buf.numberOfChannels;
  }
  return mono;
}

function showMark() {
  const song = songs[songIndex];
  $('markRow').hidden = !(mode === 'switch' && startAt === 'catchy' && ready);
  $('unmark').hidden = !(song && highlights[song] && highlights[song].mark != null);
}

// Sets where the current song starts from now on (just before now, to allow for reaction time)
function markSpot() {
  if (!ready) return;
  const song = songs[songIndex];
  const at = Math.max(0, Math.round((audio.currentTime - 0.3) * 10) / 10);
  highlights[song] = { ...highlights[song], mark: at };
  localStorage.setItem('highlights', JSON.stringify(highlights));
  toast(songTitle(song) + ' will start at ' + formatTime(at));
  updateUi();
}

function unmarkSpot() {
  const song = songs[songIndex];
  if (!highlights[song]) return;
  delete highlights[song].mark;
  localStorage.setItem('highlights', JSON.stringify(highlights));
  toast(songTitle(song) + ' will start at its catchy part');
  updateUi();
}

// Whether the STOP countdown runs by itself ("Switch songs" always moves on)
function autoNext() {
  return mode === 'switch' || autoRestart;
}

function updateUi() {
  window.desktop.keepAwake(state === 'PLAYING' || state === 'STOPPED');
  let bg;
  bigText.classList.remove('title');
  switch (state) {
    case 'PLAYING':
      if (mode === 'switch') {
        bg = SONG_COLORS[songIndex % SONG_COLORS.length];
        bigText.textContent = songTitle(songs[songIndex]);
        bigText.classList.add('title');
      } else {
        bg = COLOR_PLAY;
        bigText.textContent = '🎵 Walk! 🎵';
      }
      subText.textContent = '';
      mainButton.textContent = 'Pause';
      break;
    case 'STOPPED':
      bg = COLOR_STOP;
      bigText.textContent = 'STOP!\n🪑';
      subText.textContent = autoNext()
        ? (mode === 'switch' ? 'Next song in ' : 'Music in ') + countdown + '…'
        : 'Press “Play now” when ready';
      mainButton.textContent = 'Play now';
      break;
    case 'PAUSED':
      bg = COLOR_PAUSE;
      bigText.textContent = 'Paused';
      subText.textContent = '';
      mainButton.textContent = 'Resume';
      break;
    default:
      bg = COLOR_IDLE;
      bigText.textContent = 'Musical\nChairs';
      subText.textContent = songs.length === 0 ? 'Choose music to begin'
        : mode === 'switch' && songs.length < 2 ? 'Choose 2 or more songs' : '';
      mainButton.textContent = 'Start';
  }
  document.body.style.backgroundColor = bg;
  songText.textContent = songs.length === 0 ? 'No music chosen'
    : '♪ ' + songName(songs[songIndex]) + (songs.length > 1
      ? '  (' + (songIndex + 1) + '/' + songs.length + ')' : '');
  if (analyzing && mode === 'switch' && startAt === 'catchy') {
    songText.textContent += '  ·  finding catchy parts ' + analyzing.done + '/' + analyzing.total + '…';
  }
  showMark();
  updateProgress();
}

function updateProgress() {
  seekBar.disabled = !ready;
  if (!ready) {
    seekBar.max = 1;
    seekBar.value = 0;
    timeText.textContent = '0:00 / 0:00';
    return;
  }
  const duration = audio.duration || 0;
  if (!userSeeking) {
    seekBar.max = Math.max(duration, 1);
    seekBar.value = audio.currentTime;
  }
  timeText.textContent = formatTime(+seekBar.value) + ' / ' + formatTime(duration);
}

function formatTime(sec) {
  const s = Math.floor(Math.max(sec || 0, 0));
  return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
}

// ---------- Game logic ----------

function onMainButton() {
  switch (state) {
    case 'IDLE': startPlaying(); break;
    case 'PLAYING': pauseGame(); break;
    case 'PAUSED': resumeGame(); break;
    case 'STOPPED': startPlaying(); break;
  }
}

async function startPlaying() {
  if (songs.length === 0) {
    toast('Choose music first');
    pickMusic();
    return;
  }
  if (mode === 'switch' && songs.length < 2) {
    toast('Choose 2 or more songs for “Switch songs”');
    pickMusic();
    return;
  }
  clearTimeout(tickTimer);
  clearTimeout(stopTimer);
  if (mode === 'switch' && state === 'STOPPED') {
    // After the STOP screen, carry on with a different song
    if (!(await preparePlayer(pickNext())) || state !== 'STOPPED') return;
  }
  await preparing;
  if (songs.length === 0) return;  // reset while the song was loading
  if (!ready && !(await preparePlayer(songIndex))) return;
  audio.volume = 1;
  audio.play().catch(() => {});
  state = 'PLAYING';
  scheduleStop();
  updateUi();
}

function scheduleStop() {
  const lo = Math.min(minPlay, maxPlay);
  const hi = Math.max(minPlay, maxPlay);
  // random time in milliseconds between lo and hi seconds
  const ms = lo * 1000 + Math.random() * (hi - lo) * 1000;
  stopTimer = setTimeout(switchTime, ms);
}

// The random moment: stop (musical chairs, or "Switch songs" with a stop), or fade to the next song.
function switchTime() {
  clearTimeout(stopTimer);
  if (mode === 'switch' && switchPause === 0) switchSong();
  else doStop();
}

// A random song other than the current one
function pickNext() {
  let next = Math.floor(Math.random() * (songs.length - 1));
  if (next >= songIndex) next++;
  return next;
}

function savePosition() {
  positions[songIndex] = audio.ended ? 0 : audio.currentTime;
}

// A random spot in the song that leaves enough music to play until the next switch
function randomSpot(duration) {
  const room = duration - Math.max(minPlay, maxPlay) - 2;
  return room > 0 ? Math.random() * room : 0;
}

// Where song i starts in "Switch songs" mode
function startPosition(i) {
  if (startAt === 'resume') return positions[i] || 0;
  const h = highlights[songs[i]];
  if (startAt === 'catchy' && h) {
    if (h.mark != null) return h.mark;
    // One of the places the catchy part is played, picked at random
    if (h.starts && h.starts.length) return h.starts[Math.floor(Math.random() * h.starts.length)];
  }
  return randomSpot(audio.duration);
}

let fadeTimer = null;
let switchId = 0;

function fadeOut(ms) {
  return new Promise((resolve) => {
    const steps = 10;
    let n = 0;
    fadeTimer = setInterval(() => {
      audio.volume = Math.max(0, 1 - ++n / steps);
      if (n >= steps) { clearInterval(fadeTimer); resolve(); }
    }, ms / steps);
  });
}

// Stops a song switch that is part way through (on pause or reset).
function cancelSwitch() {
  clearInterval(fadeTimer);
  switchId++;
}

// "Switch songs" mode: fade out, then carry on with a different song from where it left off.
async function switchSong() {
  clearTimeout(stopTimer);
  const id = ++switchId;
  await fadeOut(400);
  if (id !== switchId) return;
  audio.pause();
  savePosition();
  if (!(await preparePlayer(pickNext())) || id !== switchId) return;
  audio.volume = 1;
  audio.play().catch(() => {});
  scheduleStop();
  updateUi();
}

function doStop() {
  audio.pause();
  if (mode === 'switch') savePosition();
  beep();
  state = 'STOPPED';
  countdown = mode === 'switch' ? switchPause : restTime;
  updateUi();
  if (autoNext()) tickTimer = setTimeout(tick, 1000);
}

function tick() {
  countdown--;
  if (countdown <= 0) {
    startPlaying();
  } else {
    updateUi();
    tickTimer = setTimeout(tick, 1000);
  }
}

function pauseGame() {
  if (state === 'PLAYING') {
    clearTimeout(stopTimer);
    cancelSwitch();
    audio.pause();
  } else if (state === 'STOPPED') {
    clearTimeout(tickTimer);
  }
  pausedFrom = state;
  state = 'PAUSED';
  updateUi();
}

function resumeGame() {
  if (pausedFrom === 'STOPPED') {
    state = 'STOPPED';
    updateUi();
    if (autoNext()) tickTimer = setTimeout(tick, 1000);
  } else {
    startPlaying();
  }
}

function resetGame() {
  clearTimeout(stopTimer);
  clearTimeout(tickTimer);
  cancelSwitch();
  audio.pause();
  if (ready) audio.currentTime = mode === 'switch' && startAt !== 'resume' ? startPosition(songIndex) : 0;
  state = 'IDLE';
  updateUi();
}

// The Reset button: back to how the app is on first install - no music, default settings, no saved spots.
function resetToDefaults() {
  resetGame();
  songs = [];
  songIndex = 0;
  positions = [];
  localStorage.clear();
  loadSettings();
  // Unload the song once any load in progress has finished
  preparing = preparing.then(() => {
    ready = false;
    audio.removeAttribute('src');
    audio.load();
    updateUi();
    return false;
  });
  stepperRefreshers.forEach((show) => show());
  $('auto').checked = autoRestart;
  cancelRecording();
  for (let i = 0; i < Pads.COUNT; i++) stopGate(i);
  pads = [];
  editingPads = false;
  window.desktop.padsClear();
  showPads();
  showMode();
  updateUi();
  toast('Reset to defaults');
}

// ---------- Sound pads ----------

const PAD_COLORS = ['#E91E63', '#8E24AA', '#3949AB', '#039BE5', '#00897B', '#7CB342', '#FB8C00', '#6D4C41'];
let pads = [];          // AudioBuffer for each pad, undefined while the pad is empty
let recording = null;   // the pad being recorded: { pad, starting, stream, recorder, started, saving, cancelled }
let padMenuOpen = false;
let editingPads = false;  // "Edit pads" is on: a tap opens the pad's menu instead of playing it
let gateSounds = [];      // the sound playing on each gate pad while it is held
let padCtx = null;

function padAudio() {
  padCtx = padCtx || new AudioContext({ latencyHint: 'interactive' });
  if (padCtx.state === 'suspended') padCtx.resume();
  return padCtx;
}

function buildPads() {
  const grid = $('pads');
  for (let i = 0; i < Pads.COUNT; i++) {
    const b = document.createElement('button');
    b.className = 'pad';
    // Sounds play the moment the pad is pressed (and gate pads stop when it's let go)
    b.addEventListener('pointerdown', (e) => {
      if (e.button === 0) padDown(i);
    });
    for (const ev of ['pointerup', 'pointerleave', 'pointercancel']) b.addEventListener(ev, () => padUp(i));
    b.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      padMenu(i);
    });
    grid.append(b);
  }
  $('editPads').onclick = () => {
    editingPads = !editingPads;
    showPads();
  };
  showPads();
}

function showPads() {
  $('pads').querySelectorAll('.pad').forEach((b, i) => {
    const rec = recording && recording.pad === i ? recording : null;
    b.classList.toggle('rec', !!rec);
    b.classList.toggle('empty', !rec && !pads[i]);
    b.style.backgroundColor = !rec && pads[i] ? PAD_COLORS[i] : '';
    b.textContent = rec
      ? (rec.starting ? '…' : rec.saving ? 'Saving…' : '● ' + Math.floor((Date.now() - rec.started) / 1000) + 's')
      : pads[i] ? (padModes[i] === 'gate' ? '✋ ' : '') + (i + 1) : '🎙';
  });
  $('pads').classList.toggle('editing', editingPads);
  $('editPads').textContent = editingPads ? '✓ Done editing' : '✏️ Edit pads';
  $('padsHint').textContent = editingPads
    ? 'Tap a pad to record it again, switch it between one-shot and ✋ hold, or delete it.'
    : 'Tap 🎙 to record, tap again to stop. ✋ pads play only while held. Keys 1–8 play the pads.';
}

// A pad (or number key) pressed: open its menu while editing, else play / record / stop recording
function padDown(i) {
  if (editingPads && pads[i] && !recording) {
    padMenu(i);
    return;
  }
  if (recording) {
    if (recording.pad === i) stopRecording();
    return;
  }
  if (pads[i]) playPad(i);
  else startRecording(i);
}

// A pad (or number key) let go: gate pads stop
function padUp(i) {
  stopGate(i);
}

function playPad(i) {
  const ctx = padAudio();
  const src = ctx.createBufferSource();
  const gain = ctx.createGain();
  src.buffer = pads[i];
  src.connect(gain).connect(ctx.destination);
  src.start();
  if (padModes[i] === 'gate') {
    stopGate(i);
    const sound = { src, gain };
    gateSounds[i] = sound;
    src.onended = () => { if (gateSounds[i] === sound) gateSounds[i] = null; };
  }
  const b = $('pads').children[i];
  b.classList.add('hit');
  setTimeout(() => b.classList.remove('hit'), 120);
}

// Stops a gate pad's sound with a very short fade, so it doesn't click
function stopGate(i) {
  const sound = gateSounds[i];
  if (!sound) return;
  gateSounds[i] = null;
  const t = padCtx.currentTime;
  sound.gain.gain.setValueAtTime(sound.gain.gain.value, t);
  sound.gain.gain.linearRampToValueAtTime(0, t + 0.015);
  sound.src.stop(t + 0.02);
}

function savePadModes() {
  localStorage.setItem('padModes', JSON.stringify(padModes));
}

async function padMenu(i) {
  if (padMenuOpen || recording || !pads[i]) return;
  padMenuOpen = true;
  const choice = await window.desktop.padMenu(i + 1, padModes[i] === 'gate');
  padMenuOpen = false;
  if (recording || !pads[i]) return;
  if (choice === 'record') {
    startRecording(i);
  } else if (choice === 'mode') {
    padModes[i] = padModes[i] === 'gate' ? null : 'gate';
    savePadModes();
    toast('Pad ' + (i + 1) + (padModes[i] === 'gate' ? ' plays only while held ✋' : ' plays all the way through'));
    showPads();
  } else if (choice === 'delete') {
    stopGate(i);
    pads[i] = undefined;
    padModes[i] = null;
    savePadModes();
    window.desktop.padDelete(i);
    showPads();
  }
}

async function startRecording(i) {
  const rec = { pad: i, starting: true };
  recording = rec;
  showPads();
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      // Raw sound: no echo cancelling (it would cut out the music being recorded)
      audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false },
    });
  } catch (e) {
    console.error('Microphone', e);
    if (recording === rec) recording = null;
    showPads();
    toast("Couldn't use the microphone. Check that Windows lets apps use it.", 3500);
    return;
  }
  if (rec.cancelled) {
    stream.getTracks().forEach((t) => t.stop());
    return;
  }
  const chunks = [];
  rec.stream = stream;
  rec.recorder = new MediaRecorder(stream);
  rec.recorder.ondataavailable = (e) => chunks.push(e.data);
  rec.recorder.onstop = () => finishRecording(rec, chunks);
  rec.recorder.start();
  rec.started = Date.now();
  rec.starting = false;
  rec.ticker = setInterval(showPads, 250);
  rec.limit = setTimeout(stopRecording, Pads.MAX_SECONDS * 1000);
  showPads();
}

function stopRecording() {
  const rec = recording;
  if (!rec || !rec.recorder || rec.saving) return;
  rec.saving = true;
  clearInterval(rec.ticker);
  clearTimeout(rec.limit);
  rec.recorder.stop();
  showPads();
}

async function finishRecording(rec, chunks) {
  rec.stream.getTracks().forEach((t) => t.stop());
  if (rec.cancelled) return;
  try {
    const ctx = padAudio();
    const decoded = await ctx.decodeAudioData(await new Blob(chunks).arrayBuffer());
    const sound = Pads.tidy(toMono(decoded), decoded.sampleRate);
    if (rec.cancelled) return;
    if (!sound) {
      toast("Didn't hear anything. Try again a bit closer to the microphone.", 3000);
    } else {
      const buf = ctx.createBuffer(1, sound.length, decoded.sampleRate);
      buf.copyToChannel(sound, 0);
      pads[rec.pad] = buf;
      await window.desktop.padSave(rec.pad, Pads.wav(sound, decoded.sampleRate));
      if (rec.cancelled) window.desktop.padDelete(rec.pad);  // reset while saving
    }
  } catch (e) {
    console.error('Recording', e);
    toast("Couldn't save that sound. Please try again.", 3000);
  }
  if (recording === rec) recording = null;
  showPads();
}

// Throws away a recording in progress (on reset)
function cancelRecording() {
  const rec = recording;
  if (!rec) return;
  rec.cancelled = true;
  clearInterval(rec.ticker);
  clearTimeout(rec.limit);
  if (rec.recorder && rec.recorder.state !== 'inactive') rec.recorder.stop();
  recording = null;
}

async function loadPads() {
  for (let i = 0; i < Pads.COUNT; i++) {
    const bytes = await window.desktop.padLoad(i);
    if (!bytes) continue;
    try {
      pads[i] = await padAudio().decodeAudioData(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));
    } catch (e) {
      console.error('Could not load pad', i + 1, e);
    }
  }
  showPads();
}

let beepCtx = null;
function beep() {
  try {
    beepCtx = beepCtx || new AudioContext();
    const t = beepCtx.currentTime;
    // Soft two-note chime ("ding-dong"): each note fades in quickly and dies away like a bell
    for (const [hz, at] of [[784, 0], [523, 0.28]]) {
      const osc = beepCtx.createOscillator();
      const gain = beepCtx.createGain();
      osc.type = 'sine';
      osc.frequency.value = hz;
      gain.gain.setValueAtTime(0, t + at);
      gain.gain.linearRampToValueAtTime(0.3, t + at + 0.02);
      gain.gain.setTargetAtTime(0, t + at + 0.02, 0.2);
      osc.connect(gain).connect(beepCtx.destination);
      osc.start(t + at);
      osc.stop(t + at + 1);
    }
  } catch { /* no sound device */ }
}

// ---------- Music ----------

function loadSong(path) {
  return new Promise(async (resolve) => {
    const done = (ok) => {
      audio.removeEventListener('loadedmetadata', onOk);
      audio.removeEventListener('error', onErr);
      resolve(ok);
    };
    const onOk = () => done(true);
    const onErr = () => done(false);
    audio.addEventListener('loadedmetadata', onOk);
    audio.addEventListener('error', onErr);
    audio.src = await window.desktop.fileUrl(path);
    audio.load();
  });
}

// Loads one song at a time, so a Start pressed while a song is loading waits for it.
let preparing = Promise.resolve(false);
function preparePlayer(index) {
  preparing = preparing.then(() => loadPlayer(index));
  return preparing;
}

async function loadPlayer(index) {
  ready = false;
  for (let tries = 0; tries < songs.length; tries++) {
    const i = (index + tries) % songs.length;
    if (await loadSong(songs[i])) {
      songIndex = i;
      ready = true;
      audio.loop = songs.length === 1;
      if (mode === 'switch') audio.currentTime = startPosition(i);
      return true;
    }
  }
  audio.removeAttribute('src');
  toast("Couldn't open the music. Please choose it again.", 3500);
  return false;
}

audio.addEventListener('ended', async () => {
  if (songs.length <= 1) return;
  if (mode === 'switch') {
    if (state === 'PLAYING') switchTime();
    return;
  }
  const wasPlaying = state === 'PLAYING';
  if (await preparePlayer((songIndex + 1) % songs.length) && wasPlaying) {
    audio.play().catch(() => {});
  }
  updateUi();
});

async function pickMusic() {
  const picked = await window.desktop.chooseMusic();
  if (picked.length === 0) return;
  localStorage.setItem('songs', JSON.stringify(picked));

  resetGame();
  songs = picked;
  songIndex = 0;
  positions = [];
  await preparePlayer(0);
  updateUi();
  if (mode === 'switch' && startAt === 'catchy') findCatchyParts();
}

function songName(path) {
  return path.split(/[\\/]/).pop() || 'Song';
}

// File name without the extension, e.g. "Happy.mp3" -> "Happy"
function songTitle(path) {
  return songName(path).replace(/\.[^.]+$/, '');
}

// ---------- Start ----------

songs = loadSetting('songs', []);
buildUi();
updateUi();
if (songs.length > 0) preparePlayer(0).then(updateUi);
if (mode === 'switch' && startAt === 'catchy') findCatchyParts();
loadPads();
setInterval(updateProgress, 500);
