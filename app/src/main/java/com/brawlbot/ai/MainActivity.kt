package com.brawlbot.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import org.json.JSONObject
import java.io.File
import kotlin.math.*

object AsdMemory {
    fun load(ctx: Context): JSONObject {
        val f = File(ctx.filesDir, "asd_memory.json")
        if (!f.exists()) {
            val txt = runCatching { ctx.assets.open("asd_memory.json").bufferedReader().readText() }.getOrDefault("{}")
            f.writeText(txt)
        }
        return runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
    }
}

class BotControlService : AccessibilityService() {
    companion object { var instance: BotControlService? = null }
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, 55L)).build(), null, null)
    }

    fun move(x1: Float, y1: Float, x2: Float, y2: Float) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, 180L)).build(), null, null)
    }
}

class MainActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 48, 36, 36) }
        lay.addView(TextView(this).apply { text = "asd AI v3.0 — Зрение + Память + Руки"; textSize = 19f; setTextColor(Color.CYAN) })
        lay.addView(Button(this).apply {
            text = "1. ВКЛЮЧИТЬ ГЛАЗА И ПОДСВЕТКУ ВРАГОВ"
            setOnClickListener {
                if (!Settings.canDrawOverlays(context)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(mpm.createScreenCaptureIntent(), 101)
                }
            }
        })
        lay.addView(Button(this).apply {
            text = "2. ВКЛЮЧИТЬ РУКИ (СПЕЦ. ВОЗМОЖНОСТИ)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        lay.addView(TextView(this).apply {
            text = AsdMemory.load(context).toString(2)
            textSize = 11f; setTextColor(Color.LTGRAY); typeface = Typeface.MONOSPACE
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#121212")); addView(lay) })
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == 101 && res == RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java).putExtra("rc", res).putExtra("data", data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            finish()
        }
    }
}

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var hud: BotHudView
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private val h = Handler(Looper.getMainLooper())
    private var mp: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var ir: ImageReader? = null
    private var sw = 1920; private var sh = 1080
    private var auto = false; private var lastDist = 0f; private var stuck = 0; private var ang = 0.0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        hud = BotHudView(this)
        wm.addView(hud, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT))

        status = TextView(this).apply { text = "asd: Готов"; setTextColor(Color.CYAN); textSize = 11f; setPadding(8, 0, 8, 0) }
        panel = LinearLayout(this).apply {
            setBackgroundColor(Color.argb(210, 20, 20, 20)); setPadding(8, 4, 8, 4)
            addView(status)
            addView(Button(context).apply {
                text = "АВТО: ВЫКЛ"; textSize = 10f
                setOnClickListener { auto = !auto; text = if (auto) "АВТО: ВКЛ" else "АВТО: ВЫКЛ" }
            })
            addView(Button(context).apply {
                text = "Кнопки"; textSize = 10f
                setOnClickListener { hud.showZones = !hud.showZones; hud.invalidate() }
            })
            addView(Button(context).apply { text = "Х"; textSize = 10f; setTextColor(Color.RED); setOnClickListener { stopSelf() } })
        }
        val pp = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 30 }
        wm.addView(panel, pp)
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val ch = "asd_ch"
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
            vd = mp?.createVirtualDisplay("asd", sw / 4, sh / 4, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir?.surface, null, h)
            h.post(loop)
        }
        return START_NOT_STICKY
    }

    private val loop = object : Runnable {
        override fun run() {
            scanFrame()
            h.postDelayed(this, 190L)
        }
    }

    private fun scanFrame() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        sw = dm.widthPixels; sh = dm.heightPixels
        val img = ir?.acquireLatestImage() ?: return
        try {
            val pl = img.planes[0]; val buf = pl.buffer; val ps = pl.pixelStride; val rs = pl.rowStride
            val iw = img.width; val ih = img.height
            val sx = sw.toFloat() / iw; val sy = sh.toFloat() / ih
            val rawE = mutableListOf<PointF>(); val rawM = mutableListOf<PointF>()

            for (y in (ih * 0.14f).toInt() until (ih * 0.84f).toInt() step 2) {
                for (x in (iw * 0.12f).toInt() until (iw * 0.88f).toInt() step 2) {
                    if (x > iw * 0.62f && y > ih * 0.55f) continue
                    if (x < iw * 0.30f && y > ih * 0.55f) continue
                    val off = y * rs + x * ps
                    if (off + 2 >= buf.limit()) continue
                    val r = buf.get(off).toInt() and 0xFF
                    val g = buf.get(off + 1).toInt() and 0xFF
                    val b = buf.get(off + 2).toInt() and 0xFF
                    if (r > 215 && g in 15..68 && b in 20..78) rawE.add(PointF(x * sx, y * sy + 60f))
                    else if (g > 210 && r in 15..85 && b in 20..95 && x in (iw * 0.32f).toInt()..(iw * 0.68f).toInt()) rawM.add(PointF(x * sx, y * sy + 60f))
                }
            }
            val en = cluster(rawE); val meL = cluster(rawM)
            val me = if (meL.isNotEmpty()) meL[0] else PointF(sw / 2f, sh / 2f)
            val near = en.minByOrNull { hypot(it.x - me.x, it.y - me.y) }
            val d = near?.let { hypot(it.x - me.x, it.y - me.y) } ?: 9999f

            val msg = when {
                near == null -> "Мысль: Чисто. Ищу кусты ? (50/50), Гипер бережем!"
                d < 185f -> "КРАЙНИЙ СЛУЧАЙ (${d.toInt()}px)! ГИПЕР + УЛЬТА + АВТОАТАКА!"
                d < 310f -> "ОПАСНОСТЬ (${d.toInt()}px): Зона Ульты! Держу дистанцию!"
                else -> "Враг (${d.toInt()}px): Автоатака запрещена (далеко), мансую!"
            }
            status.text = "Врагов: ${en.size}"
            hud.update(me, en, msg)

            val hands = BotControlService.instance
            if (auto && hands != null) {
                val mx = sw * 0.16f; val my = sh * 0.74f; val ax = sw * 0.83f; val ay = sh * 0.70f
                if (abs(d - lastDist) < 6f) stuck++ else stuck = 0
                lastDist = d
                if (stuck >= 4) {
                    hud.update(me, en, "ЗАСТРЯЛ В СТЕНЕ! 3 удара и обход!")
                    hands.tap(ax, ay); ang += 1.57; stuck = 0
                } else if (near != null && d < 185f) {
                    hands.tap(sw * 0.61f, sh * 0.82f); hands.tap(sw * 0.70f, sh * 0.64f); hands.tap(ax, ay)
                } else {
                    hands.move(mx, my, mx + (cos(ang) * 110f).toFloat(), my + (sin(ang) * 110f).toFloat())
                }
            }
        } catch (_: Exception) {} finally { img.close() }
    }

    private fun cluster(pts: List<PointF>): List<PointF> {
        val res = mutableListOf<PointF>()
        for (p in pts) {
            val ex = res.find { hypot(it.x - p.x, it.y - p.y) < 160f }
            if (ex == null) res.add(PointF(p.x, p.y)) else { ex.x = (ex.x + p.x) / 2f; ex.y = (ex.y + p.y) / 2f }
        }
        return res
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null); vd?.release(); ir?.close(); mp?.stop()
        if (::hud.isInitialized) wm.removeView(hud)
        if (::panel.isInitialized) wm.removeView(panel)
        super.onDestroy()
    }
}

class BotHudView(ctx: Context) : View(ctx) {
    private var me = PointF(0f, 0f); private var enemies = listOf<PointF>()
    private var thought = "Мысль asd: Готов"; var showZones = true
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; typeface = Typeface.DEFAULT_BOLD }

    fun update(m: PointF, e: List<PointF>, t: String) { me = m; enemies = e; thought = t; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val mx = if (me.x > 0f) me.x else w / 2f; val my = if (me.y > 0f) me.y else h / 2f

        p.style = Paint.Style.FILL; p.color = Color.argb(170, 0, 0, 0)
        c.drawRoundRect(w * 0.22f, 20f, w * 0.90f, 75f, 14f, 14f, p)
        tp.color = Color.YELLOW; c.drawText(thought, w * 0.24f, 56f, tp)

        p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = Color.GREEN
        c.drawRect(mx - 50f, my - 50f, mx + 50f, my + 50f, p); c.drawCircle(mx, my, 175f, p)
        tp.color = Color.GREEN; c.drawText("Я (ASD)", mx - 45f, my - 60f, tp)

        for ((i, en) in enemies.withIndex()) {
            p.style = Paint.Style.FILL; p.color = Color.argb(55, 0, 220, 255)
            c.drawRect(en.x - 55f, en.y - 55f, en.x + 55f, en.y + 55f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = Color.CYAN
            c.drawRect(en.x - 55f, en.y - 55f, en.x + 55f, en.y + 55f, p); c.drawCircle(en.x, en.y, 220f, p)
            p.color = Color.RED; c.drawCircle(en.x, en.y, 310f, p)
            c.drawLine(mx, my, en.x, en.y, p)
            tp.color = Color.CYAN; c.drawText("ВРАГ #${i + 1}", en.x - 55f, en.y - 68f, tp)
        }

        if (showZones) {
            zone(c, w * 0.16f, h * 0.74f, 90f, Color.GREEN, "ХОДЬБА")
            zone(c, w * 0.83f, h * 0.70f, 85f, Color.RED, "АВТОАТАКА")
            zone(c, w * 0.70f, h * 0.64f, 52f, Color.YELLOW, "УЛЬТА")
            zone(c, w * 0.76f, h * 0.87f, 42f, Color.GREEN, "ГАДЖЕТ")
            zone(c, w * 0.61f, h * 0.82f, 45f, Color.MAGENTA, "ГИПЕР")
        }
    }

    private fun zone(c: Canvas, x: Float, y: Float, r: Float, col: Int, txt: String) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = col
        c.drawCircle(x, y, r, p); tp.color = col; c.drawText(txt, x - r, y - r - 8f, tp)
    }
}
// КОНЕЦ
