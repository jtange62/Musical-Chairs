const { app, BrowserWindow, dialog, ipcMain, powerSaveBlocker } = require('electron');
const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');

let win;
let blockerId = null;

function createWindow() {
  win = new BrowserWindow({
    width: 480,
    height: 820,
    minWidth: 360,
    minHeight: 600,
    backgroundColor: '#37474F',
    autoHideMenuBar: true,
    icon: path.join(__dirname, 'assets', 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      // Keep the stop timer and music going while the window is minimized.
      backgroundThrottling: false,
    },
  });
  // The title bar shows the app's name, so a Lab build says "Musical Chairs Lab"
  win.on('page-title-updated', (e) => e.preventDefault());
  win.setTitle(app.getName());
  win.loadFile(path.join(__dirname, 'index.html'));
}

ipcMain.handle('choose-music', async () => {
  const result = await dialog.showOpenDialog(win, {
    title: 'Choose music',
    properties: ['openFile', 'multiSelections'],
    filters: [
      { name: 'Audio', extensions: ['mp3', 'm4a', 'aac', 'wav', 'ogg', 'oga', 'opus', 'flac', 'webm'] },
      { name: 'All files', extensions: ['*'] },
    ],
  });
  return result.canceled ? [] : result.filePaths;
});

ipcMain.handle('confirm-reset', async () => {
  const result = await dialog.showMessageBox(win, {
    type: 'warning',
    title: 'Reset',
    message: 'Reset everything?',
    detail: 'This clears your chosen music, settings and saved song spots. Your music files are not deleted.',
    buttons: ['Reset', 'Cancel'],
    defaultId: 1,
    cancelId: 1,
  });
  return result.response === 0;
});

ipcMain.handle('file-url', (_e, p) => pathToFileURL(p).href);

ipcMain.handle('read-file', (_e, p) => fs.promises.readFile(p));

// Stops the screen and computer from sleeping while a game is running.
ipcMain.on('keep-awake', (_e, on) => {
  if (on && blockerId === null) {
    blockerId = powerSaveBlocker.start('prevent-display-sleep');
  } else if (!on && blockerId !== null) {
    powerSaveBlocker.stop(blockerId);
    blockerId = null;
  }
});

app.whenReady().then(createWindow);
app.on('window-all-closed', () => app.quit());
