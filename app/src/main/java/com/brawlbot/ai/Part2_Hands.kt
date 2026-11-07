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

    // 1. ДЖОЙСТИК ХОДЬБЫ: Зажимает и плавно водит палец БЕЗ отрыва от экрана!
    fun holdAndSteerWalk(joyX: Float, joyY: Float, dirX: Float, dirY: Float) {
        if (isBusyAiming) return

        val radius = 135f
        val tx = (joyX + dirX * radius).coerceIn(40f, joyX + 175f)
        val ty = (joyY + dirY * radius).coerceIn(joyY - 175f, joyY + 175f)

        try {
            val cur = walkStroke
            if (cur == null) {
                val p = Path().apply {
                    moveTo(joyX, joyY)
                    lineTo(tx, ty)
                }
                val s = GestureDescription.StrokeDescription(p, 0L, 250L, true)
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
                val nx = if (abs(tx - lastWalkX) < 2f) tx + 2f else tx
                val ny = if (abs(ty - lastWalkY) < 2f) ty + 2f else ty
                val p = Path().apply {
                    moveTo(lastWalkX, lastWalkY)
                    lineTo(nx, ny)
                }
                val nextS = cur.continueStroke(p, 0L, 250L, true)
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

    // 2. УМНОЕ ПРИЦЕЛИВАНИЕ (Атакой или Ультой) С ВОЗМОЖНОСТЬЮ ОТМЕНЫ!
    // Если shouldCancel == true (враг за стеной или вышел из ренджа), бот возвращает палец обратно в центр джойстика и ОТМЕНЯЕТ выстрел!
    fun aimOrCancelJoystick(
        joyX: Float,
        joyY: Float,
        dirX: Float,
        dirY: Float,
        shouldCancel: Boolean
    ) {
        isBusyAiming = true
        walkStroke = null

        val pullX = joyX + dirX * 140f
        val pullY = joyY + dirY * 140f

        val path = Path().apply {
            moveTo(joyX, joyY)
            // Сначала вытягиваем прицел в сторону врага (чтобы навестись и замерить радиус!)
            lineTo(pullX, pullY)
            if (shouldCancel) {
                // ОТМЕНА УЛЬТЫ / АТАКИ: уводим палец обратно в самый центр джойстика перед отпусканием!
                lineTo(joyX, joyY)
            }
        }

        val duration = if (shouldCancel) 280L else 170L
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

    // 3. НАЖАТИЕ И ПРИЦЕЛИВАНИЕ ГАДЖЕТОМ (включает гаджет и наводит прицел на врага!)
    fun activateAndAimGadget(
        gadX: Float,
        gadY: Float,
        atkX: Float,
        atkY: Float,
        dirX: Float,
        dirY: Float,
        shouldCancel: Boolean
    ) {
        isBusyAiming = true
        walkStroke = null

        val gadPath = Path().apply {
            moveTo(gadX, gadY)
            lineTo(gadX + dirX * 70f, gadY + dirY * 70f)
        }
        val s = GestureDescription.StrokeDescription(gadPath, 0L, 90L, false)
        dispatchGesture(GestureDescription.Builder().addStroke(s).build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                AsdBrain.isGadgetActive = true
                // Сразу наводимся атакой с усиленным радиусом гаджета (или отменяем, если враг спрятался)
                aimOrCancelJoystick(atkX, atkY, dirX, dirY, shouldCancel)
            }
            override fun onCancelled(g: GestureDescription?) {
                isBusyAiming = false
            }
        }, null)
    }

    fun tapButton(x: Float, y: Float) {
        walkStroke = null
        val p = Path().apply { moveTo(x, y) }
        val s = GestureDescription.StrokeDescription(p, 0L, 60L, false)
        dispatchGesture(GestureDescription.Builder().addStroke(s).build(), null, null)
    }
}
// КОНЕЦ ЧАСТИ 2
