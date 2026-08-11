package io.github.nitsuya.aa.display.util

import android.view.MotionEvent

/**
 * Rebuild a [MotionEvent] with optional coordinate division and time/source overrides.
 * Callers own recycle of the returned event.
 *
 * @param divideCoordsBy divide x/y by this factor (e.g. display mirror ratio); 1f = unchanged
 */
fun rewriteMotionEvent(
    source: MotionEvent,
    downTime: Long = source.downTime,
    eventTime: Long = source.eventTime,
    divideCoordsBy: Float = 1f,
    preserveMeta: Boolean = true,
    sourceOverride: Int? = null,
): MotionEvent {
    val count = source.pointerCount
    val pointerCoords = arrayOfNulls<MotionEvent.PointerCoords>(count)
    val pointerProperties = arrayOfNulls<MotionEvent.PointerProperties>(count)
    for (i in 0 until count) {
        val props = MotionEvent.PointerProperties()
        source.getPointerProperties(i, props)
        pointerProperties[i] = props
        val coords = MotionEvent.PointerCoords()
        source.getPointerCoords(i, coords)
        if (divideCoordsBy != 1f) {
            coords.x = coords.x / divideCoordsBy
            coords.y = coords.y / divideCoordsBy
        }
        pointerCoords[i] = coords
    }
    val newEvent = if (preserveMeta) {
        MotionEvent.obtain(
            downTime,
            eventTime,
            source.action,
            count,
            pointerProperties,
            pointerCoords,
            source.metaState,
            source.buttonState,
            source.xPrecision,
            source.yPrecision,
            source.deviceId,
            source.edgeFlags,
            source.source,
            source.flags,
        )
    } else {
        MotionEvent.obtain(
            downTime,
            eventTime,
            source.action,
            count,
            pointerProperties,
            pointerCoords,
            0,
            0,
            1.0f,
            1.0f,
            0,
            0,
            0,
            0,
        )
    }
    if (sourceOverride != null) {
        newEvent.source = sourceOverride
    }
    return newEvent
}
