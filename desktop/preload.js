const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('desktop', {
  chooseMusic: () => ipcRenderer.invoke('choose-music'),
  choosePictureFolder: () => ipcRenderer.invoke('choose-picture-folder'),
  listPictures: (folder) => ipcRenderer.invoke('list-pictures', folder),
  confirmReset: () => ipcRenderer.invoke('confirm-reset'),
  fileUrl: (p) => ipcRenderer.invoke('file-url', p),
  readFile: (p) => ipcRenderer.invoke('read-file', p),
  keepAwake: (on) => ipcRenderer.send('keep-awake', on),
});
