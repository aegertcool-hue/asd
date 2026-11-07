package com.brawlbot.ai

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

// Индивидуальный профиль каждого бойца (Свой радиус атаки, Свой радиус ульты, Прицельный гаджет)
data class BrawlerProfile(
    val name: String,
    val atkRadius: Float,
    val supRadius: Float,
    val leadFactor: Float,     // Коэффициент упреждения прицела при движении врага
    val gadgetAimed: Boolean,  // Нужно ли целить гаджет свайпом
    val gadgetName: String
)

// Рассчитанная цель с точкой упреждения для прицельной стрельбы и гаджета
data class AimTarget(
    val rawX: Float,
    val rawY: Float,
    val aimX: Float, // Точка, куда реально нужно тянуть прицел (с упреждением!)
    val aimY: Float,
    val dist: Float,
    val dirX: Float, // Направление свайпа прицела по X (-1..+1)
    val dirY: Float  // Направление свайпа прицела по Y (-1..+1)
)

object AsdBrain {
    val profiles = listOf(
        BrawlerProfile("Кит (Kit)", atkRadius = 135f, supRadius = 330f, leadFactor = 1.4f, gadgetAimed = true, gadgetName = "Коробка / Чизбургер"),
        BrawlerProfile("Шелли (Shelly)", atkRadius = 245f, supRadius = 270f, leadFactor = 1.8f, gadgetAimed = true, gadgetName = "Глиняные голуби (Снайпер)"),
        BrawlerProfile("Кольт (Colt)", atkRadius = 360f, supRadius = 410f, leadFactor = 2.6f, gadgetAimed = true, gadgetName = "Серебряная пуля (Выстрел)"),
        BrawlerProfile("Пайпер (Piper)", atkRadius = 430f, supRadius = 290f, leadFactor = 3.0f, gadgetAimed = true, gadgetName = "Автоприцел (Толкающий выстрел)"),
        BrawlerProfile("Булл (Bull)", atkRadius = 175f, supRadius = 350f, leadFactor = 1.3f, gadgetAimed = false, gadgetName = "Стоп-кран / Стейк"),
        BrawlerProfile("Эдгар (Edgar)", atkRadius = 120f, supRadius = 310f, leadFactor = 1.2f, gadgetAimed = false, gadgetName = "Летим! / Щит")
    )

    var brawlerIndex = 0
    val current: BrawlerProfile get() = profiles[brawlerIndex]

    private var prevEnemyX = -1f
    private var prevEnemyY = -1f
    private var shotsAimedCount = 0
    private var gadgetsAimedCount = 0
    private var obstaclesBypassed = 0

    fun switchNextBrawler(ctx: Context): BrawlerProfile {
        brawlerIndex = (brawlerIndex + 1) % profiles.size
        saveAnalyticsToJson(ctx)
        return current
    }

    // Вычисляет точный вектор прицела (на упреждение движения врага!)
    fun calculateAim(meX: Float, meY: Float, enX: Float, enY: Float): AimTarget {
        val vx = if (prevEnemyX > 0f) (enX - prevEnemyX).coerceIn(-45f, 45f) else 0f
        val vy = if (prevEnemyY > 0f) (enY - prevEnemyY).coerceIn(-45f, 45f) else 0f
        prevEnemyX = enX
        prevEnemyY = enY

        val leadX = enX + vx * current.leadFactor
        val leadY = enY + vy * current.leadFactor

        val dx = leadX - meX
        val dy = leadY - meY
        val dist = hypot(dx, dy).coerceAtLeast(1f)

        return AimTarget(
            rawX = enX,
            rawY = enY,
            aimX = leadX,
            aimY = leadY,
            dist = hypot(enX - meX, enY - meY),
            dirX = dx / dist,
            dirY = dy / dist
        )
    }

    fun resetEnemyTracking() {
        prevEnemyX = -1f
        prevEnemyY = -1f
    }

    fun loadJson(ctx: Context): JSONObject {
        val f = File(ctx.filesDir, "asd_memory.json")
        if (!f.exists()) {
            val txt = runCatching { ctx.assets.open("asd_memory.json").bufferedReader().readText() }.getOrDefault("{}")
            f.writeText(txt)
        }
        return runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
    }

    fun recordAction(ctx: Context, aimedShot: Boolean, aimedGadget: Boolean, bypassedWall: Boolean) {
        if (aimedShot) shotsAimedCount++
        if (aimedGadget) gadgetsAimedCount++
        if (bypassedWall) obstaclesBypassed++
        if ((shotsAimedCount + gadgetsAimedCount + obstaclesBypassed) % 3 == 0) {
            saveAnalyticsToJson(ctx)
        }
    }

    fun saveAnalyticsToJson(ctx: Context) {
        runCatching {
            val f = File(ctx.filesDir, "asd_memory.json")
            val root = loadJson(ctx)
            val b = current
            val analytics = JSONObject().apply {
                put("selected_brawler", b.name)
                put("max_attack_radius_px", b.atkRadius.toInt())
                put("max_super_radius_px", b.supRadius.toInt())
                put("active_gadget", b.gadgetName)
                put("aimed_shots_fired", shotsAimedCount)
                put("aimed_gadgets_used", gadgetsAimedCount)
                put("walls_bypassed", obstaclesBypassed)
            }
            root.put("live_self_learning", analytics)
            f.writeText(root.toString(2))
        }
    }
}
// КОНЕЦ ЧАСТИ 1
