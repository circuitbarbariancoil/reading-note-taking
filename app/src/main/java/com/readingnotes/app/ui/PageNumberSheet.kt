package com.readingnotes.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readingnotes.app.ui.theme.Accent
import com.readingnotes.app.ui.theme.Paper
import com.readingnotes.app.ui.theme.Sumi
import com.readingnotes.app.ui.theme.SumiSoft
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Text action that triggers on pointer-down, so a tap can't be cancelled
 * by any layout shift caused by the soft keyboard.
 */
@Composable
private fun SheetActionText(
    label: String,
    color: Color,
    onPress: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = 44.dp)
            .pointerInput(enabled) {
                detectTapGestures(onPress = { if (enabled) onPress() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) color else SumiSoft.copy(alpha = 0.4f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}

data class PageSheetAction(
    val label: String,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PageNumberSheet(
    title: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    initialValue: String = "",
    confirmLabel: String = "确定",
    dismissLabel: String = "取消",
    imagePath: String? = null,
    hintText: String? = null,
    noteText: String? = null,
    confirmEnabled: (Int) -> Boolean = { true },
    secondaryActions: List<PageSheetAction> = emptyList(),
) {
    var enlarged by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var pageNumField by remember(initialValue) {
        val digits = initialValue.filter { it.isDigit() }
        mutableStateOf(TextFieldValue(digits, TextRange(digits.length)))
    }
    val pageNumText = pageNumField.text
    val parsedValue = pageNumText.toIntOrNull()
    val confirmAllowed = parsedValue != null && confirmEnabled(parsedValue)

    fun confirmIfValid() {
        val value = parsedValue ?: return
        if (confirmEnabled(value)) {
            focusManager.clearFocus()
            onConfirm(value)
        }
    }

    // Plain Dialog instead of ModalBottomSheet: the M3 sheet mis-positions its
    // touch targets while the IME animates, so taps land outside the buttons.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .imePadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(Paper)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .padding(horizontal = 20.dp, vertical = 20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Sumi)

            if (imagePath != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val bitmap = remember(imagePath) {
                        runCatching { BitmapFactory.decodeFile(imagePath) }.getOrNull()
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { enlarged = true },
                            contentScale = ContentScale.Crop,
                        )
                    }
                    if (hintText != null) {
                        Text(
                            hintText,
                            modifier = Modifier.weight(1f),
                            fontSize = 12.sp,
                            color = SumiSoft,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (noteText != null) {
                Text(noteText, fontSize = 12.sp, color = SumiSoft)
            }

            OutlinedTextField(
                value = pageNumField,
                onValueChange = { newValue -> if (newValue.text.all { c -> c.isDigit() }) pageNumField = newValue },
                label = { Text("页码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { confirmIfValid() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val useFlowRow = secondaryActions.size >= 1 && maxWidth < 320.dp
                if (useFlowRow) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        secondaryActions.forEach { action ->
                            SheetActionText(action.label, enabled = action.enabled, color = if (action.danger) Color(0xFFB3524A) else Accent, onPress = action.onClick)
                        }
                        SheetActionText(dismissLabel, color = SumiSoft, onPress = onDismiss)
                        SheetActionText(confirmLabel, enabled = confirmAllowed, color = Accent, onPress = ::confirmIfValid)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        secondaryActions.forEach { action ->
                            SheetActionText(action.label, enabled = action.enabled, color = if (action.danger) Color(0xFFB3524A) else Accent, onPress = action.onClick)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        SheetActionText(dismissLabel, color = SumiSoft, onPress = onDismiss)
                        SheetActionText(confirmLabel, enabled = confirmAllowed, color = Accent, onPress = ::confirmIfValid)
                    }
                }
            }

            Spacer(modifier = Modifier.size(8.dp))
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    if (enlarged && imagePath != null) {
        Dialog(
            onDismissRequest = { enlarged = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.82f))
                    .clickable(onClick = { enlarged = false }),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = remember(imagePath) {
                    runCatching { BitmapFactory.decodeFile(imagePath) }.getOrNull()
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}
