package com.ai.assistance.operit.ui.features.chat.components.payment

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.data.model.ChatPayment

/** 微信红包/转账那种橙黄色，两版都用它，跟聊天界面主题脱钩。 */
private val PaymentOrangeTop = Color(0xFFF7AC3E)
private val PaymentOrangeBottom = Color(0xFFF29B25)
private val PaymentOrangeAccent = Color(0xFFFF8A2B)

/**
 * 一条红包/转账消息的完整显示：卡片 + 卡片下面那句附带的话。
 *
 * @param isUserSide 用户发的靠右、AI 发的靠左，只影响圆角方向。
 */
@Composable
fun ChatPaymentBubble(
        payment: ChatPayment,
        isUserSide: Boolean,
        textColor: Color,
        modifier: Modifier = Modifier,
        onOpen: () -> Unit = {},
) {
    val corner = if (isUserSide) RoundedCornerShape(14.dp, 4.dp, 14.dp, 14.dp)
    else RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp)

    Column(
            modifier = modifier.widthIn(max = 268.dp),
            horizontalAlignment = if (isUserSide) Alignment.End else Alignment.Start,
    ) {
        ChatPaymentCard(payment = payment, shape = corner, onOpen = onOpen)
        if (payment.trailingText.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                    text = payment.trailingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    lineHeight = 22.sp,
            )
        }
    }
}

@Composable
private fun ChatPaymentCard(
        payment: ChatPayment,
        shape: RoundedCornerShape,
        onOpen: () -> Unit,
) {
    when (payment) {
        is ChatPayment.RedPacket -> RedPacketCard(payment, shape, onOpen)
        is ChatPayment.Transfer -> TransferCard(payment, shape, onOpen)
    }
}

@Composable
private fun RedPacketCard(
        payment: ChatPayment.RedPacket,
        shape: RoundedCornerShape,
        onOpen: () -> Unit,
) {
    Surface(
            shape = shape,
            color = Color.Transparent,
            modifier = Modifier.width(238.dp).clip(shape).clickable(onClick = onOpen),
    ) {
        Column(
                modifier =
                        Modifier.fillMaxWidth()
                                .background(
                                        Brush.verticalGradient(
                                                listOf(PaymentOrangeTop, PaymentOrangeBottom)
                                        )
                                )
                                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                        modifier =
                                Modifier.size(40.dp)
                                        .clip(CircleShape)
                                        .background(Color(0x33FFFFFF)),
                        contentAlignment = Alignment.Center,
                ) {
                    Icon(
                            imageVector = Icons.Default.Redeem,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(23.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                            text = payment.greeting,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 21.sp,
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                            text = "领取红包",
                            color = Color(0xCCFFFFFF),
                            fontSize = 11.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.height(9.dp))
            Text(text = "红包", color = Color(0x99FFFFFF), fontSize = 10.sp)
        }
    }
}

@Composable
private fun TransferCard(
        payment: ChatPayment.Transfer,
        shape: RoundedCornerShape,
        onOpen: () -> Unit,
) {
    Surface(
            shape = shape,
            color = Color.Transparent,
            modifier = Modifier.width(238.dp).clip(shape).clickable(onClick = onOpen),
    ) {
        Column(
                modifier =
                        Modifier.fillMaxWidth()
                                .background(
                                        Brush.verticalGradient(
                                                listOf(PaymentOrangeTop, PaymentOrangeAccent)
                                        )
                                )
                                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                        modifier =
                                Modifier.size(40.dp)
                                        .clip(CircleShape)
                                        .background(Color.White),
                        contentAlignment = Alignment.Center,
                ) {
                    Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = null,
                            tint = PaymentOrangeAccent,
                            modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                            text = "¥${payment.amount}",
                            color = Color.White,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                            text = "请收款",
                            color = Color(0xE6FFFFFF),
                            fontSize = 11.sp,
                    )
                }
            }
            if (payment.note.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                        text = payment.note,
                        color = Color(0xE6FFFFFF),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "转账", color = Color(0x99FFFFFF), fontSize = 10.sp)
        }
    }
}

/** 点开卡片之后那张详情，样式贴着微信：大金额、说明、来源。 */
@Composable
fun ChatPaymentDetailDialog(payment: ChatPayment, onDismiss: () -> Unit) {
    val isRedPacket = payment is ChatPayment.RedPacket
    androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text(if (isRedPacket) "收下" else "确认收款")
                }
            },
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                            text =
                                    when (payment) {
                                        is ChatPayment.RedPacket -> "¥${payment.amount}"
                                        is ChatPayment.Transfer -> "¥${payment.amount}"
                                    },
                            fontSize = 30.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PaymentOrangeAccent,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                            text = if (isRedPacket) "红包" else "转账",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            text = {
                Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val detail =
                            when (payment) {
                                is ChatPayment.RedPacket -> payment.greeting
                                is ChatPayment.Transfer -> payment.note.ifBlank { "请收款" }
                            }
                    Text(text = detail, fontSize = 14.sp)
                    Text(
                            text = if (payment.isFromUser) "来自你" else "来自 ${payment.senderName.ifBlank { "对方" }}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (payment.trailingText.isNotBlank()) {
                        Text(text = payment.trailingText, fontSize = 13.sp)
                    }
                }
            },
    )
}
