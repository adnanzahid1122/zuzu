package com.example.zuzuvpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zuzuvpn.R
import com.example.zuzuvpn.ui.theme.ZuzuColors

/** Amber "Demo mode" pill. Shown on every screen state that is not backed by a real tunnel. */
@Composable
fun DemoBadge(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .background(ZuzuColors.Warning.copy(alpha = 0.12f), shape)
            .border(1.dp, ZuzuColors.Warning.copy(alpha = 0.5f), shape)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(ZuzuColors.Warning, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.demo_badge).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = ZuzuColors.Warning,
        )
    }
}
