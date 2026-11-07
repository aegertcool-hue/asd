package com.brawlbot.ai

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

data class EnemyInfo(
    val x: Float,
    val y: Float,
    val aimX: Float,
    val aimY: Float,
    val dist: Float,
    val dirX: Float,
    val dirY: Float,
    val enemyClass: String,
    val enemyAtkRadius: Float,
    val enemySupRadius: Float,
    val isBehindWall: Boolean
)

object AsdBrain {
    var myDynamicAtkRadius = 220f
    var myDynamicSupRadius = 320f
    var myHpPercent = 100
    val needHealing: Boolean get() = myHpPercent < 50

    // Стабилизаторы координат (убирают прыжки "Я" в угол и дрожание рук при прицеливании!)
    private var smoothMeX = -1f
    private var smoothMeY = -1f
    private var smoothEnX = -1f
    private var smoothEnY = -1f
    private var smoothDirX = 0f
    private var smoothDirY = 0f

    var hitsLandedEstimate = 0
    var supersCanceled = 0

    // Фильтр против телепортации "Я" в угол экрана
    fun stabilizeMePosition(rawMe: PointF?, screenW: Float, screenH: Float): PointF {
        val cx = screenW / 2f
        val cy = screenH / 2f
        if (smoothMeX < 0f) {
            smoothMeX = cx
            smoothMeY = cy
        }
        if (rawMe != null && hypot(rawMe.x - cx, rawMe.y - cy) < screenW * 0.24f) {
            smoothMeX = smoothMeX * 0.65f + rawMe.x * 0.35f
            smoothMeY = smoothMeY * 0.65f + rawMe.y * 0.35f
        } else {
            // Плавно возвращаем к центру, никогда не прыгая в угол!
            smoothMeX = smoothMeX * 0.8f + cx * 0.2f
            smoothMeY = smoothMeY * 0.8f + cy * 0.2f
        }
        return PointF(smoothMeX, smoothMeY)
    }

    fun calibrateRadiusFromAimLine(measuredLengthPx: Float) {
        if (measuredLengthPx in 120f..470f) {
            myDynamicAtkRadius = (myDynamicAtkRadius * 0.7f) + (measuredLengthPx * 0.3f)
        }
    }

    fun analyzeEnemy(
        meX: Float,
        meY: Float,
        rawEnX: Float,
        rawEnY: Float,
        hpBarWidthPx: Float,
        wallBoxes: List<RectF>
    ): EnemyInfo {
        if (smoothEnX < 0f || hypot(rawEnX - smoothEnX, rawEnY - smoothEnY) > 350f) {
            smoothEnX = rawEnX
            smoothEnY = rawEnY
        }
        val vx = (rawEnX - smoothEnX).coerceIn(-35f, 35f)
        val vy = (rawEnY - smoothEnY).coerceIn(-35f, 35f)

        // Сглаживаем позицию врага (чтобы прицел не трясся!)
        smoothEnX = smoothEnX * 0.55f + rawEnX * 0.45f
        smoothEnY = smoothEnY * 0.55f + rawEnY * 0.45f

        val speed = hypot(vx, vy)
        val (eClass, eAtkR, eSupR) = when {
            speed > 13f -> Triple("Ближник/Ассасин", 145f, 320f)
            hpBarWidthPx > 72f -> Triple("Танк/Боец", 200f, 300f)
            else -> Triple("Стрелок/Поко", 310f, 370f)
        }

        val leadX = smoothEnX + vx * 1.6f
        val leadY = smoothEnY + vy * 1.6f
        val dx = leadX - meX
        val dy = leadY - meY
        val dist = hypot(smoothEnX - meX, smoothEnY - meY).coerceAtLeast(1f)
        val len = hypot(dx, dy).coerceAtLeast(1f)

        // Стабилизируем вектор рук (убирает эффект "дрожащих рук"!)
        val rawDirX = dx / len
        val rawDirY = dy / len
        smoothDirX = if (smoothDirX == 0f) rawDirX else (smoothDirX * 0.5f + rawDirX * 0.5f)
        smoothDirY = if (smoothDirY == 0f) rawDirY else (smoothDirY * 0.5f + rawDirY * 0.5f)
        val norm = hypot(smoothDirX, smoothDirY).coerceAtLeast(0.01f)
        val finalDirX = smoothDirX / norm
        val finalDirY = smoothDirY / norm

        // Проверка: перекрывает ли обведенное препятствие линию огня
        var blocked = false
        for (step in 1..4) {
            val px = meX + finalDirX * (dist * step / 5f)
            val py = meY + finalDirY * (dist * step / 5f)
            if (wallBoxes.any { it.contains(px, py) }) {
                blocked = true
                break
            }
        }

        return EnemyInfo(
            x = smoothEnX, y = smoothEnY,
            aimX = leadX, aimY = leadY,
            dist = dist, dirX = finalDirX, dirY = finalDirY,
            enemyClass = eClass,
            enemyAtkRadius = eAtkR,
            enemySupRadius = eSupR,
            isBehindWall = blocked
        )
    }

    fun resetEnemy() {
        smoothEnX = -1f
        smoothEnY = -1f
        smoothDirX = 0f
        smoothDirY = 0f
    }

    fun loadJson(ctx: Context): JSONObject {
        val f = File(ctx.filesDir, "asd_memory.json")
        if (!f.exists()) {
            val txt = runCatching { ctx.assets.open("asd_memory.json").bufferedReader().readText() }.getOrDefault("{}")
            f.writeText(txt)
        }
        return runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
    }

    fun saveBrainState(ctx: Context) {
        runCatching {
            val f = File(ctx.filesDir, "asd_memory.json")
            val root = loadJson(ctx)
            root.put("live_self_learning", JSONObject().apply {
                put("calibrated_atk_radius", myDynamicAtkRadius.toInt())
                put("my_hp_percent", myHpPercent)
                put("attacks_executed", hitsLandedEstimate)
                put("supers_canceled", supersCanceled)
            })
            f.writeText(root.toString(2))
        }
    }
}
// КОНЕЦ ЧАСТИ 1
