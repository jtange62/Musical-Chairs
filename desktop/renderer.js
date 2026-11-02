const COLOR_IDLE = '#37474F';
const COLOR_PLAY = '#2E7D32';
const COLOR_STOP = '#C62828';
const COLOR_PAUSE = '#F9A825';

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
let minPlay = loadSetting('minPlay', 10);
let maxPlay = loadSetting('maxPlay', 30);
let restTime = loadSetting('restTime', 10);
let autoRestart = loadSetting('autoRestart', true);

function saveSettings() {
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
  return row;
}

function buildUi() {
  const steppers = $('steppers');
  steppers.append(
    stepper('Music plays at least', () => minPlay, (v) => { minPlay = v; }, 3, 300, 1),
    stepper('Music plays at most', () => maxPlay, (v) => { maxPlay = v; }, 3, 300, 1),
    stepper('Stop time before restart', () => restTime, (v) => { restTime = v; }, 1, 120, 1),
  );

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

  mainButton.onclick = onMainButton;
  $('reset').onclick = resetGame;
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
    if (e.code === 'Space' && !e.repeat && e.target.tagName !== 'INPUT') {
      e.preventDefault();
      onMainButton();
    }
  });
}

function updateUi() {
  window.desktop.keepAwake(state === 'PLAYING' || state === 'STOPPED');
  let bg;
  switch (state) {
    case 'PLAYING':
      bg = COLOR_PLAY;
      bigText.textContent = '🎵 Walk! 🎵';
      subText.textContent = '';
      mainButton.textContent = 'Pause';
      break;
    case 'STOPPED':
      bg = COLOR_STOP;
      bigText.textContent = 'STOP!\n🪑';
      subText.textContent = autoRestart ? 'Music in ' + countdown + '…' : 'Press “Play now” when ready';
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
      subText.textContent = songs.length === 0 ? 'Choose music to begin' : '';
      mainButton.textContent = 'Start';
  }
  document.body.style.backgroundColor = bg;
  songText.textContent = songs.length === 0 ? 'No music chosen'
    : '♪ ' + songName(songs[songIndex]) + (songs.length > 1
      ? '  (' + (songIndex + 1) + '/' + songs.length + ')' : '');
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
  clearTimeout(tickTimer);
  clearTimeout(stopTimer);
  if (!ready && !(await preparePlayer(songIndex))) return;
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
  stopTimer = setTimeout(doStop, ms);
}

function doStop() {
  audio.pause();
  beep();
  state = 'STOPPED';
  countdown = restTime;
  updateUi();
  if (autoRestart) tickTimer = setTimeout(tick, 1000);
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
    if (autoRestart) tickTimer = setTimeout(tick, 1000);
  } else {
    startPlaying();
  }
}

function resetGame() {
  clearTimeout(stopTimer);
  clearTimeout(tickTimer);
  audio.pause();
  if (ready) audio.currentTime = 0;
  state = 'IDLE';
  updateUi();
}

let beepCtx = null;
function beep() {
  try {
    beepCtx = beepCtx || new AudioContext();
    const t = beepCtx.currentTime;
    const gain = beepCtx.createGain();
    gain.gain.value = 0.4;
    gain.connect(beepCtx.destination);
    // Two quick alternating tones, about half a second in all
    for (let i = 0; i < 4; i++) {
      const osc = beepCtx.createOscillator();
      osc.type = 'square';
      osc.frequency.value = i % 2 ? 1150 : 770;
      osc.connect(gain);
      osc.start(t + i * 0.125);
      osc.stop(t + (i + 1) * 0.125);
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

async function preparePlayer(index) {
  ready = false;
  for (let tries = 0; tries < songs.length; tries++) {
    const i = (index + tries) % songs.length;
    if (await loadSong(songs[i])) {
      songIndex = i;
      ready = true;
      audio.loop = songs.length === 1;
      return true;
    }
  }
  audio.removeAttribute('src');
  toast("Couldn't open the music. Please choose it again.", 3500);
  return false;
}

audio.addEventListener('ended', async () => {
  if (songs.length <= 1) return;
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
  await preparePlayer(0);
  updateUi();
}

function songName(path) {
  return path.split(/[\\/]/).pop() || 'Song';
}

// ---------- Start ----------

songs = loadSetting('songs', []);
buildUi();
updateUi();
if (songs.length > 0) preparePlayer(0).then(updateUi);
setInterval(updateProgress, 500);
