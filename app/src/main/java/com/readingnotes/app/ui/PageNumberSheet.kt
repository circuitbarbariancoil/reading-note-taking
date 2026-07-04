package com.readingnotes.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readingnotes.app.ui.theme.Accent
import com.readingnotes.app.ui.theme.Paper
import com.readingnotes.app.ui.theme.Sumi
import com.readingnotes.app.ui.theme.SumiSoft
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class PageSheetAction(
    val label: String,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var enlarged by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var pageNumText by remember(initialValue) { mutableStateOf(initialValue.filter { it.isDigit() }) }
    val parsedValue = pageNumText.toIntOrNull()
    val confirmAllowed = parsedValue != null && confirmEnabled(parsedValue)

    fun confirmIfValid() {
        val value = parsedValue ?: return
        if (confirmEnabled(value)) {
            focusManager.clearFocus()
            onConfirm(value)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Paper,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
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
                value = pageNumText,
                onValueChange = { pageNumText = it.filter { c -> c.isDigit() } },
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
                            TextButton(onClick = action.onClick, enabled = action.enabled) {
                                Text(action.label, color = if (action.danger) Color(0xFFB3524A) else Accent)
                            }
                        }
                        TextButton(onClick = onDismiss) { Text(dismissLabel, color = SumiSoft) }
                        TextButton(onClick = ::confirmIfValid, enabled = confirmAllowed) { Text(confirmLabel, color = Accent) }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        secondaryActions.forEach { action ->
                            TextButton(onClick = action.onClick, enabled = action.enabled) {
                                Text(action.label, color = if (action.danger) Color(0xFFB3524A) else Accent)
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text(dismissLabel, color = SumiSoft) }
                        TextButton(onClick = ::confirmIfValid, enabled = confirmAllowed) { Text(confirmLabel, color = Accent) }
                    }
                }
            }

            Spacer(modifier = Modifier.size(8.dp))
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
