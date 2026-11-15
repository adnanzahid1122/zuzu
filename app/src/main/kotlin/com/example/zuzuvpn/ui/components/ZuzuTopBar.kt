package com.example.zuzuvpn.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.zuzuvpn.R
import com.example.zuzuvpn.ui.theme.ZuzuColors

/** "ZUZU VPN" wordmark: white heavy ZUZU + electric-blue VPN. */
@Composable
fun ZuzuWordmark(modifier: Modifier = Modifier) {
    val brand = stringResource(R.string.brand_wordmark)
    val suffix = stringResource(R.string.brand_suffix)
    Text(
        modifier = modifier,
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = ZuzuColors.TextPrimary, fontWeight = FontWeight.Black, letterSpacing = 2.sp)) {
                append(brand)
            }
            append(" ")
            withStyle(SpanStyle(color = ZuzuColors.ElectricBlueBright, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)) {
                append(suffix)
            }
        },
        style = MaterialTheme.typography.titleLarge,
    )
}

/** Home top bar: the Zuzu wordmark. */
@Composable
fun ZuzuTopBar(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZuzuWordmark(Modifier.weight(1f))
    }
}
