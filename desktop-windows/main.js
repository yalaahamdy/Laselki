/**
 * لاسِلكي — تطبيق ويندوز (Electron)
 * ===================================
 * هذا التطبيق:
 *  1) يشغّل خدمة الإشارة داخل العملية نفسها (منفذ 3003) — يصبح جهازك "مضيف" الشبكة.
 *  2) يشغّل خادم واجهة الويب (Next.js standalone) كعملية منفصلة (منفذ 3000).
 *  3) يفتح نافذة التطبيق على localhost، ويحقن عنوان الشبكة المحلية
 *     ليظهر رمز QR الصحيح لبقية الأجهزة.
 *
 * الصوت نفسه لا يمر عبر هذا الجهاز إن كان وسيطًا — ينتقل P2P بين الأجهزة.
 */

const { app, BrowserWindow, Menu, dialog } = require('electron')
const path = require('path')
const os = require('os')
const net = require('net')
const { spawn } = require('child_process')

const NEXT_PORT = 3000
const SIGNAL_PORT = 3003

let mainWindow = null
let serverProc = null
let shuttingDown = false

// حماية إضافية: لا تسمح لخطأ غير متوقع بإسقاط التطبيق
process.on('uncaughtException', (err) => {
  console.error('[walkie] uncaught error:', err?.message || err)
})

/* ------------------------- أدوات ------------------------- */

/** أول عنوان IPv4 حقيقي على الشبكة المحلية (لعرضه في QR) */
function lanAddress() {
  const ifs = os.networkInterfaces()
  for (const list of Object.values(ifs)) {
    if (!list) continue
    for (const it of list) {
      if (it.family === 'IPv4' && !it.internal) return it.address
    }
  }
  return '127.0.0.1'
}

function portInUse(port) {
  return new Promise((resolve) => {
    const s = net.connect(port, '127.0.0.1')
    s.once('connect', () => {
      s.destroy()
      resolve(true)
    })
    s.once('error', () => resolve(false))
  })
}

function waitForServer(port, timeoutMs = 30000) {
  const started = Date.now()
  return new Promise((resolve, reject) => {
    const tick = async () => {
      if (await portInUse(port)) return resolve(true)
      if (Date.now() - started > timeoutMs) return reject(new Error('server-timeout'))
      setTimeout(tick, 400)
    }
    tick()
  })
}

/* ------------------------- الخدمات ------------------------- */

function startSignaling() {
  try {
    // الملف مجمّع مسبقًا عبر esbuild (bundle-signaling.mjs)
    require(path.join(__dirname, 'resources', 'signaling.cjs'))
    console.log('[walkie] signaling service started on :' + SIGNAL_PORT)
  } catch (err) {
    if (String(err?.message || '').includes('EADDRINUSE')) {
      console.log('[walkie] signaling already running on :' + SIGNAL_PORT)
    } else {
      console.error('[walkie] signaling failed to start:', err)
    }
  }
}

function startNextServer() {
  const serverJs = path.join(__dirname, 'server', 'server.js')
  if (!require('fs').existsSync(serverJs)) {
    dialog.showErrorBox(
      'ملفات الخادم غير موجودة',
      'لم يتم العثور على مجلد server.\nشغّل build-windows.bat أولًا لبناء التطبيق (راجع README-AR.md).',
    )
    app.quit()
    return
  }

  serverProc = spawn(process.execPath, [serverJs], {
    cwd: path.join(__dirname, 'server'),
    env: {
      ...process.env,
      ELECTRON_RUN_AS_NODE: '1',
      NODE_ENV: 'production',
      PORT: String(NEXT_PORT),
      HOSTNAME: '0.0.0.0',
    },
    stdio: 'inherit',
  })
  serverProc.on('exit', (code) => {
    console.log('[walkie] next server exited', code)
    if (!shuttingDown && mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.executeJavaScript(
        "document.title.includes('لاسِلكي') && location.reload()",
      ).catch(() => {})
    }
  })
}

/* ------------------------- النافذة ------------------------- */

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 440,
    height: 900,
    minWidth: 360,
    minHeight: 620,
    title: 'لاسِلكي',
    backgroundColor: '#171412',
    autoHideMenuBar: true,
    icon: path.join(__dirname, 'resources', 'icon.png'),
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      mediaPlaybackRequiresUserAction: false,
    },
  })

  Menu.setApplicationMenu(null)
  mainWindow.setMenuBarVisibility(false)

  mainWindow.loadURL(`http://localhost:${NEXT_PORT}/`)

  // حقن عنوان الشبكة المحلية لعرضه في لوحة QR الانضمام
  mainWindow.webContents.on('did-finish-load', () => {
    const lan = lanAddress()
    mainWindow?.webContents
      .executeJavaScript(
        `window.__WALKIE_JOIN_URL__ = 'http://${lan}:${NEXT_PORT}/'; undefined`,
      )
      .catch(() => {})
  })

  mainWindow.on('closed', () => {
    mainWindow = null
  })
}

/* ------------------------- دورة الحياة ------------------------- */

if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore()
      mainWindow.focus()
    }
  })

  app.whenReady().then(async () => {
    startSignaling()

    const nextBusy = await portInUse(NEXT_PORT)
    if (!nextBusy) startNextServer()

    try {
      await waitForServer(NEXT_PORT, nextBusy ? 5000 : 30000)
    } catch {
      dialog.showErrorBox(
        'تعذّر تشغيل الخادم المحلي',
        `لم يستجب الخادم على المنفذ ${NEXT_PORT}.\nتأكد أنه غير مشغول بتطبيق آخر ثم أعد المحاولة.`,
      )
      app.quit()
      return
    }

    createWindow()
  })
}

app.on('window-all-closed', () => {
  app.quit()
})

app.on('before-quit', () => {
  shuttingDown = true
  if (serverProc && !serverProc.killed) {
    try {
      serverProc.kill()
    } catch {
      /* تجاهل */
    }
  }
})
