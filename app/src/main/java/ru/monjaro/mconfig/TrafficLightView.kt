package ru.monjaro.mconfig

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * 红绿灯倒计时自定义视图 - 上下布局版本（固定大小）
 * 采用Java版本的胶囊形轮廓设计，统一大小和比例
 */
class TrafficLightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        // 颜色定义 - 与Java版本保持一致
        private const val COLOR_RED = 0xFFFF4444.toInt()      // Java版本红色
        private const val COLOR_GREEN = 0xFF44FF44.toInt()    // Java版本绿色
        private const val COLOR_YELLOW = 0xFFFFFF44.toInt()   // Java版本黄色
        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()     // 白色文本
        private const val COLOR_ARROW = 0xFFFFFFFF.toInt()    // 白色箭头

        // 尺寸定义 - 固定大小，不随scale变化
        private const val BASE_CIRCLE_DIAMETER = 48f      // 灯直径（固定大小）
        private const val BASE_TEXT_SIZE = 48f            // 倒计时文本大小（固定大小）
        private const val BASE_OUTLINE_STROKE = 1.5f      // 边框线宽
        private const val BASE_PADDING = 2f               // 基础内边距

        // 布局常量 - 调整垂直布局的间距
        private const val VERTICAL_GAP = 8f               // 灯与文本之间的垂直间隙

        // 箭头大小参数 - 缩小箭头
        private const val ARROW_SCALE_FACTOR = 0.8f       // 箭头缩放因子（0.8表示缩小到80%）
    }

    // 红绿灯状态
    private var status = TrafficLightManager.STATUS_NONE
    private var countdown = 0
    private var direction = TrafficLightManager.DIRECTION_STRAIGHT
    private var source = ""

    // 历史方向缓存
    private var lastValidDirection = TrafficLightManager.DIRECTION_STRAIGHT

    // 尺寸参数（支持缩放）
    private var scale = 1.0f  // 默认缩放比例，但不影响组件大小
    private var circleDiameter = BASE_CIRCLE_DIAMETER
    private var textSize = BASE_TEXT_SIZE
    private var outlineStroke = BASE_OUTLINE_STROKE
    private var padding = BASE_PADDING
    private var verticalGap = VERTICAL_GAP

    // 画笔
    private val lightPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = COLOR_TEXT
        textAlign = Paint.Align.CENTER  // 居中，上下布局需要居中
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }

    private val outlinePaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = outlineStroke
        strokeCap = Paint.Cap.ROUND
    }

    // 方向覆盖
    private var directionOverride = -1

    // 布局参数 - 修改为垂直布局
    private var circleCenterX = 0f
    private var circleCenterY = 0f
    private var textCenterX = 0f
    private var textCenterY = 0f



    // 箭头drawable - 与Java版本保持一致
    private var arrowDrawable: android.graphics.drawable.Drawable? = null

    init {
        // 初始化箭头drawable（与Java版本相同的drawable）
        arrowDrawable = ContextCompat.getDrawable(context, R.drawable.ic_direction_arrow)

        // 更新缩放尺寸
        updateScaledDimensions()

        // 默认隐藏
        visibility = View.GONE
    }

    private fun updateScaledDimensions() {
        // 圆形直径和文本大小保持固定，不随scale变化
        circleDiameter = BASE_CIRCLE_DIAMETER  // 固定大小
        textSize = BASE_TEXT_SIZE              // 固定大小

        // 只缩放边框线宽、内边距和间距
        outlineStroke = BASE_OUTLINE_STROKE * scale
        padding = BASE_PADDING * scale
        verticalGap = VERTICAL_GAP * scale

        textPaint.textSize = textSize  // 设置固定的文本大小
        outlinePaint.strokeWidth = outlineStroke
    }

    /**
     * 设置缩放比例（用于预览模式）
     * 注意：现在只影响间距，不影响组件大小
     */
    fun setPreviewScale(scale: Float) {
        this.scale = scale
        updateScaledDimensions()
        requestLayout()
        invalidate()
    }

    /**
     * 设置方向覆盖
     */
    fun setOverrideDirection(direction: Int) {
        this.directionOverride = direction
        this.direction = direction
        invalidate()
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
        // 处理方向：如果有覆盖方向，使用覆盖方向
        val effectiveDirection = if (directionOverride != -1) {
            directionOverride
        } else if (direction != 0) {
            lastValidDirection = direction
            direction
        } else {
            lastValidDirection
        }

        val timeChanged = this.countdown != countdown

        this.status = status
        this.countdown = countdown
        this.direction = effectiveDirection
        this.source = source

        // 控制显示/隐藏
        visibility = if (status != TrafficLightManager.STATUS_NONE && countdown >= 0) {
            View.VISIBLE
        } else {
            View.GONE
        }

        if (timeChanged) {
            requestLayout() // 时间改变时重新测量（数字位数可能变化）
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val width = w.toFloat()
        val height = h.toFloat()

        // 垂直布局：灯在上，文字在下
        circleCenterX = width / 2f
        circleCenterY = circleDiameter / 2f + padding

        textCenterX = width / 2f
        textCenterY = circleCenterY + circleDiameter / 2f + verticalGap + textPaint.textSize / 2f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 计算宽度：取圆形直径和文本宽度的较大值
        val circleWidth = circleDiameter
        val textWidth = measureTextWidth()
        val minWidth = maxOf(circleWidth, textWidth) + padding * 2

        // 计算高度：圆形直径 + 垂直间隙 + 文本高度 + 上下内边距
        // 文本高度通过FontMetrics计算更准确
        val fontMetrics = textPaint.fontMetrics
        val textHeight = fontMetrics.bottom - fontMetrics.top
        val minHeight = circleDiameter + verticalGap + textHeight + padding * 2

        // 使用View类的resolveSize方法
        val width = View.resolveSize(minWidth.toInt(), widthMeasureSpec)
        val height = View.resolveSize(minHeight.toInt(), heightMeasureSpec)

        setMeasuredDimension(width, height)
    }

    private fun measureTextWidth(): Float {
        if (countdown > 0) {
            val textStr = countdown.toString()
            return textPaint.measureText(textStr)
        }
        return 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (status == TrafficLightManager.STATUS_NONE) {
            return
        }

        // 1. 获取状态颜色
        val color = getStatusColor()

        // 2. 绘制圆形灯
        lightPaint.color = color
        val circleRadius = circleDiameter / 2f
        canvas.drawCircle(circleCenterX, circleCenterY, circleRadius, lightPaint)

        // 3. 绘制方向箭头（在灯上）- 使用drawable与Java版本一致
        drawDirectionArrow(canvas, circleCenterX, circleCenterY, circleRadius)

        // 4. 绘制倒计时文本
        if (countdown > 0) {
            drawCountdownText(canvas, color)
        }


    }

    /**
     * 绘制方向箭头
     */
    private fun drawDirectionArrow(canvas: Canvas, centerX: Float, centerY: Float, circleRadius: Float) {
        val drawable = arrowDrawable ?: return

        canvas.save()

        // 根据方向旋转（与Java版本一致）
        val rotation = when (direction) {
            TrafficLightManager.DIRECTION_LEFT -> -90f
            TrafficLightManager.DIRECTION_RIGHT -> 90f
            3 -> 180f  // U-turn，如果支持的话
            else -> 0f  // 直行或其他
        }
        canvas.rotate(rotation, centerX, centerY)

        // 箭头大小：基于圆形半径，但使用缩放因子缩小箭头
        val arrowSize = (circleRadius * ARROW_SCALE_FACTOR * 2).toInt() // 使用缩放因子缩小箭头
        val halfSize = arrowSize / 2

        // 设置drawable边界并绘制
        drawable.setBounds(
            (centerX - halfSize).toInt(),
            (centerY - halfSize).toInt(),
            (centerX + halfSize).toInt(),
            (centerY + halfSize).toInt()
        )
        drawable.draw(canvas)

        canvas.restore()
    }

    /**
     * 绘制倒计时文本
     */
    private fun drawCountdownText(canvas: Canvas, color: Int) {
        val countdownText = countdown.toString()
        textPaint.color = color

        // 计算文本基线位置
        val fontMetrics = textPaint.fontMetrics
        val textY = textCenterY - (fontMetrics.top + fontMetrics.bottom) / 2

        canvas.drawText(countdownText, textCenterX, textY, textPaint)
    }

    /**
     * 绘制调试信息
     */
    private fun drawDebugInfo(canvas: Canvas) {
        val debugPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = 0x80FFFFFF.toInt()
            textSize = textSize * 0.4f
            textAlign = Paint.Align.LEFT
        }

        // 在左上角显示来源和方向
        val debugText = "$source | ${getDirectionString(direction)}"
        canvas.drawText(debugText, 5f, debugPaint.textSize + 5, debugPaint)
    }

    /**
     * 获取状态颜色
     */
    private fun getStatusColor(): Int {
        return when (status) {
            TrafficLightManager.STATUS_GREEN -> COLOR_GREEN
            TrafficLightManager.STATUS_RED -> COLOR_RED
            TrafficLightManager.STATUS_YELLOW -> COLOR_YELLOW
            else -> COLOR_GREEN
        }
    }

    /**
     * 获取方向字符串
     */
    private fun getDirectionString(direction: Int): String {
        return when (direction) {
            TrafficLightManager.DIRECTION_STRAIGHT -> "直行"
            TrafficLightManager.DIRECTION_LEFT -> "左转"
            TrafficLightManager.DIRECTION_RIGHT -> "右转"
            3 -> "掉头"
            else -> "未知"
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
     * 清理资源
     */
    fun cleanup() {
        lastValidDirection = TrafficLightManager.DIRECTION_STRAIGHT
        directionOverride = -1
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cleanup()
    }
}