package devs.org.ultrafocus.utils

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * A minimal LifecycleOwner we drive by hand so CameraX can be bound
 * inside a plain Service (which has no lifecycle of its own).
 *
 * Must be started/destroyed on the main thread — LifecycleRegistry
 * enforces this.
 */
class SimpleLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    fun markStarted() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    fun markDestroyed() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        // Walk back through events so observers tear down cleanly
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
