package com.brawlbot.ai

import android.app.Activity
import android.content.*
import android.graphics.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 48, 36, 36) }
        lay.addView(TextView(this).apply { text = "asd AI v4.0 — Наведение, Хил и Отмена Ульты"; textSize = 18f; setTextColor(Color.CYAN) })
        lay.addView(Button(this).apply {
            text = "1. ЗАПУСТИТЬ ASD (УМНЫЙ ПРИЦЕЛ И ХОДЬБА)"
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
            text = AsdBrain.loadJson(context).toString(2)
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

class BotHudView(ctx: Context) : View(ctx) {
    private var me = PointF(0f, 0f)
    private var enemy: EnemyInfo? = null
    private var bushes = listOf<PointF>()
    private var walls = listOf<PointF>()
    private var thought = "asd v4.0: Готов"
    var showZones = true

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; typeface = Typeface.DEFAULT_BOLD }

    fun updateHud(m: PointF, en: EnemyInfo?, bsh: List<PointF>, wls: List<PointF>, t: String) {
        me = m; enemy = en; bushes = bsh; walls = wls; thought = t; invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val mx = if (me.x > 0f) me.x else w / 2f; val my = if (me.y > 0f) me.y else h / 2f

        p.style = Paint.Style.FILL; p.color = Color.argb(180, 0, 0, 0)
        c.drawRoundRect(w * 0.20f, 16f, w * 0.94f, 70f, 14f, 14f, p)
        tp.color = if (AsdBrain.needHealing) Color.RED else Color.YELLOW
        c.drawText(thought, w * 0.22f, 52f, tp)

        // 1. КУСТЫ и СТЕНЫ
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f
        for (b in bushes) {
            p.color = Color.MAGENTA; c.drawRect(b.x - 50f, b.y - 50f, b.x + 50f, b.y + 50f, p)
            tp.color = Color.MAGENTA; c.drawText("КУСТ", b.x - 30f, b.y, tp)
        }
        for (wl in walls) {
            p.color = Color.rgb(255, 140, 0); c.drawRect(wl.x - 45f, wl.y - 45f, wl.x + 45f, wl.y + 45f, p)
            tp.color = Color.rgb(255, 140, 0); c.drawText("СТЕНА", wl.x - 35f, wl.y, tp)
        }

        // 2. ЗЕЛЕНЫЙ КУБИК (ходит за персонажем!) + САМОРЕДАКТИРУЕМЫЙ КРУГ РАДИУСА АТАКИ
        p.strokeWidth = 5f; p.color = if (AsdBrain.needHealing) Color.YELLOW else Color.GREEN
        c.drawRect(mx - 48f, my - 48f, mx + 48f, my + 48f, p)
        p.strokeWidth = 3f; c.drawCircle(mx, my, AsdBrain.myDynamicAtkRadius, p)
        tp.color = Color.GREEN
        c.drawText("Я (ХП:${AsdBrain.myHpPercent}% | Рендж:${AsdBrain.myDynamicAtkRadius.toInt()})", mx - 120f, my - 58f, tp)

        // 3. ПРОТИВНИК, ЕГО ТИП, ЛИНИЯ ПРИЦЕЛА И ПРОВЕРКА НА СТЕНУ
        enemy?.let { en ->
            p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = Color.CYAN
            c.drawRect(en.x - 55f, en.y - 55f, en.x + 55f, en.y + 55f, p)
            c.drawCircle(en.x, en.y, en.enemyAtkRadius, p)
            p.color = Color.RED; c.drawCircle(en.x, en.y, en.enemySupRadius, p)

            // Лазерная линия прицела (Зеленая = можно бить, Красная = за стеной, отмена ульты!)
            p.color = if (en.isBehindWall) Color.RED else Color.GREEN
            c.drawLine(mx, my, en.aimX, en.aimY, p)
            c.drawCircle(en.aimX, en.aimY, 16f, p)

            tp.color = Color.CYAN
            c.drawText("${en.enemyClass} (${en.dist.toInt()}px)", en.x - 110f, en.y - 65f, tp)
        }

        if (showZones) {
            zone(c, w * 0.16f, h * 0.74f, 88f, Color.GREEN, "ХОДЬБА (ЗАЖАТИЕ)")
            zone(c, w * 0.83f, h * 0.70f, 82f, Color.RED, "ПРИЦЕЛ АТАКИ")
            zone(c, w * 0.70f, h * 0.64f, 50f, Color.YELLOW, "УЛЬТА (ОТМЕНА В ЦЕНТР)")
            zone(c, w * 0.76f, h * 0.87f, 42f, Color.GREEN, "ГАДЖЕТ (ПРИЦЕЛ)")
        }
    }

    private fun zone(c: Canvas, x: Float, y: Float, r: Float, col: Int, txt: String) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = col
        c.drawCircle(x, y, r, p); tp.color = col; c.drawText(txt, x - r, y - r - 8f, tp)
    }
}
// КОНЕЦ ЧАСТИ 4
