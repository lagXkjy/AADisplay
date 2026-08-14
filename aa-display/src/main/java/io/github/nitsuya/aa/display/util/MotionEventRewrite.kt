package io.github.nitsuya.aa.display.util

import android.view.MotionEvent

/**
 * Rebuild a [MotionEvent] with rewritten timestamps / touchscreen source.
 * Callers own recycle of the returned event.
 */
fun rewriteMotionEvent(
    source: MotionEvent,
    downTime: Long,
    eventTime: Long,
    sourceOverride: Int,
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
        pointerCoords[i] = coords
    }
    val newEvent = MotionEvent.obtain(
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
    newEvent.source = sourceOverride
    return newEvent
}
