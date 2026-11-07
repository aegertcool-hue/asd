package com.brawlbot.ai

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*

data class BrawlerStats(
    val name: String,
    val attackRadius: Float,
    val superRadius: Float,
    var dangerMemory: Float
)

object BrawlerDatabase {
    val brawlers = mutableMapOf(
        "Кит (Kit)" to BrawlerStats("Кит (Kit)", attackRadius = 120f, superRadius = 310f, dangerMemory = 1.0f),
        "Шелли (Shelly)" to BrawlerStats("Шелли (Shelly)", attackRadius = 230f, superRadius = 250f, dangerMemory = 1.5f),
        "Булл (Bull)" to BrawlerStats("Булл (Bull)", attackRadius = 170f, superRadius = 340f, dangerMemory = 1.4f),
        "Эдгар (Edgar)" to BrawlerStats("Эдгар (Edgar)", attackRadius = 110f, superRadius = 300f, dangerMemory = 1.6f),
        "Кольт (Colt)" to BrawlerStats("Кольт (Colt)", attackRadius = 340f, superRadius = 390f, dangerMemory = 1.1f),
        "Пайпер (Piper)" to BrawlerStats("Пайпер (Piper)", attackRadius = 410f, superRadius = 280f, dangerMemory = 1.2f)
    )
}

data class BushZone(
    val rect: RectF,
    var isCheckedPurple: Boolean = false,
    var checkedTimestamp: Long = 0L
)

data class TrackedEnemy(
    var brawler: BrawlerStats,
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var isHiddenInBush: Boolean = false
)

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val title = TextView(this).apply {
            text = "asd AI — Мозг и База Данных"
            textSize = 22f
            setTextColor(Color.WHITE)
        }
        layout.addView(title)

        val dbInfo = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(0, 24, 0, 24)
            text = buildDbText()
        }
        layout.addView(dbInfo)

        val learnBtn = Button(this).apply {
            text = "Тест лобби: Меня убил Булл (Самообучение)"
            setOnClickListener {
                BrawlerDatabase.brawlers["Булл (Bull)"]?.let {
                    it.dangerMemory += 0.3f
                }
                dbInfo.text = buildDbText()
                Toast.makeText(this@MainActivity, "asd запомнил! Опасность Булла выросла", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(learnBtn)

        val startBtn = Button(this).apply {
            text = "ЗАПУСТИТЬ ASD ПОВЕРХ ИГРЫ"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Разреши отображение поверх других окон!", Toast.LENGTH_LONG).show()
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                } else {
                    startService(Intent(this@MainActivity, OverlayService::class.java))
                    finish()
                }
            }
        }
        layout.addView(startBtn)

        setContentView(layout)
    }

    private fun buildDbText(): String {
        val sb = StringBuilder("Стартовая База asd (Атака / Ульта / Опасность):\n\n")
        for (b in BrawlerDatabase.brawlers.values) {
            sb.append("• ${b.name}: Атака=${b.attackRadius.toInt()}px | Ульта=${b.superRadius.toInt()}px | Опасность=x${"%.1f".format(b.dangerMemory)}\n")
        }
        return sb.toString()
    }
}

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var hudView: BotHudView
    private lateinit var controlPanel: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

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

        controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(200, 30, 30, 30))
            setPadding(16, 8, 16, 8)

            val checkBushBtn = Button(context).apply {
                text = "Ударить куст (Фиол)"
                textSize = 11f
                setOnClickListener { hudView.markBushesChecked() }
            }
            addView(checkBushBtn)

            val switchEnemyBtn = Button(context).apply {
                text = "Сменить врага"
                textSize = 11f
                setOnClickListener { hudView.switchEnemyBrawler() }
            }
            addView(switchEnemyBtn)

            val closeBtn = Button(context).apply {
                text = "Х"
                textSize = 11f
                setTextColor(Color.RED)
                setOnClickListener { stopSelf() }
            }
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
            x = 40
            y = 60
        }
        wm.addView(controlPanel, panelParams)

        handler.post(updateRunnable)
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            hudView.updatePhysics()
            hudView.invalidate()
            handler.postDelayed(this, 30L)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::hudView.isInitialized) wm.removeView(hudView)
        if (::controlPanel.isInitialized) wm.removeView(controlPanel)
        super.onDestroy()
    }
}

class BotHudView(context: Context) : View(context) {
    private val brawlerList = BrawlerDatabase.brawlers.values.toList()
    private var currentEnemyIndex = 1

    private val enemy = TrackedEnemy(
        brawler = brawlerList[currentEnemyIndex],
        x = 400f,
        y = 350f,
        vx = 6f,
        vy = 3f
    )

    private val bushes = listOf(
        BushZone(RectF(650f, 250f, 950f, 520f)),
        BushZone(RectF(250f, 550f, 520f, 750f))
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
    }

    fun switchEnemyBrawler() {
        currentEnemyIndex = (currentEnemyIndex + 1) % brawlerList.size
        enemy.brawler = brawlerList[currentEnemyIndex]
    }

    fun markBushesChecked() {
        val now = System.currentTimeMillis()
        for (bush in bushes) {
            bush.isCheckedPurple = true
            bush.checkedTimestamp = now
        }
    }

    fun updatePhysics() {
        val now = System.currentTimeMillis()

        for (bush in bushes) {
            if (bush.isCheckedPurple && now - bush.checkedTimestamp > 6000L) {
                bush.isCheckedPurple = false
            }
        }

        enemy.x += enemy.vx
        enemy.y += enemy.vy

        if (enemy.x < 200f || enemy.x > 1100f) enemy.vx = -enemy.vx
        if (enemy.y < 200f || enemy.y > 700f) enemy.vy = -enemy.vy

        enemy.isHiddenInBush = bushes.any { it.rect.contains(enemy.x, enemy.y) }
        for (bush in bushes) {
            if (bush.rect.contains(enemy.x, enemy.y)) {
                bush.isCheckedPurple = false
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f

        for (bush in bushes) {
            paint.style = Paint.Style.FILL
            if (bush.isCheckedPurple) {
                paint.color = Color.argb(95, 170, 0, 255)
                canvas.drawRect(bush.rect, paint)
                canvas.drawText("БЕЗОПАСНО (0%)", bush.rect.left + 20f, bush.rect.centerY(), textPaint)
            } else {
                paint.color = Color.argb(80, 255, 200, 0)
                canvas.drawRect(bush.rect, paint)
                canvas.drawText("КУСТ ? (50/50)", bush.rect.left + 20f, bush.rect.centerY(), textPaint)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = if (bush.isCheckedPurple) Color.MAGENTA else Color.YELLOW
            canvas.drawRect(bush.rect, paint)
        }

        val myStats = BrawlerDatabase.brawlers["Кит (Kit)"]!!
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.color = Color.GREEN
        canvas.drawRect(centerX - 40f, centerY - 40f, centerX + 40f, centerY + 40f, paint)

        paint.strokeWidth = 2f
        paint.color = Color.argb(140, 0, 255, 100)
        canvas.drawCircle(centerX, centerY, myStats.attackRadius, paint)

        val effectiveSuperRadius = enemy.brawler.superRadius * enemy.brawler.dangerMemory

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(45, 255, 0, 0)
        canvas.drawCircle(enemy.x, enemy.y, effectiveSuperRadius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Color.RED
        canvas.drawCircle(enemy.x, enemy.y, effectiveSuperRadius, paint)

        paint.strokeWidth = 3f
        paint.color = if (enemy.isHiddenInBush) Color.CYAN else Color.YELLOW
        canvas.drawCircle(enemy.x, enemy.y, enemy.brawler.attackRadius, paint)

        val statusText = if (enemy.isHiddenInBush) {
            "В КУСТАХ (Предсказание!): ${enemy.brawler.name}"
        } else {
            "Враг: ${enemy.brawler.name}"
        }
        canvas.drawText(statusText, enemy.x - 100f, enemy.y - 20f, textPaint)
    }
}
