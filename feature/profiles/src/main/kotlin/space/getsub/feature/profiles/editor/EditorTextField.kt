// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import space.getsub.core.ui.theme.mono
import space.getsub.feature.profiles.R

/**
 * The text field the WireGuard, HTTP, mKCP and HTTPUpgrade sections share.
 *
 * [secret] masks the value (keys, the mKCP seed) with a Show/Hide button, and asks the keyboard not to learn
 * or suggest it. [error] and [hint] are already-resolved fixed text, never typed content (ARCHITECTURE.md §5.6).
 */
// Same baseline as EditorField's parameters. DEPRECATION: the replacement (OutlinedSecureTextField) owns a
// TextFieldState, but every field in this form is a hoisted String in [EditorState] so a half-typed value
// survives; PasswordVisualTransformation masks that model correctly.
@Suppress("LongParameterList", "DEPRECATION")
@Composable
internal fun EditorTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    mono: Boolean = false,
    singleLine: Boolean = true,
    secret: Boolean = false,
    error: String? = null,
    hint: String? = null,
) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    val masked = secret && !revealed
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        isError = error != null,
        supportingText = (error ?: hint)?.let { { Text(it) } },
        trailingIcon =
        if (secret) {
            {
                TextButton(onClick = { revealed = !revealed }) {
                    Text(stringResource(if (revealed) R.string.editor_secret_hide else R.string.editor_secret_show))
                }
            }
        } else {
            null
        },
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType),
        textStyle = MaterialTheme.typography.bodyLarge.let { if (mono) it.mono() else it },
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    )
}
