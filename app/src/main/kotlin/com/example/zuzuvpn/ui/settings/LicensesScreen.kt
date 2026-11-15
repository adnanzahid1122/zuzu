package com.example.zuzuvpn.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.zuzuvpn.R
import com.example.zuzuvpn.ui.theme.ZuzuColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shows assets/licenses/NOTICE.txt followed by the full Apache-2.0 text. */
@Composable
fun LicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val text by produceState<String?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                listOf(NOTICE_ASSET, APACHE_ASSET).joinToString("\n\n" + "=".repeat(60) + "\n\n") { name ->
                    context.assets.open(name).bufferedReader().use { it.readText() }
                }
            }.getOrNull()
        } ?: ""
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
            }
            Text(stringResource(R.string.licenses_title), style = MaterialTheme.typography.titleLarge)
        }
        val body = when (val t = text) {
            null -> stringResource(R.string.licenses_loading)
            "" -> stringResource(R.string.licenses_error)
            else -> t
        }
        Text(
            text = body,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = ZuzuColors.TextSecondary,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

private const val NOTICE_ASSET = "licenses/NOTICE.txt"
private const val APACHE_ASSET = "licenses/APACHE-2.0.txt"
