package ru.monjaro.mconfig

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 红绿灯倒计时自定义视图
 * 采用胶囊样式设计，左侧为红绿灯颜色和箭头，右侧为倒计时数字
 */
class TrafficLightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        // 颜色定义（与显示方法文件相同）
        private const val COLOR_RED = 0xFFFF4444.toInt()
        private const val COLOR_GREEN = 0xFF44FF44.toInt()
        private const val COLOR_YELLOW = 0xFFFFFF44.toInt()
        private const val COLOR_FLASHING_YELLOW = 0xFFFFFF88.toInt() // 黄闪颜色稍亮
        private const val COLOR_BACKGROUND = 0x33444444.toInt()  // 半透明背景
        private const val COLOR_BACKGROUND_DARK = 0x66222222.toInt() // 暗色背景
        private const val COLOR_TEXT = 0xFFCCCCCC.toInt()
        private const val COLOR_TEXT_DARK = 0xFF888888.toInt()
        private const val COLOR_OUTLINE = 0xFF666666.toInt()

        // 方向箭头颜色
        private const val COLOR_ARROW_DARK = 0xFF333333.toInt()
        private const val COLOR_ARROW_LIGHT = 0xFFDDDDDD.toInt()

        // 动画相关
        private const val BLINK_INTERVAL = 500L // 闪烁间隔
    }

    // 红绿灯状态
    private var status = TrafficLightManager.STATUS_NONE
    private var countdown = 0
    private var direction = TrafficLightManager.DIRECTION_STRAIGHT
    private var dataSource = ""

    // 闪烁相关
    private var isBlinking = false
    private var blinkVisible = true
    private val blinkHandler = android.os.Handler()
    private val blinkRunnable = object : Runnable {
        override fun run() {
            blinkVisible = !blinkVisible
            invalidate()
            blinkHandler.postDelayed(this, BLINK_INTERVAL)
        }
    }

    // 画笔
    private val capsulePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = COLOR_BACKGROUND
    }

    private val capsuleDarkPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = COLOR_BACKGROUND_DARK
    }

    private val lightPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val lightOutlinePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = COLOR_OUTLINE
    }

    private val outlinePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = COLOR_OUTLINE
    }

    private val textPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = COLOR_TEXT
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val textDarkPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = COLOR_TEXT_DARK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val arrowPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = COLOR_ARROW_DARK
    }

    private val arrowLightPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = COLOR_ARROW_LIGHT
    }

    // 箭头路径
    private val straightArrowPath = Path()
    private val leftArrowPath = Path()
    private val rightArrowPath = Path()
    private val straightLeftArrowPath = Path()
    private val straightRightArrowPath = Path()
    private val allDirectionPath = Path()

    // 尺寸
    private var capsuleRadius = 0f
    private var lightRadius = 0f
    private var textSize = 0f
    private var padding = 0f
    private var lightCenterX = 0f
    private var lightCenterY = 0f
    private var textCenterX = 0f
    private var textCenterY = 0f

    // 调试模式
    private var showDebugInfo = BuildConfig.DEBUG

    init {
        // 初始化箭头路径
        initArrowPaths()

        // 设置默认可见性
        visibility = View.GONE
    }

    private fun initArrowPaths() {
        // 直行箭头（向上箭头）
        straightArrowPath.reset()
        straightArrowPath.moveTo(0f, -0.5f)    // 上顶点
        straightArrowPath.lineTo(-0.3f, 0f)    // 左下
        straightArrowPath.lineTo(-0.1f, 0f)    // 左内
        straightArrowPath.lineTo(-0.1f, 0.5f)  // 下左
        straightArrowPath.lineTo(0.1f, 0.5f)   // 下右
        straightArrowPath.lineTo(0.1f, 0f)     // 右内
        straightArrowPath.lineTo(0.3f, 0f)     // 右下
        straightArrowPath.close()

        // 左转箭头
        leftArrowPath.reset()
        leftArrowPath.moveTo(0.3f, -0.5f)      // 右上
        leftArrowPath.lineTo(-0.3f, 0f)        // 左中
        leftArrowPath.lineTo(0.3f, 0.5f)       // 右下
        leftArrowPath.close()

        // 右转箭头
        rightArrowPath.reset()
        rightArrowPath.moveTo(-0.3f, -0.5f)    // 左上
        rightArrowPath.lineTo(0.3f, 0f)        // 右中
        rightArrowPath.lineTo(-0.3f, 0.5f)     // 左下
        rightArrowPath.close()

        // 直行+左转箭头
        straightLeftArrowPath.reset()
        // 直行部分
        straightLeftArrowPath.moveTo(-0.1f, -0.5f)
        straightLeftArrowPath.lineTo(-0.1f, -0.2f)
        straightLeftArrowPath.lineTo(-0.3f, -0.2f)
        straightLeftArrowPath.lineTo(0f, 0.1f)
        straightLeftArrowPath.lineTo(0.3f, -0.2f)
        straightLeftArrowPath.lineTo(0.1f, -0.2f)
        straightLeftArrowPath.lineTo(0.1f, 0.5f)
        straightLeftArrowPath.lineTo(-0.1f, 0.5f)
        straightLeftArrowPath.close()

        // 直行+右转箭头
        straightRightArrowPath.reset()
        // 直行部分
        straightRightArrowPath.moveTo(-0.1f, -0.5f)
        straightRightArrowPath.lineTo(-0.1f, -0.2f)
        straightRightArrowPath.lineTo(-0.3f, -0.2f)
        straightRightArrowPath.lineTo(-0.3f, 0f)
        straightRightArrowPath.lineTo(0f, 0.3f)
        straightRightArrowPath.lineTo(0.3f, 0f)
        straightRightArrowPath.lineTo(0.3f, -0.2f)
        straightRightArrowPath.lineTo(0.1f, -0.2f)
        straightRightArrowPath.lineTo(0.1f, 0.5f)
        straightRightArrowPath.lineTo(-0.1f, 0.5f)
        straightRightArrowPath.close()

        // 所有方向（十字箭头）
        allDirectionPath.reset()
        // 垂直部分
        allDirectionPath.moveTo(0f, -0.5f)
        allDirectionPath.lineTo(-0.1f, -0.4f)
        allDirectionPath.lineTo(-0.1f, -0.1f)
        allDirectionPath.lineTo(-0.4f, -0.1f)
        allDirectionPath.lineTo(-0.4f, 0.1f)
        allDirectionPath.lineTo(-0.1f, 0.1f)
        allDirectionPath.lineTo(-0.1f, 0.4f)
        allDirectionPath.lineTo(0.1f, 0.4f)
        allDirectionPath.lineTo(0.1f, 0.1f)
        allDirectionPath.lineTo(0.4f, 0.1f)
        allDirectionPath.lineTo(0.4f, -0.1f)
        allDirectionPath.lineTo(0.1f, -0.1f)
        allDirectionPath.lineTo(0.1f, -0.4f)
        allDirectionPath.close()
    }

    /**
     * 更新红绿灯状态
     */
    fun updateState(
        status: Int,
        countdown: Int,
        direction: Int = TrafficLightManager.DIRECTION_STRAIGHT,
        source: String = ""
    ) {
        val oldStatus = this.status
        val oldCountdown = this.countdown

        this.status = status
        this.countdown = countdown
        this.direction = direction
        this.dataSource = source

        // 控制闪烁效果
        if (status == TrafficLightManager.STATUS_YELLOW && oldStatus != status) {
            startBlinking()
        } else if (status != TrafficLightManager.STATUS_YELLOW && oldStatus == TrafficLightManager.STATUS_YELLOW) {
            stopBlinking()
        }

        // 只有当有有效数据时才显示
        visibility = if (status != TrafficLightManager.STATUS_NONE && countdown >= 0) {
            View.VISIBLE
        } else {
            View.GONE
        }

        // 如果状态或倒计时发生变化，重新测量
        if (oldStatus != status || oldCountdown != countdown) {
            requestLayout()
        }

        invalidate()
    }

    /**
     * 开始闪烁（黄灯）
     */
    private fun startBlinking() {
        if (isBlinking) return

        isBlinking = true
        blinkVisible = true
        blinkHandler.post(blinkRunnable)
    }

    /**
     * 停止闪烁
     */
    private fun stopBlinking() {
        if (!isBlinking) return

        isBlinking = false
        blinkHandler.removeCallbacks(blinkRunnable)
        blinkVisible = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // 计算尺寸
        capsuleRadius = h / 2f
        lightRadius = h * 0.3f
        textSize = h * 0.5f
        padding = h * 0.1f

        // 计算中心点
        lightCenterX = capsuleRadius + padding
        lightCenterY = h / 2f

        // 根据是否有倒计时调整文本位置
        textCenterX = if (countdown > 0) {
            // 有倒计时时文本在右侧
            lightCenterX + lightRadius + padding + (textSize * 0.3f)
        } else {
            // 无倒计时时文本在中间
            w / 2f
        }
        textCenterY = h / 2f + (textSize * 0.35f)

        // 设置文本大小
        textPaint.textSize = textSize
        textDarkPaint.textSize = textSize
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height)

        // 测量高度
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> min(desiredHeight, heightSize)
            else -> desiredHeight
        }

        // 测量宽度：根据是否有倒计时动态调整
        val baseWidth = (height * 1.5f).toInt() // 基础宽度（只有红绿灯）
        val countdownWidth = (height * 2.2f).toInt() // 包含倒计时的宽度

        val desiredWidth = if (countdown > 0) {
            // 计算倒计时数字宽度
            val textWidth = textPaint.measureText(countdown.toString())
            (baseWidth + textWidth + padding * 2).toInt()
        } else {
            baseWidth
        }

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)

        val width = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> min(desiredWidth, widthSize)
            else -> desiredWidth
        }

        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (status == TrafficLightManager.STATUS_NONE) return

        val width = width.toFloat()
        val height = height.toFloat()

        // 1. 绘制胶囊背景
        drawCapsuleBackground(canvas, width, height)

        // 2. 绘制红绿灯圆形
        drawTrafficLight(canvas, width, height)

        // 3. 绘制方向箭头
        if (blinkVisible) {
            drawDirectionArrow(canvas, width, height)
        }

        // 4. 绘制倒计时数字
        if (countdown > 0 && blinkVisible) {
            drawCountdownText(canvas, width, height)
        }

        // 5. 绘制调试信息（仅在调试模式显示）
        if (showDebugInfo && dataSource.isNotEmpty() && blinkVisible) {
            drawDebugInfo(canvas, width, height)
        }
    }

    /**
     * 绘制胶囊背景
     */
    private fun drawCapsuleBackground(canvas: Canvas, width: Float, height: Float) {
        val capsulePath = Path()
        val radius = capsuleRadius

        // 创建胶囊形状路径
        // 左半圆
        capsulePath.addArc(0f, 0f, radius * 2, height, 90f, 180f)
        // 顶部直线
        capsulePath.lineTo(width - radius, 0f)
        // 右半圆
        capsulePath.addArc(width - radius * 2, 0f, width, height, 270f, 180f)
        // 底部直线
        capsulePath.lineTo(radius, height)
        capsulePath.close()

        // 根据状态选择背景颜色
        val backgroundPaint = when (status) {
            TrafficLightManager.STATUS_GREEN -> capsuleDarkPaint
            TrafficLightManager.STATUS_RED -> capsuleDarkPaint
            TrafficLightManager.STATUS_YELLOW -> capsuleDarkPaint
            else -> capsulePaint
        }

        // 填充胶囊
        canvas.drawPath(capsulePath, backgroundPaint)

        // 绘制边框
        canvas.drawPath(capsulePath, outlinePaint)
    }

    /**
     * 绘制红绿灯圆形
     */
    private fun drawTrafficLight(canvas: Canvas, width: Float, height: Float) {
        if (!blinkVisible && status == TrafficLightManager.STATUS_YELLOW) {
            return // 闪烁时跳过绘制
        }

        // 根据状态设置颜色
        val (lightColor, outlineColor) = when (status) {
            TrafficLightManager.STATUS_GREEN -> Pair(COLOR_GREEN, COLOR_GREEN)
            TrafficLightManager.STATUS_RED -> Pair(COLOR_RED, COLOR_RED)
            TrafficLightManager.STATUS_YELLOW -> Pair(COLOR_YELLOW, COLOR_YELLOW)
            TrafficLightManager.STATUS_FLASHING_YELLOW -> Pair(COLOR_FLASHING_YELLOW, COLOR_FLASHING_YELLOW)
            else -> Pair(COLOR_GREEN, COLOR_GREEN)
        }

        lightPaint.color = lightColor
        lightOutlinePaint.color = outlineColor

        // 绘制红绿灯圆形
        canvas.drawCircle(lightCenterX, lightCenterY, lightRadius, lightPaint)

        // 绘制边框
        canvas.drawCircle(lightCenterX, lightCenterY, lightRadius, lightOutlinePaint)

        // 绘制内圆（高光效果）
        val highlightPaint = Paint(lightPaint).apply {
            color = adjustColorBrightness(lightColor, 1.3f)
        }
        canvas.drawCircle(lightCenterX - lightRadius * 0.2f,
            lightCenterY - lightRadius * 0.2f,
            lightRadius * 0.3f,
            highlightPaint)
    }

    /**
     * 绘制方向箭头
     */
    private fun drawDirectionArrow(canvas: Canvas, width: Float, height: Float) {
        canvas.save()

        // 移动到红绿灯中心
        canvas.translate(lightCenterX, lightCenterY)

        // 根据箭头大小缩放
        val arrowScale = lightRadius * 0.5f
        canvas.scale(arrowScale, arrowScale)

        // 根据方向选择箭头路径和画笔
        val (arrowPath, paint) = when (direction) {
            TrafficLightManager.DIRECTION_STRAIGHT -> Pair(straightArrowPath,
                if (status == TrafficLightManager.STATUS_RED) arrowLightPaint else arrowPaint)
            TrafficLightManager.DIRECTION_LEFT -> Pair(leftArrowPath,
                if (status == TrafficLightManager.STATUS_RED) arrowLightPaint else arrowPaint)
            TrafficLightManager.DIRECTION_RIGHT -> Pair(rightArrowPath,
                if (status == TrafficLightManager.STATUS_RED) arrowLightPaint else arrowPaint)
            TrafficLightManager.DIRECTION_STRAIGHT_LEFT -> Pair(straightLeftArrowPath,
                if (status == TrafficLightManager.STATUS_RED) arrowLightPaint else arrowPaint)
            TrafficLightManager.DIRECTION_STRAIGHT_RIGHT -> Pair(straightRightArrowPath,
                if (status == TrafficLightManager.STATUS_RED) arrowLightPaint else arrowPaint)
            TrafficLightManager.DIRECTION_ALL -> Pair(allDirectionPath,
                if (status == TrafficLightManager.STATUS_RED) arrowLightPaint else arrowPaint)
            else -> Pair(straightArrowPath,
                if (status == TrafficLightManager.STATUS_RED) arrowLightPaint else arrowPaint)
        }

        // 绘制箭头
        canvas.drawPath(arrowPath, paint)

        canvas.restore()
    }

    /**
     * 绘制倒计时数字
     */
    private fun drawCountdownText(canvas: Canvas, width: Float, height: Float) {
        // 根据状态选择文本颜色
        val paint = when (status) {
            TrafficLightManager.STATUS_GREEN -> textPaint.apply { color = COLOR_GREEN }
            TrafficLightManager.STATUS_RED -> textPaint.apply { color = COLOR_RED }
            TrafficLightManager.STATUS_YELLOW -> textPaint.apply { color = COLOR_YELLOW }
            TrafficLightManager.STATUS_FLASHING_YELLOW -> textPaint.apply { color = COLOR_FLASHING_YELLOW }
            else -> textPaint.apply { color = COLOR_TEXT }
        }

        // 绘制倒计时数字
        val countdownText = countdown.toString()
        canvas.drawText(countdownText, textCenterX, textCenterY, paint)

        // 绘制"秒"字（小号）
        val secondPaint = Paint(paint).apply {
            textSize = textSize * 0.4f
        }
        val secondTextX = textCenterX + paint.measureText(countdownText) / 2 + secondPaint.textSize * 0.5f
        val secondTextY = textCenterY - (paint.textSize - secondPaint.textSize) * 0.3f
        canvas.drawText("秒", secondTextX, secondTextY, secondPaint)
    }

    /**
     * 绘制调试信息
     */
    private fun drawDebugInfo(canvas: Canvas, width: Float, height: Float) {
        val debugPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = 0x80FFFFFF.toInt() // 半透明白色
            textSize = height * 0.12f
            textAlign = Paint.Align.CENTER
        }

        // 显示数据来源
        val sourceText = when (dataSource) {
            "navigation" -> "导航"
            "cruise" -> "巡航"
            "cruise_json" -> "巡航(JSON)"
            "generic" -> "通用"
            "test" -> "测试"
            else -> dataSource
        }

        // 在底部显示来源
        canvas.drawText(sourceText, width / 2, height - 5, debugPaint)

        // 显示方向信息
        val directionText = when (direction) {
            TrafficLightManager.DIRECTION_STRAIGHT -> "直行"
            TrafficLightManager.DIRECTION_LEFT -> "左转"
            TrafficLightManager.DIRECTION_RIGHT -> "右转"
            TrafficLightManager.DIRECTION_STRAIGHT_LEFT -> "直左"
            TrafficLightManager.DIRECTION_STRAIGHT_RIGHT -> "直右"
            TrafficLightManager.DIRECTION_ALL -> "全向"
            else -> "未知"
        }

        canvas.drawText(directionText, width / 2, height - debugPaint.textSize - 5, debugPaint)
    }

    /**
     * 调整颜色亮度
     */
    private fun adjustColorBrightness(color: Int, factor: Float): Int {
        val a = color shr 24 and 0xFF
        var r = color shr 16 and 0xFF
        var g = color shr 8 and 0xFF
        var b = color and 0xFF

        r = (r * factor).toInt().coerceIn(0, 255)
        g = (g * factor).toInt().coerceIn(0, 255)
        b = (b * factor).toInt().coerceIn(0, 255)

        return a shl 24 or (r shl 16) or (g shl 8) or b
    }

    /**
     * 获取状态颜色
     */
    fun getStatusColor(): Int {
        return when (status) {
            TrafficLightManager.STATUS_GREEN -> COLOR_GREEN
            TrafficLightManager.STATUS_RED -> COLOR_RED
            TrafficLightManager.STATUS_YELLOW -> COLOR_YELLOW
            TrafficLightManager.STATUS_FLASHING_YELLOW -> COLOR_FLASHING_YELLOW
            else -> COLOR_GREEN
        }
    }

    /**
     * 获取当前状态
     */
    fun getCurrentStatus(): Int = status

    /**
     * 获取当前倒计时
     */
    fun getCurrentCountdown(): Int = countdown

    /**
     * 获取当前方向
     */
    fun getCurrentDirection(): Int = direction

    /**
     * 设置调试模式
     */
    fun setDebugMode(enabled: Boolean) {
        showDebugInfo = enabled
        invalidate()
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        stopBlinking()
        blinkHandler.removeCallbacksAndMessages(null)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cleanup()
    }
}