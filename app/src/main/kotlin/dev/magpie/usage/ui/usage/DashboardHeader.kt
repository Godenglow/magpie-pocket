package dev.magpie.usage.ui.usage

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.magpie.usage.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardHeader(
    subtitle: String,
    syncing: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onTheme: (String) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    TopAppBar(
        windowInsets = WindowInsets(0, 0, 0, 0),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.ic_magpie), contentDescription = null,
                    modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onSurface)
                Column {
                    Text("magpie", style = MaterialTheme.typography.titleLarge)
                    Text(subtitle, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        actions = {
            IconButton(onClick = { onTheme(if (dark) "light" else "dark") }) {
                Icon(if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                    contentDescription = if (dark) "切换为浅色" else "切换为深色")
            }
            IconButton(onClick = onRefresh, enabled = !syncing) {
                if (syncing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "设置")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}
