package com.tzvi.kickoff.feature.auth

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffShapes
import com.tzvi.kickoff.ui.theme.KickoffTheme

/**
 * A six-digit code, drawn as six boxes.
 *
 * One real text field underneath, invisible, holding the whole code; the boxes over it are
 * a rendering of that string. That is the arrangement worth having: six separate fields
 * mean six focus states to chase, a backspace that has to guess which box to empty, and an
 * autofilled code that arrives as one string and has nowhere to go. Here a pasted or
 * autofilled code just lands, and backspace behaves the way backspace behaves.
 *
 * The caret is drawn rather than left to the platform - it is the outline of the box that
 * is waiting, which is what tells you where the next digit goes.
 */
@Composable
internal fun OtpField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    length: Int = MIN_CODE_LENGTH,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }

    // Selection pinned to the end, always. Without it, tapping the row would drop the
    // caret wherever the invisible field was touched and the next digit would land in the
    // middle of the code.
    val field = TextFieldValue(text = value, selection = TextRange(value.length))

    Box(modifier = modifier) {
        BasicTextField(
            value = field,
            onValueChange = { next ->
                onValueChange(next.text.filter(Char::isDigit).take(length))
            },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            // Invisible rather than absent: it is the thing holding the text, taking the
            // keyboard and receiving the autofilled code.
            cursorBrush = SolidColor(Color.Transparent),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Transparent),
            modifier = Modifier
                .matchParentSize()
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                // Android and Autofill both know what a one-time code is; saying so is
                // what lets the keyboard offer the code straight from the email.
                .semantics { contentType = ContentType.SmsOtpCode },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BOX_HEIGHT)
                .focusable(false),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(length) { index ->
                val digit = value.getOrNull(index)
                // The box the next digit will land in, so the row shows where it is up to.
                val isNext = focused && index == value.length.coerceAtMost(length - 1) &&
                    value.length < length

                val border by animateColorAsState(
                    targetValue = when {
                        isNext -> MaterialTheme.colorScheme.primary
                        digit != null -> MaterialTheme.colorScheme.outlineVariant
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    animationSpec = Motion.effects(Motion.Duration.SHORT),
                    label = "otp-border",
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .size(BOX_HEIGHT)
                        .clip(KickoffShapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(
                            width = if (isNext) 2.dp else 1.dp,
                            color = border,
                            shape = KickoffShapes.small,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (digit != null) {
                        Text(
                            text = digit.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }

    // The code screen exists to be typed into; anything else is a tap the user should not
    // have had to make.
    androidx.compose.runtime.LaunchedEffect(enabled) {
        if (enabled) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
}

private val BOX_HEIGHT = 56.dp

@Preview(name = "OTP - part typed")
@Composable
private fun OtpFieldPreview() {
    KickoffTheme {
        OtpField(value = "204", onValueChange = {}, onSubmit = {})
    }
}

@Preview(name = "OTP - empty")
@Composable
private fun OtpFieldEmptyPreview() {
    KickoffTheme {
        OtpField(value = "", onValueChange = {}, onSubmit = {})
    }
}
