package io.github.nitsuya.aa.display.util

import android.view.MotionEvent

/**
 * Rebuild a [MotionEvent] with rewritten timestamps / touchscreen source.
 * Callers own recycle of the returned event.
 *
 * Pointer property/coord arrays are ThreadLocal-reused to avoid per-event allocation
 * on the AA touch → Binder inject hot path.
 */
fun rewriteMotionEvent(
    source: MotionEvent,
    downTime: Long,
    eventTime: Long,
    sourceOverride: Int,
): MotionEvent {
    val count = source.pointerCount
    val buffers = pointerBuffers.get()!!
    buffers.ensure(count)
    for (i in 0 until count) {
        source.getPointerProperties(i, buffers.props[i])
        source.getPointerCoords(i, buffers.coords[i])
    }
    val newEvent = MotionEvent.obtain(
        downTime,
        eventTime,
        source.action,
        count,
        buffers.props,
        buffers.coords,
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

private class PointerBuffers {
    var props = Array(8) { MotionEvent.PointerProperties() }
    var coords = Array(8) { MotionEvent.PointerCoords() }

    fun ensure(count: Int) {
        if (props.size >= count) return
        val n = count.coerceAtLeast(props.size * 2)
        props = Array(n) { i -> if (i < props.size) props[i] else MotionEvent.PointerProperties() }
        coords = Array(n) { i -> if (i < coords.size) coords[i] else MotionEvent.PointerCoords() }
    }
}

private val pointerBuffers = ThreadLocal.withInitial { PointerBuffers() }
