package zenith.app

import android.app.Presentation
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Draws the car screen: the host's surface backs a private virtual display, on which a
 * [Presentation] shows [CarDashboard] in Compose, map included, the same code as the phone's.
 * Rebuilt whenever the host hands a new surface (size or density change); paused, map included,
 * while another app fills the car screen. Any failure leaves the screen black, never a crash. Main
 * thread only, like every host callback.
 */
class CarSurface(private val context: Context, private val hint: StateFlow<String>) : SurfaceCallback {
    /** What the host's own buttons never cover, in pixels; empty until the host says. */
    private val stableArea = MutableStateFlow(Rect())
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var view: ComposeView? = null
    private var owner: SurfaceOwner? = null
    private var shown = true

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        release()
        val surface = surfaceContainer.surface ?: return
        if (surfaceContainer.width <= 0 || surfaceContainer.height <= 0) return
        runCatching {
            val virtual = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                "Zenith",
                surfaceContainer.width,
                surfaceContainer.height,
                surfaceContainer.dpi,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
            display = virtual
            val screen = Presentation(context, virtual.display)
            presentation = screen
            // Compose needs a lifecycle and a saved state from the window it lives in: here, ours.
            val lifecycleOwner = SurfaceOwner().also { owner = it }
            val content = ComposeView(screen.context).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setContent { CarDashboard(stableArea, hint) }
            }
            view = content
            screen.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
            screen.setContentView(content)
            screen.window?.decorView?.let {
                it.setViewTreeLifecycleOwner(lifecycleOwner)
                it.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            }
            lifecycleOwner.moveTo(if (shown) Lifecycle.State.RESUMED else Lifecycle.State.CREATED)
            screen.show()
        }.onFailure {
            Log.w("Zenith", "car surface", it)
            release()
        }
    }

    /** Zenith's screen in front on the car, or behind another app: the map and animations rest meanwhile. */
    fun shown(visible: Boolean) {
        shown = visible
        owner?.moveTo(if (visible) Lifecycle.State.RESUMED else Lifecycle.State.CREATED)
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        this.stableArea.value = Rect(stableArea)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = release()

    /** Ends the composition first (the map lets go of its view), then the window and the display. */
    fun release() {
        runCatching { view?.disposeComposition() }
        owner?.moveTo(Lifecycle.State.DESTROYED)
        runCatching { presentation?.dismiss() }
        runCatching { display?.release() }
        view = null
        owner = null
        presentation = null
        display = null
    }
}

/** Lifecycle and saved state of the car window: resumed while the surface lives, destroyed with it. */
private class SurfaceOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this).apply { performRestore(null) }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun moveTo(state: Lifecycle.State) {
        // A lifecycle never created cannot be destroyed.
        if (state == Lifecycle.State.DESTROYED && registry.currentState == Lifecycle.State.INITIALIZED) return
        registry.currentState = state
    }
}
