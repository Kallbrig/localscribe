package dev.chaseallbright.localscribe.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import dev.chaseallbright.localscribe.dictation.BubbleDismissal
import dev.chaseallbright.localscribe.dictation.DictationController
import dev.chaseallbright.localscribe.dictation.DictationUiState
import dev.chaseallbright.localscribe.feedback.FeedbackReport
import dev.chaseallbright.localscribe.settings.AppPreferences
import dev.chaseallbright.localscribe.ui.overlay.Bounds
import dev.chaseallbright.localscribe.ui.overlay.BubbleCollapse
import dev.chaseallbright.localscribe.ui.overlay.BubbleStyle
import dev.chaseallbright.localscribe.ui.overlay.CollapseDelay
import dev.chaseallbright.localscribe.ui.overlay.DISMISS_TARGET_SIZE
import dev.chaseallbright.localscribe.ui.overlay.DismissTarget
import dev.chaseallbright.localscribe.ui.overlay.DismissZone
import dev.chaseallbright.localscribe.ui.overlay.OverlayContent
import dev.chaseallbright.localscribe.ui.overlay.OverlayGeometry
import dev.chaseallbright.localscribe.ui.overlay.OverlayGestures
import dev.chaseallbright.localscribe.ui.overlay.OverlayShape
import dev.chaseallbright.localscribe.ui.overlay.PxPoint
import dev.chaseallbright.localscribe.ui.overlay.SHADOW_PADDING
import dev.chaseallbright.localscribe.ui.overlay.StarPromptCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Hosts every overlay window: the bubble (with its dot, pills and spinner), the drag-to-dismiss
 * target, and the occasional star card. Owns the collapse timer, the bubble's position, and the
 * hold-to-record gesture's lifecycle.
 *
 * Position is a single anchor -- the bubble's centre in screen pixels. Each shape is centred on it
 * and clamped into a safe area, and the window is sized explicitly to the shape plus shadow room,
 * so nothing ever grows off the screen and the bubble returns to its spot after a dictation.
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

    private lateinit var windowManager: WindowManager
    private var rootView: RawTouchFrame? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private lateinit var preferences: AppPreferences
    private var unobservePreferences: (() -> Unit)? = null
    private lateinit var style: MutableStateFlow<BubbleStyle>
    private lateinit var collapseDelay: MutableStateFlow<CollapseDelay>
    private val collapse = MutableStateFlow(BubbleCollapse())

    /** Uptime of the current hold's start, or 0 when no hold is in progress. */
    private var holdStartedAt = 0L

    private lateinit var shape: StateFlow<OverlayShape>
    private lateinit var bounds: Bounds
    private lateinit var anchor: PxPoint

    private var dismissView: ComposeView? = null
    private val overDismissTarget = MutableStateFlow(false)
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartAnchor = PxPoint(0, 0)

    private var starCardView: FrameLayout? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        preferences = AppPreferences(this)
        style = MutableStateFlow(preferences.bubbleStyle)
        collapseDelay = MutableStateFlow(preferences.collapseDelay)
        unobservePreferences = preferences.observeBubbleSettings {
            style.value = preferences.bubbleStyle
            collapseDelay.value = preferences.collapseDelay
        }
        bounds = computeBounds()
        anchor = OverlayGeometry.clampAnchor(
            INITIAL_X + px(OverlayShape.BUBBLE.widthDp) / 2, INITIAL_Y + px(OverlayShape.BUBBLE.widthDp) / 2, px(OverlayShape.BUBBLE.widthDp), bounds
        )

        // A timer may expire during a recording; the dot is only drawn when idle, so the pill
        // never changes because of it.
        val showingDot = combine(collapse, DictationController.state) { c, state ->
            c.collapsed && (state == DictationUiState.Idle || state is DictationUiState.Error)
        }
        shape = combine(DictationController.state, showingDot, DictationController.recordingIsHold) { state, dot, hold ->
            OverlayShape.of(state, dot, hold)
        }.distinctUntilChanged().stateIn(lifecycleScope, SharingStarted.Eagerly, OverlayShape.NONE)

        startForegroundWithNotification()
        addOverlayView()
        startCollectors()
    }

    private fun startForegroundWithNotification() {
        val notification: Notification = NotificationCompat.Builder(this, DICTATION_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.overlay_notification_hide), selfIntent(ACTION_HIDE_BUBBLE, 1))
            .addAction(0, getString(R.string.overlay_notification_show), selfIntent(ACTION_SHOW_BUBBLE, 2))
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    private fun selfIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, OverlayBubbleService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // The same as a drop on the X, using the configured duration, once.
            ACTION_HIDE_BUBBLE -> BubbleDismissal.dismiss(preferences)
            ACTION_SHOW_BUBBLE -> BubbleDismissal.restore(preferences)
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation changes the safe area; keep the bubble inside the new one.
        bounds = computeBounds()
        anchor = OverlayGeometry.clampAnchor(anchor.x, anchor.y, px(OverlayShape.BUBBLE.widthDp), bounds)
        applyLayout()
    }

    private fun startCollectors() {
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
        lifecycleScope.launch { shape.collect { applyLayout() } }
        // The keyboard opening or closing changes where shapes may go.
        lifecycleScope.launch { DictationController.imeTop.collect { applyLayout() } }
        lifecycleScope.launch {
            DictationController.starPromptRequests.collect {
                // Let the dictated text land before anything appears over it.
                delay(STAR_CARD_DELAY_MS)
                showStarCard()
            }
        }
    }

    // --- Layout -------------------------------------------------------------------------------

    private fun px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
    private fun px(dp: Dp): Int = px(dp.value.toInt())

    /**
     * The safe area: 16 dp in from the sides, below the status bar -- a swipe there opens the
     * notification shade -- and above the navigation bar or gesture area.
     */
    private fun computeBounds(): Bounds {
        val margin = px(EDGE_MARGIN_DP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val screen = metrics.bounds
            return Bounds(
                left = screen.left + insets.left + margin,
                top = screen.top + insets.top + margin,
                right = screen.right - insets.right - margin,
                bottom = screen.bottom - insets.bottom - margin
            )
        }
        val display = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(display)
        return Bounds(
            left = margin,
            top = systemDimension("status_bar_height") + margin,
            right = display.widthPixels - margin,
            bottom = display.heightPixels - systemDimension("navigation_bar_height") - margin
        )
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun systemDimension(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    /**
     * [bounds] with the keyboard taken out. Application overlays are drawn beneath the IME, so
     * anything placed over the keyboard is hidden behind it. The anchor itself is not moved: when
     * the keyboard closes, the bubble goes back to where the user put it.
     */
    private fun effectiveBounds(): Bounds {
        val imeTop = DictationController.imeTop.value ?: return bounds
        val bottom = imeTop - px(EDGE_MARGIN_DP)
        // A keyboard that leaves no room at all (landscape, a tall IME) is ignored rather than
        // squeezing every shape into nothing.
        return if (bottom - bounds.top < px(OverlayShape.BUBBLE.heightDp) * 2) bounds
        else bounds.copy(bottom = minOf(bounds.bottom, bottom))
    }

    /** Sizes and places the bubble window for the current shape. */
    private fun applyLayout() {
        val view = rootView ?: return
        val current = shape.value
        if (current == OverlayShape.NONE) {
            layoutParams.width = 1
            layoutParams.height = 1
            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            val pad = px(SHADOW_PADDING)
            val width = px(current.widthDp)
            val height = px(current.heightDp)
            val area = effectiveBounds()
            // The anchor is clamped into the current area too, so a bubble placed low on the
            // screen rides up above the keyboard instead of hiding behind it.
            val centre = OverlayGeometry.clampAnchor(anchor.x, anchor.y, px(OverlayShape.BUBBLE.widthDp), area)
            val topLeft = OverlayGeometry.placeCentered(centre.x, centre.y, width, height, area)
            layoutParams.x = topLeft.x - pad
            layoutParams.y = topLeft.y - pad
            layoutParams.width = width + pad * 2
            layoutParams.height = height + pad * 2
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
    }

    private fun addOverlayView() {
        layoutParams = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Screen coordinates throughout, cutout included, so bounds and raw touches agree.
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by DictationController.state.collectAsStateWithLifecycle()
                val currentShape by shape.collectAsStateWithLifecycle()
                val currentStyle by style.collectAsStateWithLifecycle()
                val gestures = remember {
                    OverlayGestures(
                        onTap = ::onTap,
                        onDragStart = ::onDragStart,
                        onDragMove = ::onDragMove,
                        onDragEnd = ::onDragEnd,
                        onHoldStart = ::onHoldStart,
                        onHoldEnd = ::onHoldEnd
                    )
                }
                MaterialTheme {
                    OverlayContent(
                        state = state,
                        shape = currentShape,
                        style = currentStyle,
                        gestures = gestures,
                        onConfirm = { sendAction(DictationForegroundService.ACTION_CONFIRM) },
                        onCancel = { sendAction(DictationForegroundService.ACTION_CANCEL) }
                    )
                }
            }
        }
        val root = RawTouchFrame(this).apply { addView(compose) }
        attachOwners(root)
        rootView = root
        windowManager.addView(root, layoutParams)
        applyLayout()
    }

    // --- Gestures -----------------------------------------------------------------------------

    private fun onTap(onDot: Boolean) {
        if (onDot) {
            collapse.update { it.reduce(BubbleCollapse.Event.Expand) }
        } else {
            sendAction(DictationForegroundService.ACTION_START)
        }
    }

    private fun onHoldStart() {
        holdStartedAt = SystemClock.uptimeMillis()
        sendAction(DictationForegroundService.ACTION_START) { putExtra(DictationForegroundService.EXTRA_HOLD, true) }
    }

    /**
     * Releasing transcribes -- unless the hold was too short to have been meant, which becomes an
     * ordinary tap recording with its buttons: transcribing a fraction of a second invites Whisper
     * to invent text. Intents to one service arrive in order, so a release that beats the recorder
     * is still applied after it starts, and a refused start makes either a no-op.
     */
    private fun onHoldEnd() {
        if (holdStartedAt == 0L) return
        val held = SystemClock.uptimeMillis() - holdStartedAt
        holdStartedAt = 0L
        sendAction(
            if (held < MIN_HOLD_MS) DictationForegroundService.ACTION_RELEASE_TO_TAP
            else DictationForegroundService.ACTION_CONFIRM
        )
    }

    private fun onDragStart() {
        collapse.update { it.reduce(BubbleCollapse.Event.DragStart) }
        val root = rootView ?: return
        // From where the finger went down, not where the drag was recognised: otherwise the
        // bubble trails the finger by the touch-slop distance for the whole drag.
        dragStartRawX = root.downRawX
        dragStartRawY = root.downRawY
        dragStartAnchor = anchor
        showDismissTarget()
    }

    /**
     * Moves by raw screen deltas since the drag began. Compose's positions are relative to this
     * window, which moves under the finger, so they drift.
     */
    private fun onDragMove() {
        val root = rootView ?: return
        anchor = OverlayGeometry.clampAnchor(
            dragStartAnchor.x + (root.rawX - dragStartRawX).toInt(),
            dragStartAnchor.y + (root.rawY - dragStartRawY).toInt(),
            px(OverlayShape.BUBBLE.widthDp),
            effectiveBounds()
        )
        applyLayout()
        val over = isOverDismissTarget()
        if (over && !overDismissTarget.value) haptic(entering = true)
        overDismissTarget.value = over
    }

    private fun onDragEnd(dropped: Boolean) {
        val dismissed = dropped && overDismissTarget.value
        hideDismissTarget()
        if (dismissed) {
            haptic(entering = false)
            // Back where the drag began, so the next field it appears for does not find it parked
            // on top of the keyboard at the bottom of the screen.
            anchor = dragStartAnchor
            applyLayout()
            BubbleDismissal.dismiss(preferences)
        }
        collapse.update { it.reduce(BubbleCollapse.Event.DragEnd) }
    }

    /** A tick on first reaching the X, a firmer confirm on the drop. Honours the system setting. */
    private fun haptic(entering: Boolean) {
        val constant = when {
            entering -> HapticFeedbackConstants.CLOCK_TICK
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> HapticFeedbackConstants.CONFIRM
            else -> HapticFeedbackConstants.LONG_PRESS
        }
        rootView?.performHapticFeedback(constant)
    }

    private fun isOverDismissTarget(): Boolean {
        val (targetX, targetY) = dismissTargetCenter()
        return DismissZone.isOver(
            bubbleCenterX = anchor.x.toFloat(),
            bubbleCenterY = anchor.y.toFloat(),
            targetCenterX = targetX,
            targetCenterY = targetY,
            radiusPx = px(DISMISS_TARGET_SIZE).toFloat()
        )
    }

    /**
     * Bottom centre of the area the bubble may occupy -- above the keyboard when one is open, since
     * the keyboard would otherwise hide the target. Computed, not measured, in the same screen
     * coordinates as the anchor.
     */
    private fun dismissTargetCenter(): Pair<Float, Float> {
        val area = effectiveBounds()
        return (area.left + area.width / 2f) to (area.bottom - px(DISMISS_TARGET_SIZE) / 2f)
    }

    private fun showDismissTarget() {
        if (dismissView != null) return
        val size = px(DISMISS_TARGET_SIZE)
        val (centreX, centreY) = dismissTargetCenter()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Never touchable: it is a drop zone judged by position, and must not swallow the drag.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Same coordinate space as the bubble window, so the hit test and the drawing agree.
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            x = (centreX - size / 2f).toInt()
            y = (centreY - size / 2f).toInt()
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
        runCatching { windowManager.addView(view, params) }.onFailure { dismissView = null }
    }

    private fun hideDismissTarget() {
        dismissView?.let { runCatching { windowManager.removeView(it) } }
        dismissView = null
        overDismissTarget.value = false
    }

    // --- Star card ----------------------------------------------------------------------------

    private fun showStarCard() {
        if (starCardView != null) return
        val policy = preferences.starPrompt
        if (!policy.due) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable, so the keyboard and the field underneath keep working. Tapping outside
            // does not close it: the card arrives just as the user reaches for Send, and a touch
            // there would dismiss it unread. It waits for one of its three answers.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MaterialTheme {
                    StarPromptCard(
                        uses = policy.uses,
                        onTakeMeThere = {
                            preferences.starPrompt = preferences.starPrompt.finish()
                            hideStarCard()
                            openRepo()
                        },
                        onRemindLater = { remindLaterAndClose() },
                        onDontRemind = {
                            preferences.starPrompt = preferences.starPrompt.finish()
                            hideStarCard()
                        }
                    )
                }
            }
        }
        val frame = FrameLayout(this).apply { addView(compose) }
        attachOwners(frame)
        starCardView = frame
        runCatching { windowManager.addView(frame, params) }.onFailure { starCardView = null }
    }

    private fun remindLaterAndClose() {
        if (starCardView == null) return
        preferences.starPrompt = preferences.starPrompt.remindLater()
        hideStarCard()
    }

    private fun hideStarCard() {
        starCardView?.let { runCatching { windowManager.removeView(it) } }
        starCardView = null
    }

    /** LocalScribe makes no request itself: the browser opens the page. */
    private fun openRepo() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(FeedbackReport.REPO_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "No browser found. The project is at ${FeedbackReport.REPO_URL}", Toast.LENGTH_LONG).show()
        }
    }

    // --- Plumbing -----------------------------------------------------------------------------

    private fun attachOwners(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
    }

    private fun sendAction(action: String, extras: Intent.() -> Unit = {}) {
        val intent = Intent(this, DictationForegroundService::class.java).setAction(action).apply(extras)
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
        hideStarCard()
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    /** Records each touch's raw screen position before Compose sees it. */
    private class RawTouchFrame(context: Context) : FrameLayout(context) {
        var rawX = 0f
            private set
        var rawY = 0f
            private set
        var downRawX = 0f
            private set
        var downRawY = 0f
            private set

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            rawX = ev.rawX
            rawY = ev.rawY
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                downRawX = ev.rawX
                downRawY = ev.rawY
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    companion object {
        const val ACTION_HIDE_BUBBLE = "dev.chaseallbright.localscribe.action.HIDE_BUBBLE"
        const val ACTION_SHOW_BUBBLE = "dev.chaseallbright.localscribe.action.SHOW_BUBBLE"

        private const val NOTIFICATION_ID = 1002
        private const val EDGE_MARGIN_DP = 16
        private const val INITIAL_X = 100
        private const val INITIAL_Y = 300
        private const val STAR_CARD_DELAY_MS = 700L

        /** A hold released sooner than this after recording began is treated as a tap. */
        private const val MIN_HOLD_MS = 400L
    }
}
