package com.magic.carderase

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 魔术刮刮乐擦除控件
 * 核心特性：
 * 1. 底层绘制魔术师选定的扑克牌图片（居中等比缩放展示）。
 * 2. 顶层覆盖全黑蒙版（纯黑 Canvas 离屏缓冲）。
 * 3. 采用 PorterDuff.Mode.CLEAR 配合圆角画笔（STROKE_CAP_ROUND），实现边缘自然柔和的手指涂抹擦除效果。
 * 4. 支持重复载入新牌并一键重置黑色蒙版。
 */
class EraseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 底层扑克牌 Bitmap
    private var cardBitmap: Bitmap? = null
    // 扑克牌绘制目标矩形（等比居中）
    private val cardDestRect = RectF()

    // 顶层黑色蒙版离屏缓冲与画布
    private var maskBitmap: Bitmap? = null
    private var maskCanvas: Canvas? = null

    // 绘制蒙版到 View 的普通画笔
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // 扑克牌抗锯齿画笔
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // 橡皮擦线条画笔（使用 PorterDuff.Mode.CLEAR 擦除黑色像素）
    private val eraseLinePaint = Paint().apply {
        isAntiAlias = true
        isDither = true
        color = Color.TRANSPARENT
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 110f // 宽厚圆角笔刷，手感极佳
    }

    // 单点触摸擦除画笔（单次点击时擦出圆点）
    private val eraseDotPaint = Paint().apply {
        isAntiAlias = true
        isDither = true
        color = Color.TRANSPARENT
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        style = Paint.Style.FILL
    }

    // 贝塞尔曲线平滑触控轨迹
    private val touchPath = Path()
    private var lastX = 0f
    private var lastY = 0f

    init {
        // 确保使用软件或硬件加速下的离屏图层
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            initMask(w, h)
            calculateCardRect()
        }
    }

    /**
     * 初始化或重新创建纯黑蒙版
     */
    private fun initMask(w: Int, h: Int) {
        maskBitmap?.recycle()
        maskBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        maskCanvas = Canvas(maskBitmap!!).apply {
            drawColor(Color.BLACK)
        }
    }

    /**
     * 重置蒙版为全黑，清空已擦除的轨迹
     */
    fun resetMask() {
        maskCanvas?.let { canvas ->
            canvas.drawColor(Color.BLACK)
            touchPath.reset()
            invalidate()
        }
    }

    /**
     * 载入待展示扑克牌图片
     * 载入后自动将黑色蒙版完全重置为全黑，等待观众或表演者手指刮拭
     */
    fun setCardBitmap(bitmap: Bitmap?) {
        this.cardBitmap = bitmap
        calculateCardRect()
        resetMask()
    }

    /**
     * 当前是否已装载扑克牌
     */
    fun hasCard(): Boolean = cardBitmap != null

    /**
     * 计算扑克牌在全屏居中等比缩放的绘制范围
     */
    private fun calculateCardRect() {
        val bmp = cardBitmap ?: return
        if (width <= 0 || height <= 0) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val bmpW = bmp.width.toFloat()
        val bmpH = bmp.height.toFloat()

        // 扑克牌在手机屏幕上保留适当四周边距（例如宽度的 88%）
        val maxTargetW = viewW * 0.90f
        val maxTargetH = viewH * 0.85f

        val scale = min(maxTargetW / bmpW, maxTargetH / bmpH)
        val dstW = bmpW * scale
        val dstH = bmpH * scale

        val left = (viewW - dstW) / 2f
        val top = (viewH - dstH) / 2f
        cardDestRect.set(left, top, left + dstW, top + dstH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. 底层：纯黑底色
        canvas.drawColor(Color.BLACK)

        // 2. 底层图层：若已装载扑克牌，则居中绘制扑克牌
        cardBitmap?.let { bmp ->
            if (!bmp.isRecycled) {
                canvas.drawBitmap(bmp, null, cardDestRect, cardPaint)
            }
        }

        // 3. 顶层蒙版：绘制被擦除后的黑色蒙版
        maskBitmap?.let { mask ->
            if (!mask.isRecycled) {
                canvas.drawBitmap(mask, 0f, 0f, maskPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 只有当装载了扑克牌时才响应涂抹擦除（未装载牌前保持纯黑静止）
        if (cardBitmap == null) {
            return false
        }

        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x
                lastY = y
                touchPath.reset()
                touchPath.moveTo(x, y)

                // 单点触摸立即擦出自然圆孔
                maskCanvas?.drawCircle(x, y, eraseLinePaint.strokeWidth / 2f, eraseDotPaint)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = abs(x - lastX)
                val dy = abs(y - lastY)

                // 阈值平滑过滤：使用二阶贝塞尔曲线 quadTo 保证圆角转角自然丝滑，无锯齿棱角
                if (dx >= 3 || dy >= 3) {
                    val endX = (x + lastX) / 2f
                    val endY = (y + lastY) / 2f
                    touchPath.quadTo(lastX, lastY, endX, endY)

                    maskCanvas?.drawPath(touchPath, eraseLinePaint)

                    touchPath.reset()
                    touchPath.moveTo(endX, endY)

                    lastX = x
                    lastY = y
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchPath.lineTo(x, y)
                maskCanvas?.drawPath(touchPath, eraseLinePaint)
                touchPath.reset()
                invalidate()
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        maskBitmap?.recycle()
        maskBitmap = null
        maskCanvas = null
    }
}