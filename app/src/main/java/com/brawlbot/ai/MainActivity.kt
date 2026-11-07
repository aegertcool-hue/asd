package com.brawlbot.ai

import android.app.Activity
import android.content.*
import android.graphics.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 48, 36, 36); setBackgroundColor(Color.parseColor("#121212")) }
        lay.addView(TextView(this).apply { text = "asd AI v4.1 — Калибровка 360°"; textSize = 20f; setTextColor(Color.CYAN) })
        
        lay.addView(Button(this).apply {
            text = "1. ЗАПУСТИТЬ ЗРЕНИЕ И ПАМЯТЬ"
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

        val mem = AsdBrain.loadJson(this)
        lay.addView(TextView(this).apply {
            text = "База asd:\n${mem.toString(2)}"
            textSize = 12f; setTextColor(Color.LTGRAY); typeface = Typeface.MONOSPACE
        })

        setContentView(ScrollView(this).apply { addView(lay) })
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == 101 && res == RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java).putExtra("rc", res).putExtra("data", data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            finish()
        }
    }
}

class BotHudView(ctx: Context) : View(ctx) {
    private var me = PointF(0f, 0f)
    private var enemy: EnemyInfo? = null
    private var bushes = listOf<PointF>(); private var walls = listOf<PointF>()
    private var thought = "asd: Ждет запуска..."
    var showZones = true

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; typeface = Typeface.DEFAULT_BOLD }

    fun updateHud(m: PointF, en: EnemyInfo?, bsh: List<PointF>, wls: List<PointF>, t: String) {
        me = m; enemy = en; bushes = bsh; walls = wls; thought = t; invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val mx = if (me.x > 0f) me.x else w / 2f; val my = if (me.y > 0f) me.y else h / 2f

        // Окно мыслей
        p.style = Paint.Style.FILL; p.color = Color.argb(180, 0, 0, 0)
        c.drawRoundRect(w * 0.2f, 15f, w * 0.95f, 70f, 14f, 14f, p)
        tp.color = Color.YELLOW; c.drawText(thought, w * 0.22f, 50f, tp)

        // Стены и Препятствия (Обводим ярко)
        p.style = Paint.Style.STROKE; p.strokeWidth = 5f
        for (wl in walls) {
            p.color = Color.rgb(255, 100, 0)
            c.drawRect(wl.x - 60f, wl.y - 60f, wl.x + 60f, wl.y + 60f, p)
            tp.color = Color.rgb(255, 100, 0); c.drawText("БЛОК", wl.x - 30f, wl.y, tp)
        }
        // Кусты
        for (b in bushes) {
            p.color = Color.MAGENTA; c.drawRect(b.x - 50f, b.y - 50f, b.x + 50f, b.y + 50f, p)
            tp.color = Color.MAGENTA; c.drawText("КУСТ ?", b.x - 35f, b.y, tp)
        }

        // Я (Зеленый кубик)
        p.color = Color.GREEN; c.drawRect(mx - 50f, my - 50f, mx + 50f, my + 50f, p)
        p.strokeWidth = 3f; c.drawCircle(mx, my, AsdBrain.myDynamicAtkRadius, p)
        tp.color = Color.GREEN; c.drawText("Я (ASD)", mx - 50f, my - 65f, tp)

        // Враг (Голубой с лазером)
        enemy?.let { en ->
            p.style = Paint.Style.STROKE; p.strokeWidth = 6f; p.color = Color.CYAN
            c.drawRect(en.x - 60f, en.y - 60f, en.x + 60f, en.y + 60f, p)
            c.drawCircle(en.x, en.y, en.enemyAtkRadius, p)
            p.color = Color.RED; c.drawCircle(en.x, en.y, en.enemySupRadius, p)
            
            // Лазер прицеливания
            p.color = if (en.isBehindWall) Color.RED else Color.GREEN
            c.drawLine(mx, my, en.aimX, en.aimY, p)
            c.drawCircle(en.aimX, en.aimY, 15f, p)

            tp.color = Color.CYAN; c.drawText("${en.enemyClass} (${en.dist.toInt()}px)", en.x - 100f, en.y - 70f, tp)
        }

        if (showZones) {
            zone(c, w * 0.16f, h * 0.74f, 90f, Color.GREEN, "ХОДЬБА")
            zone(c, w * 0.83f, h * 0.70f, 85f, Color.RED, "АТАКА")
            zone(c, w * 0.70f, h * 0.64f, 55f, Color.YELLOW, "УЛЬТА")
            zone(c, w * 0.76f, h * 0.87f, 45f, Color.GREEN, "ГАДЖЕТ")
            zone(c, w * 0.61f, h * 0.82f, 48f, Color.MAGENTA, "ГИПЕР")
        }
    }

    private fun zone(c: Canvas, x: Float, y: Float, r: Float, col: Int, txt: String) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = col
        c.drawCircle(x, y, r, p); tp.color = col; c.drawText(txt, x - r, y - r - 10f, tp)
    }
}
// КОНЕЦ ЧАСТИ 4
