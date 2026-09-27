package com.wavetalk.app.presentation.walkie.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wavetalk.app.R
import com.wavetalk.app.domain.Device
import com.wavetalk.app.domain.DeviceStatus
import com.wavetalk.app.domain.EngineState
import com.wavetalk.app.domain.RemoteTalkState
import com.wavetalk.app.domain.TalkScope
import com.wavetalk.app.presentation.icons.AppIcons

/**
 * Live list of discovered devices with status dots and speaking badges.
 * Tapping an online device selects it as the direct talk target.
 */
@Composable
fun DeviceList(
    state: EngineState,
    onDeviceClick: (Device) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        ) {
            Icon(
                imageVector = AppIcons.People,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.devices_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (state.scope is TalkScope.Single) stringResource(R.string.scope_device, state.scope.device.name)
                else stringResource(R.string.scope_channel),
                style = MaterialTheme.typography.labelSmall,
                color = if (state.scope is TalkScope.Single) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(160.dp),
                textAlign = TextAlign.End,
            )
        }

        Spacer(Modifier.height(8.dp))

        if (state.devices.isEmpty()) {
            EmptyDevices(modifier = Modifier.fillMaxWidth())
        } else {
            // NOTE: a plain Column is required here — this list lives inside the
            // screen-level verticalScroll container. A LazyColumn would be measured
            // with infinite height constraints and crash the app the instant the
            // first peer is discovered. LAN device lists are small, so laziness
            // brings no benefit anyway.
            Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                state.devices.forEach { device ->
                    DeviceRow(
                        device = device,
                        myChannel = state.channel,
                        selected = (state.scope as? TalkScope.Single)?.device?.id == device.id,
                        onClick = { if (device.status == DeviceStatus.ONLINE) onDeviceClick(device) },
                    )
                }
                Text(
                    text = stringResource(R.string.tap_device_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: Device,
    myChannel: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val online = device.status == DeviceStatus.ONLINE
    val speaking = online && device.talkState == RemoteTalkState.TALKING
    val sameChannel = device.channel.equals(myChannel, ignoreCase = true)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else -> MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 2.dp else 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (online) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape,
                    ),
            ) {
                Text(
                    text = device.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (online) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (speaking) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.device_speaking),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    text = buildString {
                        append(if (online) stringResource(R.string.device_status_online) else stringResource(R.string.device_status_offline))
                        if (!sameChannel) append("  •  ${device.channel}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (online) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusDot(online = online)
        }
    }
}

@Composable
private fun EmptyDevices(modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            Icon(
                imageVector = AppIcons.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.status_searching),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.empty_devices_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
