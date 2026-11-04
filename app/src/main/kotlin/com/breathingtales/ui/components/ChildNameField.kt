package com.breathingtales.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.breathingtales.ui.theme.Pleos
import com.breathingtales.ui.theme.PleosDimens

/** 아이 이름 입력 + 저장. 이름은 TTS로 읽으므로 12자까지 받는다 */
@Composable
fun ChildNameField(initial: String, onSave: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val canSave = text.isNotBlank()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PleosDimens.gapSmall),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(MAX_LENGTH) },
            singleLine = true,
            placeholder = { Text("예: 하늘", style = Pleos.type.bodyLargeNormal) },
            textStyle = Pleos.type.bodyLargeNormal.copy(color = Pleos.colors.textPrimary),
            shape = RoundedCornerShape(PleosDimens.buttonMediumRadius),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSave) onSave(text) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Pleos.colors.textPrimary,
                unfocusedBorderColor = Pleos.colors.divider,
                cursorColor = Pleos.colors.textPrimary,
            ),
            modifier = Modifier.weight(1f).height(PleosDimens.buttonMedium),
        )
        PleosButton("저장", onClick = { onSave(text) }, kind = ButtonKind.Filled, size = ButtonSize.Medium, enabled = canSave)
    }
}

private const val MAX_LENGTH = 12
