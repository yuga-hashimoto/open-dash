package com.opendash.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opendash.app.R
import com.opendash.app.util.SaverReason
import com.opendash.app.util.SaverState

/**
 * P14.8 Home-screen variant of the Ambient saver chip. Renders a single
 * row banner above the ambient clock when the saver is actively
 * throttling, so users who keep Home in the foreground still see *why*
 * wake-word went quiet.
 *
 * Styled as a thin banner rather than the Ambient chip-row pill so it
 * doesn't compete with the hero clock typography — Home's visual
 * hierarchy puts the clock first, and an error-tinted banner above is
 * the minimum surface that still catches the eye.
 */
@Composable
fun HomeSaverBanner(
    saverState: SaverState,
    modifier: Modifier = Modifier
) {
    if (!saverState.active) return

    val label = when (saverState.reason) {
        SaverReason.BATTERY_LOW -> stringResource(R.string.saver_chip_battery)
        SaverReason.THERMAL_THROTTLE -> stringResource(R.string.saver_chip_thermal)
        SaverReason.NONE -> return
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.BatterySaver,
            contentDescription = stringResource(R.string.saver_chip_icon_description),
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}
