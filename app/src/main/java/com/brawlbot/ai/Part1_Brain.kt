package com.brawlbot.ai

import android.content.Context
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
    val enemyClass: String, // Танк-Ближник, Стрелок или Снайпер
    val enemyAtkRadius: Float,
    val enemySupRadius: Float,
    val isBehindWall: Boolean
)

object AsdBrain {
    // Наш динамический радиус: сам меняется, когда бот наводится прицелом или включает гаджет!
    var myDynamicAtkRadius = 210f
    var myDynamicSupRadius = 310f
    var isGadgetActive = false

    // Состояние здоровья нашего бойца (0..100%)
    var myHpPercent = 100
    val needHealing: Boolean get() = myHpPercent < 55

    private var prevEnX = -1f
    private var prevEnY = -1f
    var canceledSupersCount = 0
    var healedTimesCount = 0

    // Авто-калибровка радиуса круга по белой линии прицела на экране
    fun calibrateRadiusFromAimLine(measuredLengthPx: Float) {
        if (measuredLengthPx in 110f..480f) {
            // Плавно подстраиваем круг под реальный прицел бойца (с учетом гаджета)
            myDynamicAtkRadius = (myDynamicAtkRadius * 0.6f) + (measuredLengthPx * 0.4f)
        }
    }

    // Определяет, какой именно противник перед нами, его радиусы и закрыт ли он стеной
    fun analyzeEnemy(
        meX: Float,
        meY: Float,
        enX: Float,
        enY: Float,
        hpBarWidthPx: Float,
        walls: List<android.graphics.PointF>
    ): EnemyInfo {
        val vx = if (prevEnX > 0f) (enX - prevEnX).coerceIn(-40f, 40f) else 0f
        val vy = if (prevEnY > 0f) (enY - prevEnY).coerceIn(-40f, 40f) else 0f
        prevEnX = enX
        prevEnY = enY

        val speed = hypot(vx, vy)
        // По скорости и полоске ХП определяем тип врага и его реальный радиус атаки/ульты
        val (eClass, eAtkR, eSupR) = when {
            speed > 14f -> Triple("Быстрый Ближник (Кит/Эдгар)", 135f, 320f)
            hpBarWidthPx > 75f -> Triple("Танк (Булл/Шелли)", 190f, 300f)
            else -> Triple("Дальник-Стрелок (Кольт/Пайпер)", 360f, 390f)
        }

        val leadX = enX + vx * 2.0f
        val leadY = enY + vy * 2.0f
        val dx = leadX - meX
        val dy = leadY - meY
        val dist = hypot(enX - meX, enY - meY).coerceAtLeast(1f)
        val dirX = dx / hypot(dx, dy).coerceAtLeast(1f)
        val dirY = dy / hypot(dx, dy).coerceAtLeast(1f)

        // Проверяем, не стоит ли стена прямо на линии выстрела между нами и врагом
        val blockedByWall = walls.any { w ->
            val dToMe = hypot(w.x - meX, w.y - meY)
            if (dToMe >= dist) false
            else {
                val projX = meX + dirX * dToMe
                val projY = meY + dirY * dToMe
                hypot(w.x - projX, w.y - projY) < 65f
            }
        }

        return EnemyInfo(
            x = enX, y = enY,
            aimX = leadX, aimY = leadY,
            dist = dist, dirX = dirX, dirY = dirY,
            enemyClass = eClass,
            enemyAtkRadius = eAtkR,
            enemySupRadius = eSupR,
            isBehindWall = blockedByWall
        )
    }

    fun resetEnemy() {
        prevEnX = -1f
        prevEnY = -1f
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
            val state = JSONObject().apply {
                put("calibrated_attack_radius_px", myDynamicAtkRadius.toInt())
                put("calibrated_super_radius_px", myDynamicSupRadius.toInt())
                put("last_hp_percent", myHpPercent)
                put("supers_canceled_smartly", canceledSupersCount)
                put("healing_retreats_done", healedTimesCount)
            }
            root.put("live_self_learning", state)
            f.writeText(root.toString(2))
        }
    }
}
// КОНЕЦ ЧАСТИ 1
