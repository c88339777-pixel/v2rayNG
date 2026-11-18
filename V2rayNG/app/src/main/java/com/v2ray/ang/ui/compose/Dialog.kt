package com.v2ray.ang.ui.compose

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

@Composable
fun ConfirmDialog(
    title: String? = null,
    message: String,
    confirmText: String = stringResource(R.string.action_ok),
    dismissText: String? = stringResource(R.string.action_cancel),
    confirmIcon: @Composable (() -> Unit)? = null,
    messageTextAlign: TextAlign = TextAlign.Start,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val confirmFocusRequester = remember { FocusRequester() }
    val dismissFocusRequester = remember { FocusRequester() }

    LaunchedEffect(dismissText) {
        if (dismissText != null) dismissFocusRequester.requestFocus()
        else confirmFocusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = title?.let {
            {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = messageTextAlign
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                modifier = Modifier.focusRequester(confirmFocusRequester)
            ) {
                confirmIcon?.invoke()
                if (confirmIcon != null) Spacer(Modifier.width(8.dp))
                Text(
                    confirmText,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = dismissText?.let { text ->
            {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(dismissFocusRequester)
                ) {
                    Text(
                        text,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun DeleteConfirmDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    itemName: String? = null,
) {
    ConfirmDialog(
        message = if (itemName.isNullOrBlank()) message else "$message\n\n$itemName",
        messageTextAlign = TextAlign.Center,
        confirmText = stringResource(R.string.action_delete),
        confirmIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_delete_24dp),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error
            )
        },
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

data class InputField(
    val label: String,
    val value: String,
    val singleLine: Boolean = true,
    val visualTransformation: VisualTransformation = VisualTransformation.None
)

@Composable
fun InputDialog(
    title: String,
    fields: List<InputField>,
    onFieldChange: (Int, String) -> Unit,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                fields.forEachIndexed { index, field ->
                    OutlinedTextField(
                        value = field.value,
                        onValueChange = { onFieldChange(index, it) },
                        label = {
                            Text(
                                field.label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        singleLine = field.singleLine,
                        maxLines = 5,
                        visualTransformation = field.visualTransformation,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            cursorColor = MaterialTheme.colorScheme.primary,
                            selectionColors = TextSelectionColors(
                                handleColor = MaterialTheme.colorScheme.primary,
                                backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            )
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmText,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    dismissText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun QRCodeDialog(
    bitmap: Bitmap?,
    onDismiss: () -> Unit
) {
    if (bitmap == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.acc_qr_code),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.action_close),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun <T> SelectListDialog(
    options: List<T>,
    optionText: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
    selectedOption: T? = null,
    showRadio: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title?.let {
            {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            LazyColumn {
                items(options) { option ->
                    val isSelected = option == selectedOption

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (showRadio) {
                                    Modifier.selectable(
                                        selected = isSelected,
                                        onClick = { onSelected(option) },
                                        role = Role.RadioButton
                                    )
                                } else {
                                    Modifier.clickable {
                                        onSelected(option)
                                    }
                                }
                            )
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showRadio) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary,
                                    unselectedColor = MaterialTheme.colorScheme.outline
                                )
                            )

                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        Text(
                            text = optionText(option),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.action_cancel),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}
