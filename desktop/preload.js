const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('desktop', {
  chooseMusic: () => ipcRenderer.invoke('choose-music'),
  fileUrl: (p) => ipcRenderer.invoke('file-url', p),
  readFile: (p) => ipcRenderer.invoke('read-file', p),
  keepAwake: (on) => ipcRenderer.send('keep-awake', on),
});
