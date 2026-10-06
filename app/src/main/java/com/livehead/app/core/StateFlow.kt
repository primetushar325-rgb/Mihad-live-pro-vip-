package com.livehead.app.core

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tiny, dependency-free re-implementation of the StateFlow idea.
 *
 * The app is intentionally zero-dependency (no Kotlin coroutines library, no
 * AndroidX), so hot state (engine stats, UI state) flows through this class.
 * It keeps the same mental model as StateFlow:
 *
 *   - a single current [value] that is always readable;
 *   - collectors get the current value immediately on subscribe;
 *   - every subsequent update is delivered to all collectors.
 *
 * Updates are applied atomically; collectors are invoked synchronously on the
 * writer's thread — UI collectors wrap their callback in a main-thread handler.
 */
class StateFlow<T>(initialValue: T) {

    private val listeners = CopyOnWriteArrayList<(T) -> Unit>()
    private val lock = Any()

    @Volatile
    var value: T = initialValue
        private set

    /** Subscribes; returns an unsubscribe function. The current value is NOT
     *  delivered by this call — use [subscribeWithCurrent]. */
    fun subscribe(listener: (T) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /** Subscribes and immediately delivers the current value on the caller thread. */
    fun subscribeWithCurrent(listener: (T) -> Unit): () -> Unit {
        listener(value)
        return subscribe(listener)
    }

    fun set(newValue: T) {
        synchronized(lock) {
            if (newValue == value) return
            value = newValue
        }
        for (l in listeners) {
            try {
                l(newValue)
            } catch (t: Throwable) {
                // A broken collector must never take down the engine thread.
            }
        }
    }
}
