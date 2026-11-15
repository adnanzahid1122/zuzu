package com.example.zuzuvpn.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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

/** Home top bar: wordmark on the left, gold crown shortcut to Premium on the right. */
@Composable
fun ZuzuTopBar(
    onPremiumClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZuzuWordmark(Modifier.weight(1f))
        IconButton(onClick = onPremiumClick) {
            Icon(
                painter = painterResource(R.drawable.ic_nav_premium),
                contentDescription = stringResource(R.string.action_premium),
                tint = ZuzuColors.Gold,
            )
        }
    }
}
