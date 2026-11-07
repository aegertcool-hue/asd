package com.brawlbot.ai

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.*
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import kotlin.math.*

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var hud: BotHudView
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private val h = Handler(Looper.getMainLooper())
    private var mp: MediaProjection? = null; private var vd: VirtualDisplay? = null; private var ir: ImageReader? = null
    private var sw = 1920; private var sh = 1080
    private var autoPlay = false; private var isCalibrating = false
    private var calibAngle = 0.0; private var lastAtk = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        hud = BotHudView(this)
        wm.addView(hud, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT))

        status = TextView(this).apply { text = "asd: Готов"; setTextColor(Color.CYAN); textSize = 11f; setPadding(10, 0, 10, 0) }
        panel = LinearLayout(this).apply {
            setBackgroundColor(Color.argb(220, 18, 18, 18)); setPadding(10, 4, 10, 4)
            addView(status)
            addView(Button(context).apply {
                text = "КАЛИБРОВКА 360°"; textSize = 10f
                setOnClickListener { 
                    isCalibrating = true; autoPlay = false
                    status.text = "asd: Калибровка..."
                }
            })
            addView(Button(context).apply {
                text = "АВТО: ВЫКЛ"; textSize = 10f
                setOnClickListener { autoPlay = !autoPlay; text = if (autoPlay) "АВТО: ВКЛ" else "АВТО: ВЫКЛ" }
            })
            addView(Button(context).apply {
                text = "Кнопки"; textSize = 10f
                setOnClickListener { hud.showZones = !hud.showZones; hud.invalidate() }
            })
            addView(Button(context).apply { text = "Х"; textSize = 10f; setTextColor(Color.RED); setOnClickListener { stopSelf() } })
        }
        val pp = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 24 }
        wm.addView(panel, pp)
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val ch = "asd_v4"
        if (Build.VERSION.SDK_INT >= 26) (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(ch, "asd", NotificationManager.IMPORTANCE_LOW))
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ch).setContentTitle("asd AI").setSmallIcon(android.R.drawable.ic_menu_view).build() else Notification.Builder(this).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1, n)
        val rc = i?.getIntExtra("rc", 0) ?: 0; val data = i?.getParcelableExtra<Intent>("data")
        if (rc == Activity.RESULT_OK && data != null) {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            sw = dm.widthPixels; sh = dm.heightPixels
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mp = mpm.getMediaProjection(rc, data)
            ir = ImageReader.newInstance(sw / 4, sh / 4, PixelFormat.RGBA_8888, 2)
            vd = mp?.createVirtualDisplay("AsdV4", sw / 4, sh / 4, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir?.surface, null, h)
            h.post(loop)
        }
        return START_NOT_STICKY
    }

    private val loop = object : Runnable {
        override fun run() { scanAndThink(); h.postDelayed(this, 200L) }
    }

    private fun scanAndThink() {
        val img = ir?.acquireLatestImage() ?: return
        try {
            val pl = img.planes[0]; val buf = pl.buffer; val ps = pl.pixelStride; val rs = pl.rowStride
            val iw = img.width; val ih = img.height; val sx = sw.toFloat() / iw; val sy = sh.toFloat() / ih
            val rawE = mutableListOf<PointF>(); val rawBushes = mutableListOf<PointF>(); val rawWalls = mutableListOf<PointF>(); val whiteLines = mutableListOf<PointF>()

            for (y in (ih * 0.15f).toInt() until (ih * 0.80f).toInt() step 2) {
                for (x in (iw * 0.15f).toInt() until (iw * 0.85f).toInt() step 2) {
                    val o1 = y * rs + x * ps; val o2 = y * rs + (x + 2) * ps; val oUp = (y - 2) * rs + (x + 2) * ps
                    if (o2 + 2 >= buf.limit() || oUp < 0) continue
                    val r1 = buf.get(o1).toInt() and 0xFF; val g1 = buf.get(o1 + 1).toInt() and 0xFF; val b1 = buf.get(o1 + 2).toInt() and 0xFF
                    val r2 = buf.get(o2).toInt() and 0xFF; val g2 = buf.get(o2 + 1).toInt() and 0xFF
                    val ru = buf.get(oUp).toInt() and 0xFF; val gu = buf.get(oUp + 1).toInt() and 0xFF; val bu = buf.get(oUp + 2).toInt() and 0xFF

                    // ПОИСК ВРАГОВ: Красная полоска + Белые цифры ХП над ней
                    if (r1 > 200 && g1 < 60 && b1 < 70 && r2 > 200 && (ru > 200 && gu > 200 && bu > 200)) {
                        rawE.add(PointF(x * sx, y * sy + 70f))
                    } else if (r1 > 235 && g1 > 235 && b1 > 235 && r2 > 235) {
                        whiteLines.add(PointF(x * sx, y * sy))
                    } else if (g1 in 130..200 && r1 < 100 && (x % 8 == 0)) {
                        rawBushes.add(PointF(x * sx, y * sy))
                    } else if (r1 in 90..150 && g1 in 90..150 && b1 in 100..160 && (x % 8 == 0)) {
                        rawWalls.add(PointF(x * sx, y * sy))
                    }
                }
            }
            val me = PointF(sw / 2f, sh / 2f)
            val enemies = cluster(rawE, 220f, 2)
            val bushes = cluster(rawBushes, 180f, 5).take(4)
            val walls = cluster(rawWalls, 160f, 5).take(6)

            // САМОКАЛИБРОВКА РАДИУСА ПО БЕЛОЙ ЛИНИИ
            if (whiteLines.isNotEmpty()) {
                val maxD = whiteLines.map { hypot(it.x - me.x, it.y - me.y) }.maxOrNull() ?: 0f
                if (maxD in 100f..500f) AsdBrain.calibrateRadiusFromAimLine(maxD)
            }

            val mainEnemy = enemies.minByOrNull { hypot(it.x - me.x, it.y - me.y) }
            val aim = if (mainEnemy != null) AsdBrain.analyzeEnemy(me.x, me.y, mainEnemy.x, mainEnemy.y, 70f, walls) else null

            val thought = if (isCalibrating) "КАЛИБРОВКА: вращаюсь, замеряю прицелы..." else 
                          if (aim == null) "Поиск врага... сканирую кусты и стены" else "Цель: ${aim.enemyClass} на ${aim.dist.toInt()}px"
            
            hud.updateHud(me, aim, bushes, walls, thought)

            val hands = BotControlService.instance
            if (hands != null) {
                val mx = sw * 0.16f; val my = sh * 0.74f; val ax = sw * 0.83f; val ay = sh * 0.70f; val sxBtn = sw * 0.70f; val syBtn = sh * 0.64f
                if (isCalibrating) {
                    calibAngle += 0.2
                    hands.holdAndSteerWalk(mx, my, cos(calibAngle).toFloat(), sin(calibAngle).toFloat())
                    if (System.currentTimeMillis() - lastAtk > 2000L) {
                        hands.aimAndReleaseJoystick(ax, ay, cos(calibAngle).toFloat(), sin(calibAngle).toFloat())
                        lastAtk = System.currentTimeMillis()
                    }
                    if (calibAngle >= 6.28f) { isCalibrating = false; status.text = "Калибровка OK" }
                } else if (autoPlay) {
                    if (aim != null) {
                        if (aim.dist <= AsdBrain.myDynamicAtkRadius) {
                            hands.aimAndReleaseJoystick(ax, ay, aim.dirX, aim.dirY, aim.isBehindWall)
                        } else {
                            hands.holdAndSteerWalk(mx, my, -aim.dirX, -aim.dirY)
                        }
                    } else {
                        hands.holdAndSteerWalk(mx, my, cos(calibAngle).toFloat(), sin(calibAngle).toFloat())
                        calibAngle += 0.05
                    }
                }
            }
        } catch (_: Exception) {} finally { img.close() }
    }

    private fun cluster(pts: List<PointF>, rad: Float, min: Int): List<PointF> {
        val gs = mutableListOf<MutableList<PointF>>()
        for (p in pts) {
            val g = gs.find { hypot(it[0].x - p.x, it[0].y - p.y) < rad }
            if (g == null) gs.add(mutableListOf(p)) else g.add(p)
        }
        return gs.filter { it.size >= min }.map { g -> PointF(g.map { it.x }.average().toFloat(), g.map { it.y }.average().toFloat()) }
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null); vd?.release(); ir?.close(); mp?.stop()
        if (::hud.isInitialized) wm.removeView(hud); if (::panel.isInitialized) wm.removeView(panel)
        super.onDestroy()
    }
}
// КОНЕЦ ЧАСТИ 3
