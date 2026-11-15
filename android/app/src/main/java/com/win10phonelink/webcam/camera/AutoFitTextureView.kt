package com.win10phonelink.webcam.camera

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import kotlin.math.min

class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr) {

    fun configureTransform(
        viewWidth: Int,
        viewHeight: Int,
        streamWidth: Int,
        streamHeight: Int,
        displayRotation: Int
    ) {
        if (viewWidth <= 0 || viewHeight <= 0 || streamWidth <= 0 || streamHeight <= 0) return

        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270) {
            val bufferRect = RectF(0f, 0f, streamHeight.toFloat(), streamWidth.toFloat())
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = min(
                viewHeight.toFloat() / streamHeight.toFloat(),
                viewWidth.toFloat() / streamWidth.toFloat()
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (displayRotation - 2)).toFloat(), centerX, centerY)
        } else {
            val contentW = streamHeight.toFloat()
            val contentH = streamWidth.toFloat()
            val scale = min(viewWidth.toFloat() / contentW, viewHeight.toFloat() / contentH)
            val targetW = contentW * scale
            val targetH = contentH * scale
            matrix.setScale(targetW / viewWidth.toFloat(), targetH / viewHeight.toFloat(), centerX, centerY)
            if (displayRotation == Surface.ROTATION_180) {
                matrix.postRotate(180f, centerX, centerY)
            }
        }

        setTransform(matrix)
    }
}
