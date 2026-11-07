package com.brawlbot.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class BotControlService : AccessibilityService() {
    companion object {
        var instance: BotControlService? = null
    }

    // Хранит зажатый палец на джойстике ходьбы (чтобы водить его НЕ отрывая!)
    private var activeWalkStroke: GestureDescription.StrokeDescription? = null
    private var lastFingerX = 0f
    private var lastFingerY = 0f
    private var isPerformingAim = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {
        activeWalkStroke = null
    }

    override fun onDestroy() {
        activeWalkStroke = null
        instance = null
        super.onDestroy()
    }

    // 1. НЕПРЕРЫВНОЕ ЗАЖАТИЕ И ВЕДЕНИЕ ДЖОЙСТИКА ХОДЬБЫ (палец НЕ отрывается от экрана!)
    fun holdAndDragWalkJoystick(centerX: Float, centerY: Float, dirX: Float, dirY: Float) {
        if (isPerformingAim) return

        val pullRadius = 135f
        val targetX = (centerX + dirX * pullRadius).coerceIn(50f, centerX + 180f)
        val targetY = (centerY + dirY * pullRadius).coerceIn(centerY - 180f, centerY + 180f)

        try {
            val currentStroke = activeWalkStroke
            if (currentStroke == null) {
                // Первый шаг: ставим палец в центр джойстика, тянем в сторону и ОСТАВЛЯЕМ ЗАЖАТЫМ (willContinue = true)
                val path = Path().apply {
                    moveTo(centerX, centerY)
                    lineTo(targetX, targetY)
                }
                val newStroke = GestureDescription.StrokeDescription(path, 0L, 240L, true)
                val gesture = GestureDescription.Builder().addStroke(newStroke).build()

                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        activeWalkStroke = newStroke
                        lastFingerX = targetX
                        lastFingerY = targetY
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        activeWalkStroke = null
                    }
                }, null)
            } else {
                // Продолжение: палец УЖЕ зажат! Просто плавно ведем его из старой точки в новую точку без отрыва!
                val path = Path().apply {
                    moveTo(lastFingerX, lastFingerY)
                    lineTo(targetX, targetY)
                }
                // Если точка почти не изменилась, делаем микро-движение на 2 пикселя, чтобы Android держал палец
                if (kotlin.math.abs(targetX - lastFingerX) < 2f && kotlin.math.abs(targetY - lastFingerY) < 2f) {
                    path.lineTo(targetX + 2f, targetY + 2f)
                }
                val continuedStroke = currentStroke.continueStroke(path, 0L, 240L, true)
                val gesture = GestureDescription.Builder().addStroke(continuedStroke).build()

                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        activeWalkStroke = continuedStroke
                        lastFingerX = targetX
                        lastFingerY = targetY
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        activeWalkStroke = null
                    }
                }, null)
            }
        } catch (_: Exception) {
            activeWalkStroke = null
        }
    }

    // 2. ПРИЦЕЛЬНЫЙ ВЫСТРЕЛ ОБЫЧНОЙ АТАКОЙ ИЛИ УЛЬТОЙ (зажимает и тянет прицел по вектору!)
    fun aimAndReleaseJoystick(joyX: Float, joyY: Float, dirX: Float, dirY: Float, pullDist: Float = 140f) {
        isPerformingAim = true
        activeWalkStroke = null // Освобождаем для точного прицельного свайпа

        val aimEndX = joyX + dirX * pullDist
        val aimEndY = joyY + dirY * pullDist

        val path = Path().apply {
            moveTo(joyX, joyY)
            lineTo(aimEndX, aimEndY)
        }
        // willContinue = false: зажал центр -> вытянул прицел на врага за 145 мс -> отпустил для выстрела!
        val aimStroke = GestureDescription.StrokeDescription(path, 0L, 145L, false)
        dispatchGesture(GestureDescription.Builder().addStroke(aimStroke).build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                isPerformingAim = false
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                isPerformingAim = false
            }
        }, null)
    }

    // 3. ПРИЦЕЛЬНОЕ ИСПОЛЬЗОВАНИЕ ГАДЖЕТА (активация гаджета + вытягивание прицела на врага!)
    fun useAndAimGadget(gadgetX: Float, gadgetY: Float, atkJoyX: Float, atkJoyY: Float, dirX: Float, dirY: Float, aimed: Boolean) {
        isPerformingAim = true
        activeWalkStroke = null

        val tapPath = Path().apply {
            moveTo(gadgetX, gadgetY)
            if (aimed) {
                lineTo(gadgetX + dirX * 95f, gadgetY + dirY * 95f)
            }
        }
        val gadgetStroke = GestureDescription.StrokeDescription(tapPath, 0L, 90L, false)
        dispatchGesture(GestureDescription.Builder().addStroke(gadgetStroke).build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (aimed) {
                    // Сразу после активации гаджета целимся свайпом в точку упреждения врага!
                    aimAndReleaseJoystick(atkJoyX, atkJoyY, dirX, dirY, 145f)
                } else {
                    isPerformingAim = false
                }
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                isPerformingAim = false
            }
        }, null)
    }

    // 4. БЫСТРОЕ НАЖАТИЕ КНОПКИ (для Гиперзаряда в крайнем случае)
    fun quickTap(x: Float, y: Float) {
        activeWalkStroke = null
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L, false)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
// КОНЕЦ ЧАСТИ 2
