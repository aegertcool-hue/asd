package com.brawlbot.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

class BotControlService : AccessibilityService() {
    companion object {
        var instance: BotControlService? = null
    }

    private var walkStroke: GestureDescription.StrokeDescription? = null
    private var lastWalkX = 0f
    private var lastWalkY = 0f
    var isBusyAiming = false
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {
        walkStroke = null
        isBusyAiming = false
    }

    override fun onDestroy() {
        walkStroke = null
        instance = null
        super.onDestroy()
    }

    // 1. НЕПРЕРЫВНОЕ ВЕДЕНИЕ ДЖОЙСТИКА ХОДЬБЫ
    fun holdAndSteerWalk(joyX: Float, joyY: Float, dirX: Float, dirY: Float) {
        if (isBusyAiming) return

        val radius = 138f
        val tx = (joyX + dirX * radius).coerceIn(40f, joyX + 175f)
        val ty = (joyY + dirY * radius).coerceIn(joyY - 175f, joyY + 175f)

        try {
            val cur = walkStroke
            if (cur == null) {
                val p = Path().apply {
                    moveTo(joyX, joyY)
                    lineTo(tx, ty)
                }
                val s = GestureDescription.StrokeDescription(p, 0L, 260L, true)
                dispatchGesture(GestureDescription.Builder().addStroke(s).build(), object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        walkStroke = s
                        lastWalkX = tx
                        lastWalkY = ty
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        walkStroke = null
                    }
                }, null)
            } else {
                val nx = if (abs(tx - lastWalkX) < 3f) lastWalkX + 2f else tx
                val ny = if (abs(ty - lastWalkY) < 3f) lastWalkY + 2f else ty
                val p = Path().apply {
                    moveTo(lastWalkX, lastWalkY)
                    lineTo(nx, ny)
                }
                val nextS = cur.continueStroke(p, 0L, 260L, true)
                dispatchGesture(GestureDescription.Builder().addStroke(nextS).build(), object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        walkStroke = nextS
                        lastWalkX = nx
                        lastWalkY = ny
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        walkStroke = null
                    }
                }, null)
            }
        } catch (_: Exception) {
            walkStroke = null
        }
    }

    // 2. СТАБИЛЬНОЕ НАВЕДЕНИЕ ПРИЦЕЛА БЕЗ ДРОЖАНИЯ РУК (с фиксацией точки и возможностью отмены в центр!)
    fun aimSteadyOrCancel(
        joyX: Float,
        joyY: Float,
        dirX: Float,
        dirY: Float,
        shouldCancel: Boolean
    ) {
        isBusyAiming = true
        walkStroke = null

        val pullX = joyX + dirX * 142f
        val pullY = joyY + dirY * 142f

        val path = Path().apply {
            moveTo(joyX, joyY)
            // Плавно вытягиваем прицел
            lineTo(pullX, pullY)
            if (shouldCancel) {
                // ОТМЕНА УЛЬТЫ / АТАКИ: плавно возвращаем палец обратно в центр джойстика!
                lineTo(joyX, joyY)
            } else {
                // Фиксируем палец в конечной точке прицела (чтобы руки не дрожали перед отпусканием!)
                lineTo(pullX + dirX * 3f, pullY + dirY * 3f)
            }
        }

        val duration = if (shouldCancel) 300L else 230L
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration, false)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                isBusyAiming = false
            }
            override fun onCancelled(g: GestureDescription?) {
                isBusyAiming = false
            }
        }, null)
    }

    // 3. БЫСТРЫЙ УДАР ВБЛИЗИ (КОМБО ПОСЛЕ ПРЫЖКА НА ПОКО, ЧТОБЫ ДОБИВАТЬ ВРАГА!)
    fun fastCloseRangeStrike(atkX: Float, atkY: Float, dirX: Float, dirY: Float) {
        isBusyAiming = true
        walkStroke = null
        val p = Path().apply {
            moveTo(atkX, atkY)
            lineTo(atkX + dirX * 90f, atkY + dirY * 90f)
        }
        val s = GestureDescription.StrokeDescription(p, 0L, 95L, false)
        dispatchGesture(GestureDescription.Builder().addStroke(s).build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { isBusyAiming = false }
            override fun onCancelled(g: GestureDescription?) { isBusyAiming = false }
        }, null)
    }

    // 4. ПРИЦЕЛЬНОЕ ИСПОЛЬЗОВАНИЕ ГАДЖЕТА
    fun activateAndAimGadget(gadX: Float, gadY: Float, atkX: Float, atkY: Float, dirX: Float, dirY: Float, cancel: Boolean) {
        isBusyAiming = true
        walkStroke = null
        val p = Path().apply { moveTo(gadX, gadY) }
        val s = GestureDescription.StrokeDescription(p, 0L, 65L, false)
        dispatchGesture(GestureDescription.Builder().addStroke(s).build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                aimSteadyOrCancel(atkX, atkY, dirX, dirY, cancel)
            }
            override fun onCancelled(g: GestureDescription?) {
                isBusyAiming = false
            }
        }, null)
    }
}
// КОНЕЦ ЧАСТИ 2
