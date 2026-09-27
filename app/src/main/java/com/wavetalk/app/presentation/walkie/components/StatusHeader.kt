package com.wavetalk.app.presentation.walkie.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wavetalk.app.R
import com.wavetalk.app.domain.EngineState
import com.wavetalk.app.domain.TalkPhase
import com.wavetalk.app.presentation.icons.AppIcons

/**
 * Status card at the top of the walkie screen: Wi-Fi state, online device
 * count and the current channel chip.
 */
@Composable
fun StatusHeader(
    state: EngineState,
    onChannelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = if (state.network.wifiConnected) AppIcons.Wifi else AppIcons.WifiOff,
                contentDescription = null,
                tint = if (state.network.wifiConnected) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (state.network.wifiConnected) stringResource(R.string.status_connected)
                    else stringResource(R.string.status_no_wifi),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.devices_online, state.onlineCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            ChannelChip(
                channel = state.channel,
                onClick = onChannelClick,
            )
        }
    }
}

@Composable
fun ChannelChip(channel: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.shapes.small,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "${stringResource(R.string.channel_label)}: ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
        Text(
            text = channel,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(84.dp),
        )
    }
}

/** Green pulse dot for online / gray for offline. */
@Composable
fun StatusDot(online: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(10.dp)
            .background(
                if (online) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                CircleShape,
            ),
    )
}

/** Banner that shows who is currently talking (remote) or transmitting states. */
@Composable
fun TalkingBanner(state: EngineState, modifier: Modifier = Modifier) {
    val text = when {
        state.phase is TalkPhase.Talking -> stringResource(R.string.you_are_talking)
        state.receivingFrom != null -> stringResource(R.string.receiving_from, state.receivingFrom!!)
        state.phase is TalkPhase.Busy -> stringResource(R.string.channel_busy, (state.phase as TalkPhase.Busy).talkerName)
        else -> {
            val talker = state.remoteTalker
            if (talker != null) stringResource(R.string.talking_now, talker.name) else null
        }
    } ?: return

    val color = when {
        state.phase is TalkPhase.Talking -> MaterialTheme.colorScheme.primaryContainer
        state.phase is TalkPhase.Busy -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when {
        state.phase is TalkPhase.Talking -> MaterialTheme.colorScheme.primary
        state.phase is TalkPhase.Busy -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .background(color, MaterialTheme.shapes.large)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = AppIcons.VolumeUp,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
