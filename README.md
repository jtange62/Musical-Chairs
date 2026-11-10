# Musical Chairs (Android)

Plays your music, stops it at a random moment (big red STOP screen + beep),
waits a set number of seconds, then starts the music again by itself.

## Classroom version and Lab (experimental) versions

| | Classroom version | Lab version |
|---|---|---|
| Made from | the `main` branch | any other branch (one per experiment) |
| App name | **Musical Chairs** | **Lab: Musical Chairs** (Android), **Musical Chairs Lab** (Windows) |
| Download | [Releases page](https://github.com/jtange62/Musical-Chairs/releases) (permanent, no sign-in) | that branch's run in the **Actions** tab (sign in; kept for 90 days) |

- The two are separate apps: both can be installed on the same tablet or PC, and
  each has its own music list and settings. Trying an experiment never touches
  the classroom app.
- **New experiment:** make a branch (e.g. `feelings-mode`) and push it. GitHub
  builds `MusicalChairsLab-apk` and `MusicalChairsLab-windows` for it.
- **Experiment worked out:** merge the branch into `main`, then publish a release.
- **Publish a release:** bump the version (`versionCode`/`versionName` in
  `app/build.gradle`, `version` in `desktop/package.json`), then push a tag
  named after it, e.g. `git tag v1.2 && git push origin v1.2`. GitHub builds the
  classroom app and puts the `.apk` and `.exe` on the Releases page. Older
  releases stay there, so you can always go back.

## Settings (saved on the phone)
- Music plays at least / at most — the random stop lands somewhere in this range
- Stop time before restart — the countdown on the red screen
- Restart music automatically — turn off to restart by hand with "Play now"
- Switch songs mode — instead of stopping, jumps to a different song at a random
  moment (each song carries on where it left off). The screen shows the file
  name, so name files after the feeling (`Happy.mp3`, `Sneaky.mp3`...). Needs 2+ songs.
- Stop between songs (Switch songs mode) — beep + STOP screen for this many seconds
  before the next song; 0 fades straight into the next song
- Songs start at (Switch songs mode) — **Catchy part** (default): the app finds
  the main tune/chorus of each song (the part that repeats the most) and starts
  there; **Random spot**; or **Where it left off**. If it picks the wrong part,
  play the song, tap **📍 Start this song here** at the right moment, and it
  always starts there (Undo to go back to the automatic pick).

Pick one or several songs with "Choose music"; several songs play in order, one song loops.

## Build the APK — option A: GitHub (no installs)
1. Create a new repository on github.com and upload everything in this folder
   (keep the hidden `.github` folder).
2. Open the repo's **Actions** tab → "Build APK" runs automatically (~3 min).
3. Open the finished run → download **MusicalChairs-apk** → unzip → `app-debug.apk`.
   (Or get the classroom version from the [Releases page](https://github.com/jtange62/Musical-Chairs/releases).)

## Option B: Android Studio
Open this folder in Android Studio → Build → Build App Bundle(s)/APK(s) → Build APK(s).

## Install on the phone
Copy the APK to the phone, tap it, and allow "Install unknown apps" for your
file manager or browser when Android asks.

# Desktop app (Windows)

The `desktop` folder has the same game as a desktop app (Electron).
Settings and the chosen songs are remembered between runs. Space bar = the big button.

**Switch songs mode** (in Settings): instead of stopping, the music jumps to a
different song at a random moment. Each song starts at its catchy part (the
most repeated tune, found automatically when you choose the songs), a random
spot, or where it left off. Fix a wrong pick with **📍 Start this song here**.
The screen shows the file name in big letters with its own colour, so name
your files after the feeling (`Happy.mp3`, `Sneaky.mp3`, `Angry.mp3`...).
Choose 2 or more songs. "Stop between songs" sets how long the beep + STOP
screen lasts before the next song (0 = fade straight over).

## Get the .exe from GitHub
Download it from the [Releases page](https://github.com/jtange62/Musical-Chairs/releases)
and run it (no install needed; Windows may warn because it's unsigned — click
"More info" → "Run anyway"). Builds of each push are also in the **Actions** tab.

## Run or build it yourself
Needs Node.js. In the `desktop` folder:
- `npm install`, then `npm start` to run it
- `npm run dist` to build `dist/MusicalChairs-<version>.exe`
