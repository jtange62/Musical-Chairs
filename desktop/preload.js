const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('desktop', {
  chooseMusic: () => ipcRenderer.invoke('choose-music'),
  fileUrl: (p) => ipcRenderer.invoke('file-url', p),
  keepAwake: (on) => ipcRenderer.send('keep-awake', on),
});
