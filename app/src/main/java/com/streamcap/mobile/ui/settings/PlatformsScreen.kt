package com.streamcap.mobile.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamcap.mobile.platform.AndroidSupportStatus
import com.streamcap.mobile.platform.PlatformCatalog
import com.streamcap.mobile.platform.PlatformCatalogEntry
import com.streamcap.mobile.ui.theme.LiveTeal
import com.streamcap.mobile.ui.theme.WarningAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformsScreen(onBack: () -> Unit) {
    val entries = PlatformCatalog.entries
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("平台适配") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Android 当前可录制虎牙、抖音、B站、斗鱼、快手及 HLS / FLV 直链；其余平台按列表继续适配。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(entries, key = { it.key }) { entry -> PlatformRow(entry) }
        }
    }
}

@Composable
private fun PlatformRow(entry: PlatformCatalogEntry) {
    val color = when (entry.status) {
        AndroidSupportStatus.AVAILABLE, AndroidSupportStatus.FIRST_BATCH -> LiveTeal
        AndroidSupportStatus.NEEDS_REVIEW -> WarningAmber
        AndroidSupportStatus.PLANNED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    entry.domains.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(entry.status.displayName(), style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

private fun AndroidSupportStatus.displayName(): String = when (this) {
    AndroidSupportStatus.AVAILABLE -> "当前可用"
    AndroidSupportStatus.FIRST_BATCH -> "首批适配"
    AndroidSupportStatus.PLANNED -> "计划接入"
    AndroidSupportStatus.NEEDS_REVIEW -> "待复核"
}
