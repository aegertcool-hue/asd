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
    private var autoPlay = false; private var walkAng = 0.0; private var strafe = 1f
    private var lastAtk = 0L; private var lastGad = 0L; private var lastSup = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        hud = BotHudView(this)
        wm.addView(hud, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT))

        status = TextView(this).apply { text = "ХП: 100%"; setTextColor(Color.CYAN); textSize = 11f; setPadding(8, 0, 8, 0) }
        panel = LinearLayout(this).apply {
            setBackgroundColor(Color.argb(220, 18, 18, 18)); setPadding(8, 4, 8, 4)
            addView(status)
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
        override fun run() { scanFrame(); h.postDelayed(this, 190L) }
    }

    private fun scanFrame() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        sw = dm.widthPixels; sh = dm.heightPixels
        val img = ir?.acquireLatestImage() ?: return
        try {
            val pl = img.planes[0]; val buf = pl.buffer; val ps = pl.pixelStride; val rs = pl.rowStride
            val iw = img.width; val ih = img.height; val sx = sw.toFloat() / iw; val sy = sh.toFloat() / ih

            val myGreenPts = mutableListOf<PointF>(); val rawE = mutableListOf<PointF>()
            val rawBushes = mutableListOf<PointF>(); val rawWalls = mutableListOf<PointF>()
            val aimLinePts = mutableListOf<PointF>()

            for (y in (ih * 0.15f).toInt() until (ih * 0.82f).toInt() step 2) {
                for (x in (iw * 0.15f).toInt() until (iw * 0.85f).toInt() step 2) {
                    if (x > iw * 0.56f && y > ih * 0.50f) continue
                    if (x < iw * 0.34f && y > ih * 0.50f) continue
                    val o1 = y * rs + x * ps; val o2 = y * rs + (x + 2) * ps; val oUp = (y - 2) * rs + x * ps
                    if (o2 + 2 >= buf.limit() || oUp < 0) continue

                    val r = buf.get(o1).toInt() and 0xFF; val g = buf.get(o1 + 1).toInt() and 0xFF; val b = buf.get(o1 + 2).toInt() and 0xFF
                    val r2 = buf.get(o2).toInt() and 0xFF; val g2 = buf.get(o2 + 1).toInt() and 0xFF
                    val ru = buf.get(oUp).toInt() and 0xFF; val gu = buf.get(oUp + 1).toInt() and 0xFF

                    // 1. Зеленая полоска ХП НАШЕГО ПЕРСОНАЖА (чтобы зеленый кубик реально ходил за ним!)
                    if (g > 205 && r < 85 && b < 90 && g2 > 205 && ru < 65) {
                        myGreenPts.add(PointF(x * sx, y * sy))
                    }
                    // 2. Красная полоска ХП ВРАГА
                    else if (r > 212 && g < 52 && b < 62 && r2 > 212 && g2 < 52 && ru < 65 && gu < 65) {
                        rawE.add(PointF(x * sx, y * sy + 68f))
                    }
                    // 3. Белая линия ПРИЦЕЛА при наведении (для самокалибровки радиуса атаки!)
                    else if (r > 238 && g > 238 && b > 238 && r2 > 238) {
                        aimLinePts.add(PointF(x * sx, y * sy))
                    }
                    // 4. Кусты и Стены
                    else if (g in 140..200 && r in 35..100 && b in 20..80 && (x % 6 == 0)) {
                        rawBushes.add(PointF(x * sx, y * sy))
                    } else if (r in 95..142 && g in 90..138 && b in 100..152 && ru < 50 && (x % 6 == 0)) {
                        rawWalls.add(PointF(x * sx, y * sy))
                    }
                }
            }

            // Координаты нашего бойца (следует за зеленой полоской ХП!) и расчет % ХП для Хила
            val mePos = if (myGreenPts.isNotEmpty()) {
                val minX = myGreenPts.minOf { it.x }; val maxX = myGreenPts.maxOf { it.x }
                val barW = (maxX - minX).coerceIn(12f, 90f)
                AsdBrain.myHpPercent = ((barW / 78f) * 100f).toInt().coerceIn(20, 100)
                PointF(myGreenPts.map { it.x }.average().toFloat(), myGreenPts.map { it.y }.average().toFloat() + 68f)
            } else PointF(sw / 2f, sh / 2f)

            // Самокалибровка радиуса круга по белой линии прицела
            if (aimLinePts.size >= 4) {
                val maxAimDist = aimLinePts.map { hypot(it.x - mePos.x, it.y - mePos.y) }.filter { it in 115f..470f }.maxOrNull()
                if (maxAimDist != null) AsdBrain.calibrateRadiusFromAimLine(maxAimDist)
            }

            val bushes = cluster(rawBushes, 180f, 4).take(4)
            val walls = cluster(rawWalls, 160f, 4).take(5)
            val enemyGroups = groupEnemies(rawE, 210f)
            val bestGroup = enemyGroups.minByOrNull { grp -> hypot(grp.first.x - mePos.x, grp.first.y - mePos.y) }

            val enemyInfo = if (bestGroup != null) {
                AsdBrain.analyzeEnemy(mePos.x, mePos.y, bestGroup.first.x, bestGroup.first.y, bestGroup.second, walls)
            } else { AsdBrain.resetEnemy(); null }

            status.text = "ХП:${AsdBrain.myHpPercent}% | Рендж:${AsdBrain.myDynamicAtkRadius.toInt()}"

            val thought = when {
                AsdBrain.needHealing -> "МАЛО ХП (${AsdBrain.myHpPercent}%)! Не стреляю -> ХИЛЮСЬ и ухожу в кусты!"
                enemyInfo == null -> "Чисто: зажимаю ход, сканирую кусты (${bushes.size}) и стены (${walls.size})"
                enemyInfo.isBehindWall -> "Враг [${enemyInfo.enemyClass}] ЗА СТЕНОЙ! Отменяю ульту/атаку в центр, обхожу!"
                enemyInfo.dist <= AsdBrain.myDynamicAtkRadius -> "Враг [${enemyInfo.enemyClass}] в рендже! Навожу прицел и стреляю!"
                else -> "Перед нами ${enemyInfo.enemyClass} (${enemyInfo.dist.toInt()}px): навожусь и мансую"
            }
            hud.updateHud(mePos, enemyInfo, bushes, walls, thought)

            val hands = BotControlService.instance
            val now = System.currentTimeMillis()
            if (autoPlay && hands != null) {
                val mx = sw * 0.16f; val my = sh * 0.74f; val ax = sw * 0.83f; val ay = sh * 0.70f
                val sxBtn = sw * 0.70f; val syBtn = sh * 0.64f; val gx = sw * 0.76f; val gy = sh * 0.87f

                if (AsdBrain.needHealing) {
                    // РЕЖИМ ХИЛА: не стреляем, чтобы не сбить авто-хил, и уходим к ближайшему кусту или от врага
                    AsdBrain.healedTimesCount++
                    val bx = bushes.firstOrNull()?.let { (it.x - mePos.x) / 300f } ?: -(enemyInfo?.dirX ?: cos(walkAng).toFloat())
                    val by = bushes.firstOrNull()?.let { (it.y - mePos.y) / 300f } ?: -(enemyInfo?.dirY ?: sin(walkAng).toFloat())
                    hands.holdAndSteerWalk(mx, my, bx, by)
                } else if (enemyInfo != null) {
                    // Если враг за стеной или вышел из радиуса во время наведения — ОТМЕНЯЕМ ульту/атаку возвратом пальца в центр!
                    val mustCancel = enemyInfo.isBehindWall || enemyInfo.dist > AsdBrain.myDynamicAtkRadius + 60f
                    if (now - lastSup > 5000L && enemyInfo.dist <= AsdBrain.myDynamicSupRadius) {
                        lastSup = now
                        if (mustCancel) AsdBrain.canceledSupersCount++
                        hands.aimOrCancelJoystick(sxBtn, syBtn, enemyInfo.dirX, enemyInfo.dirY, mustCancel)
                        AsdBrain.saveBrainState(this)
                    } else if (now - lastGad > 6500L && enemyInfo.dist <= AsdBrain.myDynamicAtkRadius + 90f) {
                        lastGad = now
                        hands.activateAndAimGadget(gx, gy, ax, ay, enemyInfo.dirX, enemyInfo.dirY, mustCancel)
                    } else if (now - lastAtk > 1250L && enemyInfo.dist <= AsdBrain.myDynamicAtkRadius + 50f) {
                        lastAtk = now
                        hands.aimOrCancelJoystick(ax, ay, enemyInfo.dirX, enemyInfo.dirY, mustCancel)
                    } else {
                        // Непрерывное зажатие джойстика ходьбы (обход стены или удержание дистанции)
                        val wx = if (enemyInfo.isBehindWall) -enemyInfo.dirY * strafe else if (enemyInfo.dist < enemyInfo.enemyAtkRadius) -enemyInfo.dirX else enemyInfo.dirX
                        val wy = if (enemyInfo.isBehindWall) enemyInfo.dirX * strafe else if (enemyInfo.dist < enemyInfo.enemyAtkRadius) -enemyInfo.dirY else enemyInfo.dirY
                        hands.holdAndSteerWalk(mx, my, wx, wy)
                    }
                } else {
                    walkAng += 0.08
                    hands.holdAndSteerWalk(mx, my, cos(walkAng).toFloat(), sin(walkAng).toFloat())
                }
            }
        } catch (_: Exception) {} finally { img.close() }
    }

    private fun groupEnemies(pts: List<PointF>, rad: Float): List<Pair<PointF, Float>> {
        val groups = mutableListOf<MutableList<PointF>>()
        for (p in pts) {
            val g = groups.find { hypot(it[0].x - p.x, it[0].y - p.y) < rad }
            if (g == null) groups.add(mutableListOf(p)) else g.add(p)
        }
        return groups.filter { it.size >= 2 }.map { g ->
            val w = (g.maxOf { it.x } - g.minOf { it.x }).coerceAtLeast(30f)
            Pair(PointF(g.map { it.x }.average().toFloat(), g.map { it.y }.average().toFloat()), w)
        }
    }

    private fun cluster(pts: List<PointF>, rad: Float, minPts: Int): List<PointF> {
        val groups = mutableListOf<MutableList<PointF>>()
        for (p in pts) {
            val g = groups.find { hypot(it[0].x - p.x, it[0].y - p.y) < rad }
            if (g == null) groups.add(mutableListOf(p)) else g.add(p)
        }
        return groups.filter { it.size >= minPts }.map { g ->
            PointF(g.map { it.x }.average().toFloat(), g.map { it.y }.average().toFloat())
        }
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null); vd?.release(); ir?.close(); mp?.stop()
        if (::hud.isInitialized) wm.removeView(hud)
        if (::panel.isInitialized) wm.removeView(panel)
        super.onDestroy()
    }
}
// КОНЕЦ ЧАСТИ 3
