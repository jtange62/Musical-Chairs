const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('desktop', {
  chooseMusic: () => ipcRenderer.invoke('choose-music'),
  confirmReset: () => ipcRenderer.invoke('confirm-reset'),
  fileUrl: (p) => ipcRenderer.invoke('file-url', p),
  readFile: (p) => ipcRenderer.invoke('read-file', p),
  keepAwake: (on) => ipcRenderer.send('keep-awake', on),
  padLoad: (i) => ipcRenderer.invoke('pad-load', i),
  padSave: (i, bytes) => ipcRenderer.invoke('pad-save', i, bytes),
  padDelete: (i) => ipcRenderer.invoke('pad-delete', i),
  padsClear: () => ipcRenderer.invoke('pads-clear'),
  padMenu: (n) => ipcRenderer.invoke('pad-menu', n),
});
