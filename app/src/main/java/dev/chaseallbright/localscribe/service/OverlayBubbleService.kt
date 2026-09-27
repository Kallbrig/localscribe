package dev.chaseallbright.localscribe.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.chaseallbright.localscribe.DICTATION_NOTIFICATION_CHANNEL_ID
import dev.chaseallbright.localscribe.R
import dev.chaseallbright.localscribe.dictation.DictationController
import dev.chaseallbright.localscribe.dictation.DictationUiState
import dev.chaseallbright.localscribe.settings.AppPreferences
import dev.chaseallbright.localscribe.ui.overlay.BUBBLE_SIZE
import dev.chaseallbright.localscribe.ui.overlay.BubbleCollapse
import dev.chaseallbright.localscribe.ui.overlay.BubbleStyle
import dev.chaseallbright.localscribe.ui.overlay.CollapseDelay
import dev.chaseallbright.localscribe.ui.overlay.DISMISS_TARGET_SIZE
import dev.chaseallbright.localscribe.ui.overlay.DOT_TOUCH_SIZE
import dev.chaseallbright.localscribe.ui.overlay.DismissTarget
import dev.chaseallbright.localscribe.ui.overlay.DismissZone
import dev.chaseallbright.localscribe.ui.overlay.DragHandlers
import dev.chaseallbright.localscribe.ui.overlay.OverlayContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Draggable idle bubble + recording/processing pill, hosted directly in a WindowManager overlay.
 * Also owns the bubble's collapse timer and the drag-to-dismiss target.
 */
class OverlayBubbleService :
    Service(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore = ViewModelStore()

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private lateinit var preferences: AppPreferences
    private var unobservePreferences: (() -> Unit)? = null
    private lateinit var style: MutableStateFlow<BubbleStyle>
    private lateinit var collapseDelay: MutableStateFlow<CollapseDelay>
    private val collapse = MutableStateFlow(BubbleCollapse())

    /**
     * True when the dot is what is actually on screen. The collapse state alone is not enough:
     * a timer may expire during a recording, and the pill must not jump because of it.
     */
    private lateinit var showingDot: StateFlow<Boolean>

    private var dismissView: ComposeView? = null
    private val overDismissTarget = MutableStateFlow(false)
    private var dragStartScreen = IntArray(2)
    private var dragStartX = 0
    private var dragStartY = 0
    private var dismissTargetCenter: Pair<Float, Float>? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        preferences = AppPreferences(this)
        style = MutableStateFlow(preferences.bubbleStyle)
        collapseDelay = MutableStateFlow(preferences.collapseDelay)
        unobservePreferences = preferences.observeBubbleSettings {
            style.value = preferences.bubbleStyle
            collapseDelay.value = preferences.collapseDelay
        }
        showingDot = combine(collapse, DictationController.state) { c, state ->
            c.collapsed && (state == DictationUiState.Idle || state is DictationUiState.Error)
        }.distinctUntilChanged().stateIn(lifecycleScope, SharingStarted.Eagerly, false)
        startForegroundWithNotification()
        addOverlayView()
        startCollapseTimer()
    }

    private fun startForegroundWithNotification() {
        val notification: Notification = NotificationCompat.Builder(this, DICTATION_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startCollapseTimer() {
        lifecycleScope.launch {
            DictationController.bubbleWake.collect {
                collapse.update { it.reduce(BubbleCollapse.Event.Wake) }
            }
        }
        lifecycleScope.launch {
            // collectLatest cancels the pending delay whenever the generation or the setting
            // changes, and the generation check in the reducer drops anything that slips past.
            combine(collapse, collapseDelay) { c, d -> c.generation to c.timerMillis(d) }
                .distinctUntilChanged()
                .collectLatest { (generation, millis) ->
                    if (millis == null) return@collectLatest
                    delay(millis)
                    collapse.update { it.reduce(BubbleCollapse.Event.TimerExpired(generation)) }
                }
        }
        lifecycleScope.launch {
            // The window is WRAP_CONTENT, so it shrinks from its top-left corner. Shift it by half
            // the size difference so the dot sits where the bubble's centre was, and back again.
            val offsetPx = ((BUBBLE_SIZE - DOT_TOUCH_SIZE).value * resources.displayMetrics.density / 2).toInt()
            var wasDot = showingDot.value
            showingDot.collect { dot ->
                if (dot == wasDot) return@collect
                wasDot = dot
                val params = layoutParams ?: return@collect
                val shift = if (dot) offsetPx else -offsetPx
                params.x += shift
                params.y += shift
                // A wake mid-drag (another field took focus) can land here. Move the drag's
                // reference point with the window, or the hit test and the post-dismiss restore
                // are both off by the shift.
                if (collapse.value.dragging) {
                    dragStartX += shift
                    dragStartY += shift
                    dragStartScreen[0] += shift
                    dragStartScreen[1] += shift
                }
                composeView?.let { runCatching { windowManager?.updateViewLayout(it, params) } }
            }
        }
    }

    private fun addOverlayView() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }
        layoutParams = params

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by DictationController.state.collectAsStateWithLifecycle()
                val currentStyle by style.collectAsStateWithLifecycle()
                val dot by showingDot.collectAsStateWithLifecycle()
                val drag = remember {
                    DragHandlers(
                        onDragStart = ::onDragStart,
                        onDrag = { dx, dy -> onDrag(dx, dy) },
                        onDragEnd = { onDragEnd(dropped = true) },
                        onDragCancel = { onDragEnd(dropped = false) }
                    )
                }
                MaterialTheme {
                    OverlayContent(
                        state = state,
                        style = currentStyle,
                        collapsed = dot,
                        drag = drag,
                        onTapBubble = { sendAction(DictationForegroundService.ACTION_START) },
                        onTapDot = { collapse.update { it.reduce(BubbleCollapse.Event.Expand) } },
                        onConfirm = { sendAction(DictationForegroundService.ACTION_CONFIRM) },
                        onCancel = { sendAction(DictationForegroundService.ACTION_CANCEL) }
                    )
                }
            }
        }
        attachOwners(view)

        composeView = view
        wm.addView(view, params)
    }

    private fun onDragStart() {
        collapse.update { it.reduce(BubbleCollapse.Event.DragStart) }
        val params = layoutParams ?: return
        composeView?.getLocationOnScreen(dragStartScreen)
        dragStartX = params.x
        dragStartY = params.y
        showDismissTarget()
    }

    private fun onDrag(dx: Float, dy: Float) {
        val params = layoutParams ?: return
        val view = composeView ?: return
        params.x += dx.toInt()
        params.y += dy.toInt()
        runCatching { windowManager?.updateViewLayout(view, params) }
        overDismissTarget.value = isOverDismissTarget(params, view)
    }

    private fun onDragEnd(dropped: Boolean) {
        val dismissed = dropped && overDismissTarget.value
        hideDismissTarget()
        if (dismissed) {
            // Put the bubble back where the drag began, so the next field it appears for does not
            // find it parked on top of the keyboard at the bottom of the screen.
            layoutParams?.let { params ->
                params.x = dragStartX
                params.y = dragStartY
                composeView?.let { runCatching { windowManager?.updateViewLayout(it, params) } }
            }
            // Only the idle bubble can be dragged, but check rather than assume: hiding a live
            // recording pill would strand the user with no way to confirm or cancel.
            val state = DictationController.state.value
            if (state == DictationUiState.Idle || state is DictationUiState.Error) {
                DictationController.setState(DictationUiState.Hidden)
            }
        }
        collapse.update { it.reduce(BubbleCollapse.Event.DragEnd) }
    }

    /**
     * The bubble's screen position is derived from where it was when the drag began plus how far
     * the window has moved since, rather than re-measured: getLocationOnScreen lags a frame behind
     * updateViewLayout. The target is measured directly, since it does not move.
     */
    private fun isOverDismissTarget(params: WindowManager.LayoutParams, view: View): Boolean {
        val (targetX, targetY) = dismissTargetCenter() ?: return false
        return DismissZone.isOver(
            bubbleCenterX = dragStartScreen[0] + (params.x - dragStartX) + view.width / 2f,
            bubbleCenterY = dragStartScreen[1] + (params.y - dragStartY) + view.height / 2f,
            targetCenterX = targetX,
            targetCenterY = targetY,
            radiusPx = DISMISS_TARGET_SIZE.value * resources.displayMetrics.density
        )
    }

    /** Measured once the target has been laid out, then cached: it does not move while shown. */
    private fun dismissTargetCenter(): Pair<Float, Float>? {
        dismissTargetCenter?.let { return it }
        val target = dismissView ?: return null
        if (target.width == 0) return null
        val screen = IntArray(2).also { target.getLocationOnScreen(it) }
        return (screen[0] + target.width / 2f to screen[1] + target.height / 2f)
            .also { dismissTargetCenter = it }
    }

    private fun showDismissTarget() {
        if (dismissView != null) return
        val wm = windowManager ?: return
        val density = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Never touchable: it is a drop zone judged by position, and must not swallow the drag.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (DISMISS_BOTTOM_MARGIN_DP * density).toInt()
        }
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val highlighted by overDismissTarget.collectAsStateWithLifecycle()
                MaterialTheme {
                    // Fixed-size box so the highlight's growth does not move the target's centre.
                    Box(Modifier.size(DISMISS_TARGET_SIZE), contentAlignment = Alignment.Center) {
                        DismissTarget(highlighted = highlighted)
                    }
                }
            }
        }
        attachOwners(view)
        dismissView = view
        runCatching { wm.addView(view, params) }.onFailure { dismissView = null }
    }

    private fun hideDismissTarget() {
        dismissView?.let { runCatching { windowManager?.removeView(it) } }
        dismissView = null
        dismissTargetCenter = null
        overDismissTarget.value = false
    }

    private fun attachOwners(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
    }

    private fun sendAction(action: String) {
        val intent = Intent(this, DictationForegroundService::class.java).setAction(action)
        if (action == DictationForegroundService.ACTION_START) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    override fun onDestroy() {
        unobservePreferences?.invoke()
        unobservePreferences = null
        hideDismissTarget()
        composeView?.let { runCatching { windowManager?.removeView(it) } }
        composeView = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private companion object {
        const val NOTIFICATION_ID = 1002
        const val DISMISS_BOTTOM_MARGIN_DP = 96
    }
}
