package com.classsentinel.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.classsentinel.core.detect.EventType

/**
 * 悬浮窗答案卡片（WindowManager 传统 View 实现，非 Compose）。
 * 点名 → 「老师点你名了」+ 应对提示；提问 → 问题卡片 + 流式答案。
 * 无悬浮窗权限时静默失败（仅 println）。
 */
object FloatAnswerWindow {

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var answerView: TextView? = null
    private var answerScroll: ScrollView? = null
    private var answerStarted = false

    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0
    private var startY = 0

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 提问事件：显示问题卡片，可流式追加答案（任意线程可调，内部切主线程） */
    fun show(context: Context, question: String) {
        mainHandler.post { showOnMain(context.applicationContext, question) }
    }

    private fun showOnMain(context: Context, question: String) {
        val root = ensureWindow(context) ?: return
        val questionView = root.getChildAt(1) as TextView
        questionView.visibility = View.VISIBLE
        questionView.text = question
        answerStarted = false
        answerView?.text = "思考中…"
        answerScroll?.visibility = View.VISIBLE
    }

    /** 点名事件：「老师点你名了」+ 应对提示（任意线程可调） */
    fun showRollcall(context: Context, trigger: String) {
        mainHandler.post { showRollcallOnMain(context.applicationContext, trigger) }
    }

    private fun showRollcallOnMain(context: Context, trigger: String) {
        val root = ensureWindow(context) ?: return
        val questionView = root.getChildAt(1) as TextView
        questionView.visibility = View.VISIBLE
        questionView.text = "老师点你名了！"
        val hintView = root.getChildAt(2) as TextView
        hintView.visibility = View.VISIBLE
        hintView.text = "听到「$trigger」\n应对提示：立即起立，答到，声音洪亮"
        answerScroll?.visibility = View.GONE
    }

    /** 流式追加答案（任意线程可调） */
    fun appendAnswer(text: String) {
        mainHandler.post { appendOnMain(text) }
    }

    private fun appendOnMain(text: String) {
        val av = answerView ?: return
        if (!answerStarted) {
            av.text = ""
            answerStarted = true
        }
        av.append(text)
        answerScroll?.post { answerScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    fun hide(context: Context) {
        val wm = windowManager
        rootView?.let {
            runCatching { wm?.removeView(it) }
                .onFailure { e -> println("[FloatWindow] removeView failed: ${e.message}") }
        }
        windowManager = null
        rootView = null
        layoutParams = null
        answerView = null
        answerScroll = null
        answerStarted = false
    }

    // ---------- 内部 ----------

    private fun ensureWindow(context: Context): LinearLayout? {
        rootView?.let { return it }
        if (!Settings.canDrawOverlays(context)) {
            println("[FloatWindow] no SYSTEM_ALERT_WINDOW permission, skip overlay")
            return null
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
        val dp = context.resources.displayMetrics.density

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(0xF2FFFFFF.toInt())
                setStroke((1 * dp).toInt(), 0x33000000)
            }
            elevation = 8 * dp
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        }

        // 标题行（拖拽手柄）+ 关闭
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(context).apply {
                text = "📖 课堂答题"
                textSize = 14f
                setTextColor(0xFF1F2937.toInt())
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        titleRow.addView(
            TextView(context).apply {
                text = "✕"
                textSize = 16f
                setTextColor(0xFF6B7280.toInt())
                setPadding((8 * dp).toInt(), 0, 0, 0)
                setOnClickListener { hide(context) }
            },
        )
        attachDrag(titleRow)
        card.addView(titleRow)

        // 问题区（点名/提问共用，1-2 行省略）
        card.addView(
            TextView(context).apply {
                textSize = 15f
                setTextColor(0xFF111827.toInt())
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        attachDrag(card.getChildAt(1))

        // 应对提示（仅点名显示）
        card.addView(
            TextView(context).apply {
                textSize = 13f
                setTextColor(0xFFDC2626.toInt())
                visibility = View.GONE
                setPadding(0, 0, 0, (8 * dp).toInt())
            },
        )

        // 答案滚动区（提问显示，流式追加）
        answerView = TextView(context).apply {
            textSize = 14f
            setTextColor(0xFF374151.toInt())
            setLineSpacing(2f, 1f)
            text = "思考中…"
        }
        answerScroll = ScrollView(context).apply {
            addView(answerView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        // 固定高度答案区（View 无 maxHeight 公开 API），超长可滚动
        card.addView(
            answerScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (220 * dp).toInt()),
        )

        layoutParams = WindowManager.LayoutParams(
            (280 * dp).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (24 * dp).toInt()
            y = (120 * dp).toInt()
        }
        runCatching { wm.addView(card, layoutParams) }
            .onFailure { e ->
                println("[FloatWindow] addView failed: ${e.message}")
                return null
            }
        windowManager = wm
        rootView = card
        return card
    }

    /** 拖拽：标题行/问题区 onTouch 更新 layoutParams 坐标 */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(view: View) {
        view.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = ev.rawX
                    lastY = ev.rawY
                    startX = layoutParams?.x ?: 0
                    startY = layoutParams?.y ?: 0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val lp = layoutParams ?: return@setOnTouchListener true
                    lp.x = (startX + (ev.rawX - lastX)).toInt()
                    lp.y = (startY + (ev.rawY - lastY)).toInt()
                    runCatching { windowManager?.updateViewLayout(rootView, lp) }
                    true
                }
                else -> false
            }
        }
    }
}
