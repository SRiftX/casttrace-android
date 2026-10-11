package com.streamcap.mobile.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.streamcap.mobile.R

@Composable
fun SupportDialog(firstLaunch: Boolean, onDismiss: () -> Unit, onShowQr: (() -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (firstLaunch) "感谢使用 CastTrace" else "支持者赞助") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("感谢你愿意使用和支持 CastTrace。你的反馈帮助我们持续改进录制体验。")
                if (firstLaunch) {
                    Text("如果你觉得它有帮助，可以在设置中找到“支持者赞助”。赞助完全自愿，不影响任何功能。")
                } else {
                    Text("如果你愿意支持后续开发，可以扫描下方二维码。赞助完全自愿，不影响任何功能。")
                    Image(
                        painter = painterResource(R.drawable.support_qr),
                        contentDescription = "支持者赞助二维码",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp).heightIn(max = 320.dp)
                            .align(Alignment.CenterHorizontally),
                    )
                    Text("请在支付应用中确认收款信息后再付款。感谢支持！")
                }
            }
        },
        confirmButton = {
            if (firstLaunch) TextButton(onClick = { onDismiss(); onShowQr?.invoke() }) { Text("查看赞助码") }
            else TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            if (firstLaunch) TextButton(onClick = onDismiss) { Text("以后再说") }
        },
    )
}
