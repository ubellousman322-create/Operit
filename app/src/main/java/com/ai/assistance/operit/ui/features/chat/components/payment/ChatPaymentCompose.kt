package com.ai.assistance.operit.ui.features.chat.components.payment

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.data.model.ChatPaymentMarkup

private val PacketRedTop = Color(0xFFF5574E)
private val PacketRedBottom = Color(0xFFE33B31)
private val TransferGreen = Color(0xFF07C160)

/**
 * 发红包。样式照着微信那个红包页：上面是红包封面，中间写金额和留言，
 * 底下一颗大红按钮"塞钱进红包"。
 *
 * [onSend] 回调带四样东西：金额、封面留言、要不要附带一条正文、附带的那条正文。
 */
@Composable
fun RedPacketSendDialog(
        visible: Boolean,
        onDismiss: () -> Unit,
        onSend: (amount: String, greeting: String, attachMessage: Boolean, extraText: String) -> Unit,
) {
    if (!visible) return

    var amount by remember { mutableStateOf("") }
    var greeting by remember { mutableStateOf(ChatPaymentMarkup.DEFAULT_GREETING) }
    var attachMessage by remember { mutableStateOf(false) }
    var extraText by remember { mutableStateOf("") }

    val normalized = ChatPaymentMarkup.normalizeAmount(amount)
    val canSend = normalized != null

    Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 红包封面预览
                Box(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .background(
                                                Brush.verticalGradient(
                                                        listOf(PacketRedTop, PacketRedBottom)
                                                )
                                        )
                                        .padding(horizontal = 20.dp, vertical = 22.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                    modifier =
                                            Modifier.size(34.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0x33FFFFFF)),
                                    contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                        imageVector = Icons.Default.Redeem,
                                        contentDescription = null,
                                        tint = Color(0xFFFFD9A0),
                                        modifier = Modifier.size(19.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                    text = "我的红包",
                                    color = Color(0xE6FFFFFF),
                                    fontSize = 13.sp,
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                                text = greeting.ifBlank { ChatPaymentMarkup.DEFAULT_GREETING },
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 24.sp,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                                text = if (normalized != null) "¥$normalized" else "¥0.00",
                                color = Color(0xFFFFE7B8),
                                fontSize = 26.sp,
                                fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                    PaymentFieldRow(label = "金额") {
                        BasicTextField(
                                value = amount,
                                onValueChange = { input ->
                                    amount = sanitizeAmountInput(input, allowDecimal = true)
                                },
                                singleLine = true,
                                textStyle =
                                        TextStyle(
                                                fontSize = 17.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                        ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner ->
                                    if (amount.isEmpty()) {
                                        Text(
                                                text = "0.00",
                                                fontSize = 17.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    inner()
                                },
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    PaymentFieldRow(label = "留言") {
                        BasicTextField(
                                value = greeting,
                                onValueChange = { greeting = it.take(30) },
                                singleLine = true,
                                textStyle =
                                        TextStyle(
                                                fontSize = 15.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                        ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner ->
                                    if (greeting.isEmpty()) {
                                        Text(
                                                text = ChatPaymentMarkup.DEFAULT_GREETING,
                                                fontSize = 15.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    inner()
                                },
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "同时发一条消息", fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                    text = "关掉就只发红包，不带话",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = attachMessage, onCheckedChange = { attachMessage = it })
                    }

                    if (attachMessage) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth(),
                        ) {
                            BasicTextField(
                                    value = extraText,
                                    onValueChange = { extraText = it.take(300) },
                                    textStyle =
                                            TextStyle(
                                                    fontSize = 14.sp,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                            ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    decorationBox = { inner ->
                                        if (extraText.isEmpty()) {
                                            Text(
                                                    text = "顺手说一句……",
                                                    fontSize = 14.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        inner()
                                    },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Box(
                            modifier =
                                    Modifier.fillMaxWidth()
                                            .height(48.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                    if (canSend) PacketRedBottom
                                                    else PacketRedBottom.copy(alpha = 0.35f)
                                            )
                                            .clickable(enabled = canSend) {
                                                onSend(
                                                        amount,
                                                        greeting,
                                                        attachMessage,
                                                        extraText,
                                                )
                                                onDismiss()
                                            },
                            contentAlignment = Alignment.Center,
                    ) {
                        Text(
                                text = "塞钱进红包",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(text = "取消", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * 转账。照着微信的转账页：上面"转账给"，中间大金额和说明，
 * 底下自带一块数字键盘和最下面那颗绿色转账按钮。
 */
@Composable
fun TransferSendDialog(
        visible: Boolean,
        onDismiss: () -> Unit,
        recipientName: String = "",
        onSend: (amount: String, note: String, extraText: String) -> Unit,
) {
    if (!visible) return

    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var extraText by remember { mutableStateOf("") }

    val normalized = ChatPaymentMarkup.normalizeAmount(amount)
    val canSend = normalized != null

    Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                            text = if (recipientName.isBlank()) "转账" else "转账给 $recipientName",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                                text = "¥",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        BasicTextField(
                                value = amount,
                                onValueChange = { input ->
                                    amount = sanitizeAmountInput(input, allowDecimal = true)
                                },
                                singleLine = true,
                                textStyle =
                                        TextStyle(
                                                fontSize = 40.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                        ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                                decorationBox = { inner ->
                                    if (amount.isEmpty()) {
                                        Text(
                                                text = "0.00",
                                                fontSize = 40.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color =
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                                .copy(alpha = 0.45f),
                                        )
                                    }
                                    inner()
                                },
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth(),
                    ) {
                        BasicTextField(
                                value = note,
                                onValueChange = { note = it.take(60) },
                                singleLine = true,
                                textStyle =
                                        TextStyle(
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                        ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                decorationBox = { inner ->
                                    if (note.isEmpty()) {
                                        Text(
                                                text = "添加转账说明",
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    inner()
                                },
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth(),
                    ) {
                        BasicTextField(
                                value = extraText,
                                onValueChange = { extraText = it.take(300) },
                                textStyle =
                                        TextStyle(
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                        ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                decorationBox = { inner ->
                                    if (extraText.isEmpty()) {
                                        Text(
                                                text = "还想说点什么（可留空）",
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    inner()
                                },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                PaymentNumberPad(
                        onDigit = { digit -> amount = sanitizeAmountInput(amount + digit, true) },
                        onDot = {
                            if (!amount.contains('.')) {
                                amount = if (amount.isEmpty()) "0." else "$amount."
                            }
                        },
                        onBackspace = {
                            amount = if (amount.isEmpty()) amount else amount.dropLast(1)
                        },
                )

                Box(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 12.dp)
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                                if (canSend) TransferGreen
                                                else TransferGreen.copy(alpha = 0.35f)
                                        )
                                        .clickable(enabled = canSend) {
                                            onSend(amount, note, extraText)
                                            onDismiss()
                                        },
                        contentAlignment = Alignment.Center,
                ) {
                    Text(
                            text = "转账",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun PaymentFieldRow(label: String, content: @Composable () -> Unit) {
    Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
                text = label,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(56.dp),
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { content() }
    }
}

@Composable
private fun PaymentNumberPad(
        onDigit: (String) -> Unit,
        onDot: () -> Unit,
        onBackspace: () -> Unit,
) {
    val rows =
            listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf(".", "0", "⌫"),
            )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    Box(
                            modifier =
                                    Modifier.weight(1f)
                                            .height(48.dp)
                                            .padding(3.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(
                                                            alpha = 0.35f
                                                    )
                                            )
                                            .clickable {
                                                when (key) {
                                                    "⌫" -> onBackspace()
                                                    "." -> onDot()
                                                    else -> onDigit(key)
                                                }
                                            },
                            contentAlignment = Alignment.Center,
                    ) {
                        if (key == "⌫") {
                            Icon(
                                    imageVector = Icons.Default.Backspace,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                    text = key,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 输入框只允许数字和一个小数点，整数位最多 7 位，小数最多 2 位。 */
private fun sanitizeAmountInput(raw: String, allowDecimal: Boolean): String {
    val filtered = raw.filter { it.isDigit() || (allowDecimal && it == '.') }
    if (filtered.isEmpty()) return ""
    val firstDot = filtered.indexOf('.')
    val normalized =
            if (firstDot < 0) filtered
            else
                    filtered.substring(0, firstDot + 1) +
                            filtered.substring(firstDot + 1).replace(".", "")
    val dotIndex = normalized.indexOf('.')
    if (dotIndex < 0) return normalized.take(7)
    val integerPart = normalized.substring(0, dotIndex).ifEmpty { "0" }.take(7)
    val decimalPart = normalized.substring(dotIndex + 1).take(2)
    return "$integerPart.$decimalPart"
}