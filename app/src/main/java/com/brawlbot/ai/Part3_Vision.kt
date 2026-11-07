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
    private var autoPlay = false; private var walkAngle = 0.0; private var strafe = 1f
    private var lastShot = 0L; private var lastGad = 0L; private var lastSup = 0L
    private var prevBlkDist = 0f; private var stuck = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        hud = BotHudView(this)
        wm.addView(hud, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT))

        status = TextView(this).apply { text = AsdBrain.current.name; setTextColor(Color.CYAN); textSize = 11f; setPadding(8, 0, 8, 0) }
        panel = LinearLayout(this).apply {
            setBackgroundColor(Color.argb(220, 18, 18, 18)); setPadding(8, 4, 8, 4)
            addView(status)
            addView(Button(context).apply {
                text = "Боец"; textSize = 10f
                setOnClickListener { status.text = AsdBrain.switchNextBrawler(context).name; hud.invalidate() }
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
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ch).setContentTitle("asd AI v4.0").setSmallIcon(android.R.drawable.ic_menu_view).build() else Notification.Builder(this).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1, n)

        val rc = i?.getIntExtra("rc", 0) ?: 0
        val data = i?.getParcelableExtra<Intent>("data")
        if (rc == Activity.RESULT_OK && data != null) {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            sw = dm.widthPixels; sh = dm.heightPixels
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mp = mpm.getMediaProjection(rc, data)
            mp?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, h)
            ir = ImageReader.newInstance(sw / 4, sh / 4, PixelFormat.RGBA_8888, 2)
            vd = mp?.createVirtualDisplay("AsdV4", sw / 4, sh / 4, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir?.surface, null, h)
            h.post(loop)
        }
        return START_NOT_STICKY
    }

    private val loop = object : Runnable {
        override fun run() { scanAndAct(); h.postDelayed(this, 200L) }
    }

    private fun scanAndAct() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        sw = dm.widthPixels; sh = dm.heightPixels
        val img = ir?.acquireLatestImage() ?: return
        try {
            val pl = img.planes[0]; val buf = pl.buffer; val ps = pl.pixelStride; val rs = pl.rowStride
            val iw = img.width; val ih = img.height; val sx = sw.toFloat() / iw; val sy = sh.toFloat() / ih
            val rawE = mutableListOf<PointF>(); val rawBush = mutableListOf<PointF>(); val rawBlk = mutableListOf<PointF>()

            for (y in (ih * 0.16f).toInt() until (ih * 0.78f).toInt() step 2) {
                for (x in (iw * 0.16f).toInt() until (iw * 0.84f).toInt() step 2) {
                    if (x > iw * 0.56f && y > ih * 0.50f) continue
                    if (x < iw * 0.34f && y > ih * 0.50f) continue
                    val o1 = y * rs + x * ps; val o2 = y * rs + (x + 2) * ps; val o3 = y * rs + (x + 4) * ps; val oUp = (y - 2) * rs + (x + 2) * ps
                    if (o3 + 2 >= buf.limit() || oUp < 0) continue

                    val r1 = buf.get(o1).toInt() and 0xFF; val g1 = buf.get(o1 + 1).toInt() and 0xFF; val b1 = buf.get(o1 + 2).toInt() and 0xFF
                    val r2 = buf.get(o2).toInt() and 0xFF; val g2 = buf.get(o2 + 1).toInt() and 0xFF
                    val r3 = buf.get(o3).toInt() and 0xFF; val g3 = buf.get(o3 + 1).toInt() and 0xFF
                    val ru = buf.get(oUp).toInt() and 0xFF; val gu = buf.get(oUp + 1).toInt() and 0xFF; val bu = buf.get(oUp + 2).toInt() and 0xFF

                    if (r1 > 212 && g1 < 50 && b1 < 60 && r2 > 212 && g2 < 50 && r3 > 212 && g3 < 50 && ru < 60 && gu < 60 && bu < 60) {
                        rawE.add(PointF(x * sx, y * sy + 70f))
                    } else if (g1 in 140..205 && r1 in 35..105 && b1 in 25..85 && (x % 6 == 0)) {
                        rawBush.add(PointF(x * sx, y * sy))
                    } else if (r1 in 95..140 && g1 in 90..135 && b1 in 100..150 && ru < 50 && (x % 6 == 0)) {
                        rawBlk.add(PointF(x * sx, y * sy))
                    }
                }
            }

            val me = PointF(sw / 2f, sh / 2f)
            val enemies = cluster(rawE, 230f, 3)
            val bushes = cluster(rawBush, 180f, 5).take(4)
            val blocks = cluster(rawBlk, 160f, 5).take(4)

            val closest = enemies.minByOrNull { hypot(it.x - me.x, it.y - me.y) }
            val aim = if (closest != null) AsdBrain.calculateAim(me.x, me.y, closest.x, closest.y) else { AsdBrain.resetEnemyTracking(); null }
            val b = AsdBrain.current

            val thought = when {
                aim == null -> "Чисто: зажимаю ход, обхожу блоки (${blocks.size}), ищу кусты"
                aim.dist <= b.atkRadius -> "ЦЕЛЬ В РАДИУСЕ (${aim.dist.toInt()}px): Целюсь свайпом и отхожу!"
                aim.dist <= b.supRadius -> "Дистанция ${aim.dist.toInt()}px: Целюсь Гаджетом/Ультой, держу рендж!"
                else -> "Враг на ${aim.dist.toInt()}px: зажимаю джойстик, мансую к цели"
            }
            hud.updateHud(me, aim, bushes, blocks, thought)

            val hands = BotControlService.instance
            val now = System.currentTimeMillis()
            if (autoPlay && hands != null) {
                val mx = sw * 0.16f; val my = sh * 0.74f; val ax = sw * 0.83f; val ay = sh * 0.70f
                val sxBtn = sw * 0.70f; val syBtn = sh * 0.64f; val gx = sw * 0.76f; val gy = sh * 0.87f; val hx = sw * 0.61f; val hy = sh * 0.82f

                val nearBlk = blocks.minOfOrNull { hypot(it.x - me.x, it.y - me.y) } ?: 999f
                if (abs(nearBlk - prevBlkDist) < 5f && nearBlk < 220f) stuck++ else stuck = 0
                prevBlkDist = nearBlk
                if (stuck >= 3) { walkAngle += 1.57; strafe = -strafe; stuck = 0; AsdBrain.recordAction(this, false, false, true) }

                if (aim != null && aim.dist <= b.atkRadius * 0.85f && now - lastSup > 4500L) {
                    lastSup = now; hands.quickTap(hx, hy); hands.aimAndReleaseJoystick(sxBtn, syBtn, aim.dirX, aim.dirY, 140f)
                } else if (aim != null && aim.dist in (b.atkRadius * 0.75f)..(b.supRadius * 1.1f) && now - lastGad > 7000L) {
                    lastGad = now; hands.useAndAimGadget(gx, gy, ax, ay, aim.dirX, aim.dirY, b.gadgetAimed)
                    AsdBrain.recordAction(this, false, true, false)
                } else if (aim != null && aim.dist <= b.atkRadius + 40f && now - lastShot > 1150L) {
                    lastShot = now; hands.aimAndReleaseJoystick(ax, ay, aim.dirX, aim.dirY, 145f)
                    AsdBrain.recordAction(this, true, false, false)
                } else {
                    val dx: Float; val dy: Float
                    if (aim != null && aim.dist < b.atkRadius * 0.92f) {
                        dx = -aim.dirX + (-aim.dirY * 0.35f * strafe); dy = -aim.dirY + (aim.dirX * 0.35f * strafe)
                    } else if (aim != null && aim.dist > b.atkRadius * 1.15f) {
                        dx = aim.dirX + (-aim.dirY * 0.4f * strafe); dy = aim.dirY + (aim.dirX * 0.4f * strafe)
                    } else {
                        dx = cos(walkAngle).toFloat(); dy = sin(walkAngle).toFloat()
                    }
                    hands.holdAndDragWalkJoystick(mx, my, dx, dy)
                }
            }
        } catch (_: Exception) {} finally { img.close() }
    }

    private fun cluster(pts: List<PointF>, rad: Float, minPts: Int): List<PointF> {
        val groups = mutableListOf<MutableList<PointF>>()
        for (p in pts) {
            val g = groups.find { hypot(it[0].x - p.x, it[0].y - p.y) < rad }
            if (g == null) groups.add(mutableListOf(p)) else g.add(p)
        }
        return groups.filter { it.size >= minPts }.map { g -> PointF(g.map { it.x }.average().toFloat(), g.map { it.y }.average().toFloat()) }
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null); vd?.release(); ir?.close(); mp?.stop()
        if (::hud.isInitialized) wm.removeView(hud)
        if (::panel.isInitialized) wm.removeView(panel)
        super.onDestroy()
    }
}
// КОНЕЦ ЧАСТИ 3
