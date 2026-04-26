package com.openautolink.app.input

/**
 * Scales touch coordinates from SurfaceView pixel space to video/touchscreen space.
 *
 * Handles crop mode (VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING) where the
 * video is scaled uniformly to fill the entire surface, with overflow cropped.
 * This means when the surface aspect ratio differs from the video, the video
 * extends beyond the surface in one dimension and the visible area is centered.
 */
object TouchScaler {

    /**
     * Scale x/y from surface space to video space, accounting for crop mode.
     *
     * In crop mode: video is scaled by max(surfaceW/videoW, surfaceH/videoH)
     * to fill the surface completely. The excess is cropped (centered).
     *
     * Touch must reverse this: find where in the full video frame the surface
     * pixel corresponds to, including the crop offset.
     */
    fun scalePointCrop(
        surfaceX: Float,
        surfaceY: Float,
        surfaceWidth: Int,
        surfaceHeight: Int,
        videoWidth: Int,
        videoHeight: Int
    ): Pair<Float, Float> {
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || videoWidth <= 0 || videoHeight <= 0)
            return Pair(0f, 0f)

        val scaleX = surfaceWidth.toFloat() / videoWidth
        val scaleY = surfaceHeight.toFloat() / videoHeight
        val scale = maxOf(scaleX, scaleY) // crop uses max to fill

        // Displayed video size on surface (may exceed surface in one dimension)
        val displayedW = videoWidth * scale
        val displayedH = videoHeight * scale

        // Offset: how much of the video is cropped on each side (centered)
        val offsetX = (displayedW - surfaceWidth) / 2f
        val offsetY = (displayedH - surfaceHeight) / 2f

        // Map surface pixel to video pixel
        val vx = (surfaceX + offsetX) / scale
        val vy = (surfaceY + offsetY) / scale

        return Pair(
            vx.coerceIn(0f, videoWidth.toFloat()),
            vy.coerceIn(0f, videoHeight.toFloat())
        )
    }

    /**
     * Simple linear scale (for letterbox mode where video maps 1:1 to surface).
     */
    fun scalePoint(
        surfaceX: Float,
        surfaceY: Float,
        surfaceWidth: Int,
        surfaceHeight: Int,
        videoWidth: Int,
        videoHeight: Int
    ): Pair<Float, Float> {
        return Pair(
            scale(surfaceX, surfaceWidth, videoWidth),
            scale(surfaceY, surfaceHeight, videoHeight)
        )
    }

    fun scale(surfaceCoord: Float, surfaceDimension: Int, videoDimension: Int): Float {
        if (surfaceDimension <= 0 || videoDimension <= 0) return 0f
        val scaled = surfaceCoord * videoDimension / surfaceDimension
        return scaled.coerceIn(0f, videoDimension.toFloat())
    }
}
