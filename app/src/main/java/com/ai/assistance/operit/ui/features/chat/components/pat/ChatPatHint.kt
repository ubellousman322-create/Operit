package com.ai.assistance.operit.ui.features.chat.components.pat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 聊天页中间那行小字：谁拍了拍谁。
 *
 * 不算气泡，没有头像，不能长按，就是一句摆在中间的话。
 */
@Composable
fun ChatPatHint(text: String, modifier: Modifier = Modifier) {
    Text(
            text = text,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp),
    )
}