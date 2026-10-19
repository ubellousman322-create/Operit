package com.ai.assistance.operit.ui.features.chat.components.payment

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.data.model.ChatPayment

/** 微信红包/转账那种橙黄色，两版都用它，跟聊天界面主题脱钩。 */
private val PaymentOrangeTop = Color(0xFFF7AC3E)
private val PaymentOrangeBottom = Color(0xFFF29B25)
private val PaymentOrangeAccent = Color(0xFFFF8A2B)

/** 拆过的卡片整体压成灰的，跟微信一样，一眼能看出这个已经拿过了。 */
private val PaymentConsumedCard = Color(0xFFE9E6E1)
private val PaymentConsumedText = Color(0xFF8A8783)

/** 大红包封面的红。 */
private val PacketCoverRed = Color(0xFFD24A3F)
private val PacketCoverRedDark = Color(0xFFC03A30)
private val PacketGold = Color(0xFFF3D9A4)

/**
 * 一条红包/转账消息的完整显示：卡片 + 卡片下面那句附带的话。
 *
 * @param received 已经点开收下了。收了之后卡片变灰、写"已接收"。
 */
@Composable
fun ChatPaymentBubble(
        payment: ChatPayment,
        isUserSide: Boolean,
        textColor: Color,
        received: Boolean,
        modifier: Modifier = Modifier,
        onOpen: () -> Unit = {},
) {
    val corner =
            if (isUserSide) RoundedCornerShape(14.dp, 4.dp, 14.dp, 14.dp)
            else RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp)

    Column(
            modifier = modifier.widthIn(max = 268.dp),
            horizontalAlignment = if (isUserSide) Alignment.End else Alignment.Start,
    ) {
        when (payment) {
            is ChatPayment.RedPacket -> RedPacketCard(payment, corner, received, onOpen)
            is ChatPayment.Transfer -> TransferCard(payment, corner, received, onOpen)
        }
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
private fun RedPacketCard(
        payment: ChatPayment.RedPacket,
        shape: RoundedCornerShape,
        received: Boolean,
        onOpen: () -> Unit,
) {
    val background =
            if (received) Brush.verticalGradient(listOf(PaymentConsumedCard, PaymentConsumedCard))
            else Brush.verticalGradient(listOf(PaymentOrangeTop, PaymentOrangeBottom))
    val titleColor = if (received) PaymentConsumedText else Color.White
    val subColor = if (received) PaymentConsumedText.copy(alpha = 0.8f) else Color(0xCCFFFFFF)
    val iconTint = if (received) PaymentConsumedText else Color.White

    Surface(
            shape = shape,
            color = Color.Transparent,
            modifier =
                    Modifier.width(238.dp)
                            .clip(shape)
                            .clickable(enabled = !received, onClick = onOpen),
    ) {
        Column(
                modifier =
                        Modifier.fillMaxWidth()
                                .background(background)
                                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                        modifier =
                                Modifier.size(40.dp)
                                        .clip(CircleShape)
                                        .background(
                                                if (received) Color(0x1A000000) else Color(0x33FFFFFF)
                                        ),
                        contentAlignment = Alignment.Center,
                ) {
                    Icon(
                            imageVector = Icons.Default.Redeem,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(23.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                            text = payment.greeting,
                            color = titleColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 21.sp,
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                            text = if (received) "已接收" else "领取红包",
                            color = subColor,
                            fontSize = 11.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.height(9.dp))
            Text(
                    text = if (received) "红包 · 已接收" else "红包",
                    color = subColor,
                    fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun TransferCard(
        payment: ChatPayment.Transfer,
        shape: RoundedCornerShape,
        received: Boolean,
        onOpen: () -> Unit,
) {
    val background =
            if (received) Brush.verticalGradient(listOf(PaymentConsumedCard, PaymentConsumedCard))
            else Brush.verticalGradient(listOf(PaymentOrangeTop, PaymentOrangeAccent))
    val titleColor = if (received) PaymentConsumedText else Color.White
    val subColor = if (received) PaymentConsumedText.copy(alpha = 0.85f) else Color(0xE6FFFFFF)
    val iconTint = if (received) PaymentConsumedText else PaymentOrangeAccent
    val iconBackground = if (received) Color(0x1A000000) else Color.White

    Surface(
            shape = shape,
            color = Color.Transparent,
            modifier =
                    Modifier.width(238.dp)
                            .clip(shape)
                            .clickable(enabled = !received, onClick = onOpen),
    ) {
        Column(
                modifier =
                        Modifier.fillMaxWidth()
                                .background(background)
                                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(iconBackground),
                        contentAlignment = Alignment.Center,
                ) {
                    Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                            text = "¥${payment.amount}",
                            color = titleColor,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                            text = if (received) "已接收" else "请收款",
                            color = subColor,
                            fontSize = 11.sp,
                    )
                }
            }
            if (payment.note.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                        text = payment.note,
                        color = subColor,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                    text = if (received) "转账 · 已接收" else "转账",
                    color = subColor,
                    fontSize = 10.sp,
            )
        }
    }
}

/** 转账点开之后那张确认卡：金额、说明、来源，一颗"确认收款"。 */
@Composable
fun TransferReceiveDialog(
        payment: ChatPayment.Transfer,
        senderName: String,
        onDismiss: () -> Unit,
        onReceive: () -> Unit,
) {
    AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                        onClick = {
                            onReceive()
                            onDismiss()
                        }
                ) { Text(text = "确认收款") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(text = "取消") }
            },
            title = {
                Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                            text = "¥${payment.amount}",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PaymentOrangeAccent,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                            text = "转账",
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
                    Text(text = payment.note.ifBlank { "请收款" }, fontSize = 14.sp)
                    Text(
                            text = "来自 ${senderName.ifBlank { "对方" }}",
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

/**
 * 红包大屏。照着你发的那张图：整块红封面、头像和祝福语、下面一颗金色"開"。
 * 点開之后金额才出来，同时把这条红包记成已接收。
 */
@Composable
fun RedPacketOpenDialog(
        payment: ChatPayment.RedPacket,
        senderName: String,
        onDismiss: () -> Unit,
        onOpened: () -> Unit,
) {
    var opened by remember { mutableStateOf(false) }

    Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
                modifier =
                        Modifier.fillMaxSize().background(Color(0xE6000000)).clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {}
                        ),
                contentAlignment = Alignment.Center,
        ) {
            Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .aspectRatio(0.72f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                                Brush.verticalGradient(
                                                        listOf(PacketCoverRed, PacketCoverRedDark)
                                                )
                                        )
                ) {
                    Column(
                            modifier = Modifier.fillMaxSize().padding(top = 42.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                    modifier =
                                            Modifier.size(26.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color(0x33FFFFFF)),
                                    contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                        imageVector = Icons.Default.Redeem,
                                        contentDescription = null,
                                        tint = PacketGold,
                                        modifier = Modifier.size(15.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                    text = "${senderName.ifBlank { "对方" }}的红包",
                                    color = PacketGold,
                                    fontSize = 13.sp,
                            )
                        }

                        Spacer(modifier = Modifier.height(26.dp))

                        if (!opened) {
                            Text(
                                    text = payment.greeting,
                                    color = PacketGold,
                                    fontSize = 17.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 25.sp,
                                    modifier = Modifier.padding(horizontal = 26.dp),
                            )
                        } else {
                            Text(
                                    text = "¥${payment.amount}",
                                    color = PacketGold,
                                    fontSize = 44.sp,
                                    fontWeight = FontWeight.Light,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                    text = "已存入零钱",
                                    color = PacketGold.copy(alpha = 0.85f),
                                    fontSize = 12.sp,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                    text = payment.greeting,
                                    color = PacketGold.copy(alpha = 0.7f),
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 26.dp),
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // 金色那颗"開"
                        Box(
                                modifier =
                                        Modifier.size(74.dp)
                                                .clip(CircleShape)
                                                .background(
                                                        Brush.verticalGradient(
                                                                listOf(Color(0xFFF7E3B8), PacketGold)
                                                        )
                                                )
                                                .clickable(enabled = !opened) {
                                                    opened = true
                                                    onOpened()
                                                },
                                contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                    text = if (opened) "✓" else "開",
                                    color = Color(0xFF8A5A22),
                                    fontSize = if (opened) 30.sp else 32.sp,
                                    fontWeight = FontWeight.Medium,
                            )
                        }

                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                IconButton(
                        onClick = onDismiss,
                        modifier =
                                Modifier.size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color(0x33FFFFFF))
                ) {
                    Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}