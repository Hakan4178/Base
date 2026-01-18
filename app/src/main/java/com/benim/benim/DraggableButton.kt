package com.benim.benim.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatButton

class DraggableButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatButton(context, attrs, defStyleAttr) {

    var onDragEnd: ((Float, Float) -> Unit)? = null
    var onLongSettings: (() -> Unit)? = null
    var clickAction: (() -> Unit)? = null

    var editMode: Boolean = false
        set(value) {
            field = value
            refreshVisual()
        }

    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    init {
        isAllCaps = false
        refreshVisual()
    }

    private fun refreshVisual() {
        setBackgroundResource(
            if (editMode) android.R.drawable.btn_default_small
            else android.R.drawable.btn_default
        )
        alpha = if (editMode) 0.9f else 0.85f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // parent yoksa hiç dokunma
        val parentView = parent as? android.view.ViewGroup ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = x
                startY = y
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (parentView.width <= 0 || parentView.height <= 0) return true // layout henüz hazır değil

                val dx = event.rawX - downX
                val dy = event.rawY - downY

                if (!dragging && (dx * dx + dy * dy) > 100) { // 100 = biraz daha tolerans
                    dragging = true
                }

                if (dragging && editMode) {
                    val newX = (startX + dx).coerceIn(0f, (parentView.width - width).toFloat())
                    val newY = (startY + dy).coerceIn(0f, (parentView.height - height).toFloat())
                    x = newX
                    y = newY
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging && !editMode) {
                    clickAction?.invoke()
                } else if (!dragging && editMode) {
                    onLongSettings?.invoke()
                } else if (dragging) {
                    onDragEnd?.invoke(x, y)
                }
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}