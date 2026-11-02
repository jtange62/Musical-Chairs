# Musical Chairs (Android)

Plays your music, stops it at a random moment (big red STOP screen + beep),
waits a set number of seconds, then starts the music again by itself.

## Settings (saved on the phone)
- Music plays at least / at most — the random stop lands somewhere in this range
- Stop time before restart — the countdown on the red screen
- Restart music automatically — turn off to restart by hand with "Play now"
- Switch songs mode — instead of stopping, jumps to a different song at a random
  moment (each song carries on where it left off). The screen shows the file
  name, so name files after the feeling (`Happy.mp3`, `Sneaky.mp3`...). Needs 2+ songs.
- Stop between songs (Switch songs mode) — beep + STOP screen for this many seconds
  before the next song; 0 fades straight into the next song

Pick one or several songs with "Choose music"; several songs play in order, one song loops.

## Build the APK — option A: GitHub (no installs)
1. Create a new repository on github.com and upload everything in this folder
   (keep the hidden `.github` folder).
2. Open the repo's **Actions** tab → "Build APK" runs automatically (~3 min).
3. Open the finished run → download **MusicalChairs-apk** → unzip → `app-debug.apk`.

## Option B: Android Studio
Open this folder in Android Studio → Build → Build App Bundle(s)/APK(s) → Build APK(s).

## Install on the phone
Copy the APK to the phone, tap it, and allow "Install unknown apps" for your
file manager or browser when Android asks.

# Desktop app (Windows)

The `desktop` folder has the same game as a desktop app (Electron).
Settings and the chosen songs are remembered between runs. Space bar = the big button.

**Switch songs mode** (in Settings): instead of stopping, the music jumps to a
different song at a random moment, carrying on where that song left off.
The screen shows the file name in big letters with its own colour, so name
your files after the feeling (`Happy.mp3`, `Sneaky.mp3`, `Angry.mp3`...).
Choose 2 or more songs. "Stop between songs" sets how long the beep + STOP
screen lasts before the next song (0 = fade straight over).

## Get the .exe from GitHub
Push to GitHub → **Actions** tab → "Build Windows app" runs automatically →
download **MusicalChairs-windows** → unzip → run `MusicalChairs-1.0.0.exe`
(no install needed; Windows may warn because it's unsigned — click "More info" → "Run anyway").

## Run or build it yourself
Needs Node.js. In the `desktop` folder:
- `npm install`, then `npm start` to run it
- `npm run dist` to build `dist/MusicalChairs-1.0.0.exe`
