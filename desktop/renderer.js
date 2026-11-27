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
let minPlay, maxPlay, restTime, autoRestart, mode, switchPause, startAt, highlights, sayWords, words, voiceName, stopSound, mySounds;
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
  // At each stop, a voice says a random word from the list
  sayWords = loadSetting('sayWords', true);
  words = loadSetting('words', 'happy, sad, angry, scared, surprised, silly, sleepy');
  voiceName = loadSetting('voiceName', null);  // null = an English voice picked automatically
  // A key of STOP_SOUNDS, 'file:' + one of mySounds, 'random-mine' or 'random-all'
  stopSound = loadSetting('stopSound', 'chime');
  mySounds = loadSetting('mySounds', []);  // the user's own sound files
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
  localStorage.setItem('sayWords', JSON.stringify(sayWords));
  localStorage.setItem('words', JSON.stringify(words));
  localStorage.setItem('voiceName', JSON.stringify(voiceName));
  localStorage.setItem('stopSound', JSON.stringify(stopSound));
  localStorage.setItem('mySounds', JSON.stringify(mySounds));
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

  const say = $('say');
  say.checked = sayWords;
  say.onchange = () => { sayWords = say.checked; saveSettings(); showWords(); };
  $('words').value = words;
  $('words').oninput = () => { words = $('words').value; saveSettings(); };
  $('voice').onchange = () => { voiceName = $('voice').value || null; saveSettings(); tryVoice(); };
  $('tryVoice').onclick = tryVoice;

  showSounds();
  $('sound').onchange = () => { stopSound = $('sound').value; saveSettings(); trySound(); };
  $('trySound').onclick = trySound;
  $('addSounds').onclick = addSounds;
  $('clearSounds').onclick = clearSounds;
  // Windows lists its voices a moment after the app opens
  speechSynthesis.onvoiceschanged = showVoices;
  showVoices();
  showWords();

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
    if (e.code === 'Space' && !e.repeat && e.target.tagName !== 'INPUT' && e.target.tagName !== 'SELECT') {
      e.preventDefault();
      onMainButton();
    }
  });
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
  const mono = new Float32Array(buf.length);
  for (let c = 0; c < buf.numberOfChannels; c++) {
    const data = buf.getChannelData(c);
    for (let i = 0; i < data.length; i++) mono[i] += data[i] / buf.numberOfChannels;
  }
  return Highlights.find(mono, buf.sampleRate).starts;
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

// ---------- Word at the stop ----------

let stopWord = null;   // the word said at this stop
let soundId = 0;      // changes when the stop sound and word are cut short

function showWords() {
  $('wordsRow').hidden = !sayWords;
}

function wordList() {
  return words.split(',').map((w) => w.trim()).filter((w) => w);
}

// A random word from the list, not the same one twice in a row
function pickWord() {
  const list = wordList();
  if (list.length === 0) return null;
  const others = list.length > 1 ? list.filter((w) => w !== stopWord) : list;
  return others[Math.floor(Math.random() * others.length)];
}

// The chosen voice, or else an English one, even if Windows is set to another language
function chosenVoice() {
  const all = speechSynthesis.getVoices();
  const chosen = all.find((v) => v.name === voiceName);
  if (chosen) return chosen;
  const voices = all.filter((v) => v.lang.startsWith('en'));
  return voices.find((v) => v.default) || voices.find((v) => /zira/i.test(v.name)) || voices[0] || null;
}

// Fills the Voice menu: English voices first, then the others
function showVoices() {
  const menu = $('voice');
  const voices = speechSynthesis.getVoices().slice()
    .sort((a, b) => b.lang.startsWith('en') - a.lang.startsWith('en'));
  menu.replaceChildren(new Option('Automatic (English)', ''));
  for (const v of voices) {
    // "Microsoft Zira - English (United States)" -> "Zira - English (United States)"
    menu.add(new Option(v.name.replace(/^Microsoft /, ''), v.name));
  }
  menu.value = voices.some((v) => v.name === voiceName) ? voiceName : '';
}

function tryVoice() {
  stopSpeaking();
  sayWord(pickWord() || 'happy');
}

function sayWord(word) {
  try {
    const u = new SpeechSynthesisUtterance(word);
    u.voice = chosenVoice();
    if (u.voice) u.lang = u.voice.lang;
    u.rate = 0.85;  // a little slow, for young children
    speechSynthesis.speak(u);
  } catch { /* no voice available */ }
}

function stopSpeaking() {
  soundId++;
  sfx.pause();
  try { speechSynthesis.cancel(); } catch { /* no voice available */ }
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
      bigText.textContent = 'STOP!\n' + (stopWord ? stopWord[0].toUpperCase() + stopWord.slice(1) : '🪑');
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
  stopSpeaking();
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
  const id = ++soundId;
  stopWord = sayWords ? pickWord() : null;
  // Say the word once the stop sound has played
  playStopSound().then(() => {
    if (stopWord && id === soundId) sayWord(stopWord);
  });
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
  stopSpeaking();
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
  $('say').checked = sayWords;
  $('words').value = words;
  showSounds();
  showVoices();
  showWords();
  showMode();
  updateUi();
  toast('Reset to defaults');
}

// ---------- Stop sounds ----------

let soundCtx = null;

// One bell-like note: fades in quickly, then dies away over `decay` seconds
function note(hz, at, { vol = 0.3, decay = 0.2, type = 'sine', len = 1 } = {}) {
  const t = soundCtx.currentTime + at;
  const osc = soundCtx.createOscillator();
  const gain = soundCtx.createGain();
  osc.type = type;
  osc.frequency.value = hz;
  gain.gain.setValueAtTime(0, t);
  gain.gain.linearRampToValueAtTime(vol, t + 0.01);
  gain.gain.setTargetAtTime(0, t + 0.01, decay / 3);
  osc.connect(gain).connect(soundCtx.destination);
  osc.start(t);
  osc.stop(t + len);
}

// A short burst of high hiss, like bells rattling
function rattle(at, vol) {
  const len = 0.12;
  const buf = soundCtx.createBuffer(1, Math.floor(soundCtx.sampleRate * len), soundCtx.sampleRate);
  const data = buf.getChannelData(0);
  for (let i = 0; i < data.length; i++) data[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / data.length, 3);
  const src = soundCtx.createBufferSource();
  src.buffer = buf;
  const filter = soundCtx.createBiquadFilter();
  filter.type = 'highpass';
  filter.frequency.value = 6000;
  const gain = soundCtx.createGain();
  gain.gain.value = vol;
  src.connect(filter).connect(gain).connect(soundCtx.destination);
  src.start(soundCtx.currentTime + at);
}

// Each sound plays itself and returns how long it lasts in seconds, so the word waits for it
const STOP_SOUNDS = {
  chime: {
    name: 'Chime',
    play() {
      // Soft two-note "ding-dong"
      note(784, 0, { decay: 0.6 });
      note(523, 0.28, { decay: 0.6 });
      return 0.9;
    },
  },
  sleigh: {
    name: '🛷 Sleigh bells',
    play() {
      // A few shakes, each a cluster of tiny bells
      for (const at of [0, 0.3, 0.6, 0.75, 0.9]) {
        rattle(at, 0.25);
        for (let k = 0; k < 6; k++) {
          note(3000 + Math.random() * 4000, at + Math.random() * 0.05, { vol: 0.06, decay: 0.25, type: 'triangle', len: 0.4 });
        }
      }
      return 1.3;
    },
  },
  bell: {
    name: '🔔 Big bell',
    play() {
      // A church bell's mix of tones; the low ones ring longest
      for (const [ratio, vol, decay] of [[0.5, 0.11, 3], [1, 0.14, 2.2], [1.19, 0.07, 1.6], [1.5, 0.05, 1.3], [2, 0.07, 1.1], [2.74, 0.03, 0.8], [3.76, 0.02, 0.6]]) {
        note(196 * ratio, 0, { vol, decay, len: 3.5 });
      }
      return 1.4;
    },
  },
  jingle: {
    name: '🎶 Jingle Bells tune',
    play() {
      // "Jingle bells, jingle bells, jingle all the way" on bells
      const E = 1318.5, G = 1568, C = 1046.5, D = 1174.7;
      const beat = 0.2;
      let at = 0;
      for (const [hz, beats] of [[E, 1], [E, 1], [E, 2], [E, 1], [E, 1], [E, 2], [E, 1], [G, 1], [C, 1.5], [D, 0.5], [E, 4]]) {
        note(hz, at, { vol: 0.22, decay: 0.5 });
        note(hz * 2, at, { vol: 0.04, decay: 0.2 });
        at += beats * beat;
      }
      return at - 3 * beat;
    },
  },
  sparkle: {
    name: '✨ Magic sparkle',
    play() {
      // Quick rising twinkles
      for (let k = 0; k < 14; k++) {
        note(1500 + k * 180 + Math.random() * 300, k * 0.05, { vol: 0.1, decay: 0.4, len: 0.6 });
      }
      return 1.1;
    },
  },
};

const sfx = new Audio();  // plays the user's own sound files
let lastSound = null;     // the sound played at the last stop

// Fills the Stop sound menu: the built-in sounds, the user's own, then the random choices
function showSounds() {
  const menu = $('sound');
  menu.replaceChildren();
  for (const [key, s] of Object.entries(STOP_SOUNDS)) menu.add(new Option(s.name, key));
  for (const p of mySounds) menu.add(new Option('🎵 ' + songTitle(p), 'file:' + p));
  if (mySounds.length) menu.add(new Option('🎲 Random: my sounds', 'random-mine'));
  menu.add(new Option('🎲 Random: all sounds', 'random-all'));
  // A sound that is no longer there falls back to the chime
  menu.value = [...menu.options].some((o) => o.value === stopSound) ? stopSound : 'chime';
  $('mySoundsName').textContent = mySounds.length === 0 ? 'My sounds: none'
    : 'My sounds: ' + mySounds.length;
  $('clearSounds').hidden = mySounds.length === 0;
}

async function addSounds() {
  const picked = await window.desktop.chooseSounds();
  if (picked.length === 0) return;
  mySounds = [...new Set([...mySounds, ...picked])];
  // Start with the new sound, or with all of them at random if several were added
  stopSound = picked.length === 1 ? 'file:' + picked[0] : 'random-mine';
  saveSettings();
  showSounds();
}

function clearSounds() {
  mySounds = [];
  if (stopSound.startsWith('file:') || stopSound === 'random-mine') stopSound = 'chime';
  saveSettings();
  showSounds();
}

// The sound for this stop: the chosen one, or a random one (not the same twice in a row)
function pickSound() {
  const mine = mySounds.map((p) => 'file:' + p);
  const choice = $('sound').value;
  const pool = choice === 'random-mine' ? mine
    : choice === 'random-all' ? [...Object.keys(STOP_SOUNDS), ...mine] : [choice];
  const others = pool.length > 1 ? pool.filter((k) => k !== lastSound) : pool;
  return others[Math.floor(Math.random() * others.length)] || 'chime';
}

function playFile(path) {
  return new Promise(async (resolve) => {
    const done = () => {
      clearTimeout(limit);
      // Leave the handlers alone if a newer sound has taken over the player
      if (sfx.onended === done) sfx.onended = sfx.onerror = null;
      resolve();
    };
    // A long sound file doesn't hold up the word for more than 10 seconds
    const limit = setTimeout(done, 10000);
    sfx.onended = sfx.onerror = done;
    sfx.src = await window.desktop.fileUrl(path);
    sfx.play().catch(done);
  });
}

// Plays the stop sound; resolves once it has finished, so the word can be said after it
function playStopSound() {
  const key = pickSound();
  lastSound = key;
  if (key.startsWith('file:')) return playFile(key.slice(5));
  let seconds = 0;
  try {
    soundCtx = soundCtx || new AudioContext();
    seconds = STOP_SOUNDS[key].play();
  } catch { /* no sound device */ }
  return new Promise((resolve) => setTimeout(resolve, seconds * 1000));
}

function trySound() {
  stopSpeaking();
  playStopSound();
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
setInterval(updateProgress, 500);
