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
        lay.addView(TextView(this).apply { text = "asd AI v4.0 — 4 Модуля (Прицел + Зажатие)"; textSize = 19f; setTextColor(Color.CYAN) })
        lay.addView(Button(this).apply {
            text = "1. ЗАПУСТИТЬ ASD (ПРИЦЕЛ + ГЛАЗА)"
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
    private var aim: AimTarget? = null
    private var bushes = listOf<PointF>()
    private var blocks = listOf<PointF>()
    private var thought = "asd v4.0: Готов"
    var showZones = true

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; typeface = Typeface.DEFAULT_BOLD }

    fun updateHud(m: PointF, a: AimTarget?, bsh: List<PointF>, blk: List<PointF>, t: String) {
        me = m; aim = a; bushes = bsh; blocks = blk; thought = t; invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val mx = if (me.x > 0f) me.x else w / 2f; val my = if (me.y > 0f) me.y else h / 2f
        val b = AsdBrain.current

        p.style = Paint.Style.FILL; p.color = Color.argb(180, 0, 0, 0)
        c.drawRoundRect(w * 0.20f, 16f, w * 0.94f, 70f, 14f, 14f, p)
        tp.color = Color.YELLOW; c.drawText(thought, w * 0.22f, 52f, tp)

        p.style = Paint.Style.STROKE; p.strokeWidth = 3f
        for (bu in bushes) {
            p.color = Color.MAGENTA; c.drawRect(bu.x - 50f, bu.y - 50f, bu.x + 50f, bu.y + 50f, p)
            tp.color = Color.MAGENTA; c.drawText("КУСТ ?", bu.x - 35f, bu.y, tp)
        }
        for (bl in blocks) {
            p.color = Color.rgb(255, 140, 0); c.drawRect(bl.x - 45f, bl.y - 45f, bl.x + 45f, bl.y + 45f, p)
            tp.color = Color.rgb(255, 140, 0); c.drawText("БЛОК", bl.x - 30f, bl.y, tp)
        }

        // НАШ БОЕЦ + ИНДИВИДУАЛЬНЫЙ РАДИУС АТАКИ И УЛЬТЫ
        p.strokeWidth = 5f; p.color = Color.GREEN
        c.drawRect(mx - 45f, my - 45f, mx + 45f, my + 45f, p)
        p.strokeWidth = 3f; c.drawCircle(mx, my, b.atkRadius, p)
        p.color = Color.argb(140, 0, 255, 120); c.drawCircle(mx, my, b.supRadius, p)
        tp.color = Color.GREEN; c.drawText("${b.name} (Атк:${b.atkRadius.toInt()} Ульт:${b.supRadius.toInt()})", mx - 130f, my - 56f, tp)

        // ВРАГ + ЛАЗЕРНАЯ ЛИНИЯ ПРИЦЕЛА НА УПРЕЖДЕНИЕ
        aim?.let { t ->
            p.style = Paint.Style.FILL; p.color = Color.argb(65, 0, 220, 255)
            c.drawRect(t.rawX - 55f, t.rawY - 55f, t.rawX + 55f, t.rawY + 55f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = Color.CYAN
            c.drawRect(t.rawX - 55f, t.rawY - 55f, t.rawX + 55f, t.rawY + 55f, p)

            // Красный лазер упреждения прицела (куда тянется джойстик атаки и гаджета)
            p.color = Color.RED; p.strokeWidth = 4f
            c.drawLine(mx, my, t.aimX, t.aimY, p)
            c.drawCircle(t.aimX, t.aimY, 22f, p)
            tp.color = Color.CYAN; c.drawText("ПРИЦЕЛ (${t.dist.toInt()}px)", t.aimX - 65f, t.aimY - 65f, tp)
        }

        if (showZones) {
            zone(c, w * 0.16f, h * 0.74f, 90f, Color.GREEN, "ЗАЖАТИЕ ХОДА")
            zone(c, w * 0.83f, h * 0.70f, 85f, Color.RED, "ПРИЦЕЛ АТАКИ")
            zone(c, w * 0.70f, h * 0.64f, 52f, Color.YELLOW, "ПРИЦЕЛ УЛЬТЫ")
            zone(c, w * 0.76f, h * 0.87f, 42f, Color.GREEN, "ГАДЖЕТ")
            zone(c, w * 0.61f, h * 0.82f, 45f, Color.MAGENTA, "ГИПЕР")
        }
    }

    private fun zone(c: Canvas, x: Float, y: Float, r: Float, col: Int, txt: String) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = col
        c.drawCircle(x, y, r, p); tp.color = col; c.drawText(txt, x - r, y - r - 8f, tp)
    }
}
// КОНЕЦ ЧАСТИ 4
