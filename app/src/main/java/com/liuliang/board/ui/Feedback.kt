package com.liuliang.board.ui

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

enum class FeedbackKind { TAP, TOGGLE, CONFIRM, REJECT }

/** Uses Android's system-controlled feedback; no VIBRATE permission or ignore-setting flags. */
class BoardFeedback(
    private val enabled: () -> Boolean,
    private val perform: (Int) -> Boolean,
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val sdk: Int = Build.VERSION.SDK_INT,
) {
    private var lastFeedback: Long? = null
    private var lastKind: FeedbackKind? = null
    @ChecksSdkIntAtLeast(api = 30)
    private fun supportsConfirmEffects(): Boolean = sdk >= 30
    fun emit(kind: FeedbackKind = FeedbackKind.TAP) {
        if (!enabled()) return
        val now = clock()
        if (lastFeedback?.let { now - it in 0..79 } == true && (kind == FeedbackKind.TAP || kind == FeedbackKind.TOGGLE || kind == lastKind)) return
        val effect = when (kind) {
            FeedbackKind.TAP -> HapticFeedbackConstants.CONTEXT_CLICK
            FeedbackKind.TOGGLE -> HapticFeedbackConstants.CLOCK_TICK
            FeedbackKind.CONFIRM -> if (supportsConfirmEffects()) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
            FeedbackKind.REJECT -> if (supportsConfirmEffects()) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        }
        if (perform(effect)) { lastFeedback = now; lastKind = kind }
    }
}

val LocalBoardFeedback = staticCompositionLocalOf { BoardFeedback({ false }, { false }) }

@Composable fun feedbackClick(onClick: () -> Unit): () -> Unit {
    val feedback = LocalBoardFeedback.current
    return { feedback.emit(); onClick() }
}

@Composable fun HapticButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape, content: @Composable RowScope.() -> Unit) {
    Button(onClick = feedbackClick(onClick), modifier = modifier, enabled = enabled, shape = shape, content = content)
}
@Composable fun HapticOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape, content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick = feedbackClick(onClick), modifier = modifier, enabled = enabled, shape = shape, content = content)
}
@Composable fun HapticTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    TextButton(onClick = feedbackClick(onClick), modifier = modifier, enabled = enabled, content = content)
}
@Composable fun HapticIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable () -> Unit) {
    IconButton(onClick = feedbackClick(onClick), modifier = modifier, enabled = enabled, content = content)
}
@Composable fun HapticSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val feedback = LocalBoardFeedback.current
    Switch(checked, onCheckedChange = { feedback.emit(FeedbackKind.TOGGLE); onCheckedChange(it) }, enabled = enabled, modifier = modifier)
}
