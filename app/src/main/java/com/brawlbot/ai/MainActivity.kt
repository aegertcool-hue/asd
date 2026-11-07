package com.brawlbot.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import org.json.JSONObject
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// 1. ЧТЕНИЕ И САМООБУЧЕНИЕ ФАЙЛА ПАМЯТИ asd_memory.json
object AsdBrainMemory {
    private const val FILE_NAME = "asd_memory.json"

    fun load(context: Context): JSONObject {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            return try {
                val assetText = context.assets.open(FILE_NAME).bufferedReader().use { it.readText() }
                file.writeText(assetText)
                JSONObject(assetText)
            } catch (e: Exception) {
                JSONObject().put("bot_name", "asd AI")
            }
        }
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            file.delete()
            load(context)
        }
    }

    fun updateLearning(context: Context, spotted: Int, wallBypassed: Boolean) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            val root = load(context)
            val stats = root.optJSONObject("self_learning_stats") ?: JSONObject()
            if (spotted > 0) {
                stats.put("enemies_spotted", stats.optInt("enemies_spotted", 0) + spotted)
            }
            if (wallBypassed) {
                stats.put("walls_bypassed", stats.optInt("walls_bypassed", 0) + 1)
            }
            root.put("self_learning_stats", stats)
            file.writeText(root.toString(2))
        } catch (_: Exception) {}
    }
}

// 2. РУКИ БОТА: УПРАВЛЕНИЕ ДЖОЙСТИКАМИ И КНОПКАМИ (AccessibilityService)
class BotControlService : AccessibilityService() {
    companion object {
        var instance: BotControlService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun tapButton(x: Float, y: Float, durationMs: Long = 55L) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun holdJoystick(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300L) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}

// 3. ГЛАВНОЕ МЕНЮ ПРИЛОЖЕНИЯ
class MainActivity : Activity() {
    private val REQ_CAPTURE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#121212")) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 50, 40, 40)
        }

        val title = TextView(this).apply {
            text = "asd AI v3.0 — Зрение + Память + Руки"
            textSize = 20f
            setTextColor(Color.CYAN)
        }
        layout.addView(title)

        val startVisionBtn = Button(this).apply {
            text = "1. ЗАПУСТИТЬ ГЛАЗА И ПОДСВЕТКУ ВРАГОВ"
            setOnClickListener { startVision() }
        }
        layout.addView(startVisionBtn)

        val enableHandsBtn = Button(this).apply {
            text = "2. ВКЛЮЧИТЬ РУКИ БОТА (СПЕЦ. ВОЗМОЖНОСТИ)"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Найди asd AI в списке и включи!", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        layout.addView(enableHandsBtn)

        val memJson = AsdBrainMemory.load(this)
        val jsonBox = TextView(this).apply {
            text = "База знаний (asd_memory.json):\n\n" + memJson.toString(2)
            textSize = 11f
            setTextColor(Color.LTGRAY)
            typeface = Typeface.MONOSPACE
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }
        layout.addView(jsonBox)

        scroll.addView(layout)
        setContentView(scroll)
    }

    private fun startVision() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разреши наложение поверх других окон!", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, OverlayService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            finish()
        }
    }
}

// 4. СЕРВИС ЗРЕНИЯ, МЫСЛЕЙ И АВТО-УПРАВЛЕНИЯ
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var hudView: BotHudView
    private lateinit var statusText: TextView
    private lateinit var controlPanel: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenW = 1920
    private var screenH = 1080
    private var autoPlayEnabled = false
    private var showButtonsOverlay = true

    // Переменные для анти-застревания (3 удара + обход)
    private var lastEnemyDist = 0f
    private var stuckCounter = 0
    private var walkAngle = 0.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        hudView = BotHudView(this)
        val hudParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(hudView, hudParams)

        statusText = TextView(this).apply {
            text = "asd: Готов"
            setTextColor(Color.CYAN)
            textSize = 11f
            setPadding(10, 0, 10, 0)
        }

        val autoBtn = Button(this).apply {
            text = "АВТО: ВЫКЛ"
            textSize = 10f
            setOnClickListener {
                autoPlayEnabled = !autoPlayEnabled
                text = if (autoPlayEnabled) "АВТО: ВКЛ" else "АВТО: ВЫКЛ"
            }
        }

        val btnToggleZones = Button(this).apply {
            text = "Кнопки"
            textSize = 10f
            setOnClickListener {
                showButtonsOverlay = !showButtonsOverlay
                hudView.showControls = showButtonsOverlay
                hudView.invalidate()
            }
        }

        val closeBtn = Button(this).apply {
            text = "Х"
            textSize = 10f
            setTextColor(Color.RED)
            setOnClickListener { stopSelf() }
        }

        controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(215, 20, 20, 20))
            setPadding(10, 4, 10, 4)
            addView(statusText)
            addView(autoBtn)
            addView(btnToggleZones)
            addView(closeBtn)
        }

        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 30
        }

        controlPanel.setOnTouchListener(object : View.OnTouchListener {
            private var ix = 0; private var iy = 0; private var tx = 0f; private var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { ix = panelParams.x; iy = panelParams.y; tx = e.rawX; ty = e.rawY; return true }
                    MotionEvent.ACTION_MOVE -> {
                        panelParams.x = ix + (e.rawX - tx).toInt()
                        panelParams.y = iy + (e.rawY - ty).toInt()
                        wm.updateViewLayout(controlPanel, panelParams)
                        return true
                    }
                }
                return false
            }
        })
        wm.addView(controlPanel, panelParams)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotif()
        val rc = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")
        if (rc == Activity.RESULT_OK && data != null) {
            setupCapture(rc, data)
            handler.post(loopRunnable)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotif() {
        val chId = "asd_v3"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(chId, "asd AI", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, chId).setContentTitle("asd AI v3.0").setSmallIcon(android.R.drawable.ic_menu_view).build()
        } else Notification.Builder(this).setContentTitle("asd AI").build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(1, n)
    }

    private fun setupCapture(resultCode: Int, data: Intent) {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        screenW = m.widthPixels
        screenH = m.heightPixels

        val capW = (screenW / 4).coerceAtLeast(100)
        val capH = (screenH / 4).coerceAtLeast(100)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AsdV3", capW, capH, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )
    }

    private val loopRunnable = object : Runnable {
        override fun run() {
            processFrameAndThink()
            handler.postDelayed(this, 190L)
        }
    }

    private fun processFrameAndThink() {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        screenW = m.widthPixels
        screenH = m.heightPixels

        val img = imageReader?.acquireLatestImage() ?: return
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val pStride = plane.pixelStride
            val rStride = plane.rowStride
            val iw = img.width
            val ih = img.height

            val rawEnemies = mutableListOf<PointF>()
            val rawMe = mutableListOf<PointF>()
            val sx = screenW.toFloat() / iw
            val sy = screenH.toFloat() / ih

            for (y in (ih * 0.14f).toInt() until (ih * 0.84f).toInt() step 2) {
                for (x in (iw * 0.12f).toInt() until (iw * 0.88f).toInt() step 2) {
                    if (x > iw * 0.62f && y > ih * 0.55f) continue
                    if (x < iw * 0.30f && y > ih * 0.55f) continue

                    val off = y * rStride + x * pStride
                    if (off + 2 >= buf.limit()) continue
                    val r = buf.get(off).toInt() and 0xFF
                    val g = buf.get(off + 1).toInt() and 0xFF
                    val b = buf.get(off + 2).toInt() and 0xFF

                    if (r > 215 && g in 15..68 && b in 20..78) {
                        rawEnemies.add(PointF(x * sx, y * sy + 60f))
                    } else if (g > 210 && r in 15..85 && b in 20..95) {
                        if (x in (iw * 0.32f).toInt()..(iw * 0.68f).toInt()) {
                            rawMe.add(PointF(x * sx, y * sy + 60f))
                        }
                    }
                }
            }

            val enemies = cluster(rawEnemies, 160f)
            val meCluster = cluster(rawMe, 160f)
            val myPos = if (meCluster.isNotEmpty()) meCluster[0] else PointF(screenW / 2f, screenH / 2f)

            // МЫСЛИ И РЕШЕНИЯ БОТА (по базе asd_memory.json)
            val nearestEnemy = enemies.minByOrNull { hypot(it.x - myPos.x, it.y - myPos.y) }
            val dist = nearestEnemy?.let { hypot(it.x - myPos.x, it.y - myPos.y) } ?: 9999f

            val thought: String
            if (nearestEnemy == null) {
                thought = "Мысль: Чисто. Проверяю кусты ? (50/50), Гиперзаряд бережем!"
            } else if (dist < 185f) {
                thought = "КРАЙНИЙ СЛУЧАЙ (${dist.toInt()}px)! ГИПЕРЗАРЯД + УЛЬТА + АВТОАТАКА!"
            } else if (dist < 310f) {
                thought = "ОПАСНОСТЬ (${dist.toInt()}px): Враг в зоне Ульты! Держу дистанцию / Гаджет!"
            } else {
                thought = "Вижу врага (${dist.toInt()}px): Автоатака запрещена (далеко), мансую!"
            }

            statusText.text = "Врагов: ${enemies.size}"
            hudView.updateState(myPos, enemies, thought)

            if (enemies.isNotEmpty()) AsdBrainMemory.updateLearning(this, enemies.size, false)

            // ЕСЛИ ВКЛЮЧЕН АВТО-БОТ И РАЗРЕШЕНЫ СПЕЦ. ВОЗМОЖНОСТИ
            val hands = BotControlService.instance
            if (autoPlayEnabled && hands != null) {
                val moveX = screenW * 0.16f
                val moveY = screenH * 0.74f
                val atkX = screenW * 0.83f
                val atkY = screenH * 0.70f
                val superX = screenW * 0.70f
                val superY = screenH * 0.64f
                val hyperX = screenW * 0.61f
                val hyperY = screenH * 0.82f

                // Проверка на застревание в стене/ящике
                if (kotlin.math.abs(dist - lastEnemyDist) < 6f) {
                    stuckCounter++
                } else {
                    stuckCounter = 0
                }
                lastEnemyDist = dist

                if (stuckCounter >= 4) {
                    // Застрял -> 3 удара автоатакой и поворот обхода!
                    hudView.updateState(myPos, enemies, "ЗАСТРЯЛ В СТЕНЕ! Делаю 3 удара и обхожу!")
                    hands.tapButton(atkX, atkY, 50L)
                    walkAngle += 1.57 // Поворот на 90 градусов
                    stuckCounter = 0
                    AsdBrainMemory.updateLearning(this, 0, true)
                } else if (nearestEnemy != null && dist < 185f) {
                    // Крайний случай: жмем Гиперзаряд -> Ульту -> Автоатаку!
                    hands.tapButton(hyperX, hyperY, 45L)
                    hands.tapButton(superX, superY, 45L)
                    hands.tapButton(atkX, atkY, 50L)
                } else if (nearestEnemy != null && dist < 290f) {
                    // Отступаем от врага
                    val dx = myPos.x - nearestEnemy.x
                    val dy = myPos.y - nearestEnemy.y
                    val len = hypot(dx, dy).coerceAtLeast(1f)
                    hands.holdJoystick(moveX, moveY, moveX + (dx / len) * 110f, moveY + (dy / len) * 110f, 170L)
                } else {
                    // Обычное движение по вектору
                    val tx = moveX + (cos(walkAngle) * 110f).toFloat()
                    val ty = moveY + (sin(walkAngle) * 110f).toFloat()
                    hands.holdJoystick(moveX, moveY, tx, ty, 170L)
                }
            }
        } catch (_: Exception) {
        } finally {
            img.close()
        }
    }

    private fun cluster(pts: List<PointF>, rad: Float): List<PointF> {
        val res = mutableListOf<PointF>()
        for (p in pts) {
            val ex = res.find { hypot(it.x - p.x, it.y - p.y) < rad }
            if (ex == null) res.add(PointF(p.x, p.y))
            else { ex.x = (ex.x + p.x) / 2f; ex.y = (ex.y + p.y) / 2f }
        }
        return res
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        if (::hudView.isInitialized) wm.removeView(hudView)
        if (::controlPanel.isInitialized) wm.removeView(controlPanel)
        super.onDestroy()
    }
}

// 5. ХУДОЖНИК: РИСУЕТ НАС (ЗЕЛЕНЫМ), ВРАГОВ (ГОЛУБЫМ), КНОПКИ И МЫСЛИ
class BotHudView(context: Context) : View(context) {
    private var myPos = PointF(0f, 0f)
    private var enemies = listOf<PointF>()
    private var currentThought = "Мысль asd: Анализирую..."
    var showControls = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
    }

    fun updateState(me: PointF, en: List<PointF>, thought: String) {
        myPos = me
        enemies = en
        currentThought = thought
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val meX = if (myPos.x > 0f) myPos.x else w / 2f
        val meY = if (myPos.y > 0f) myPos.y else h / 2f

        // 1. СТРОКА МЫСЛЕЙ МОДЕЛИ СВЕРХУ
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(170, 0, 0, 0)
        canvas.drawRoundRect(w * 0.22f, 20f, w * 0.88f, 75f, 14f, 14f, paint)
      
