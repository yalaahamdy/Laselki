package com.wavetalk.app.presentation.walkie

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.wavetalk.app.R
import com.wavetalk.app.domain.TalkPhase
import com.wavetalk.app.presentation.icons.AppIcons
import com.wavetalk.app.presentation.walkie.components.ChannelSheet
import com.wavetalk.app.presentation.walkie.components.DeviceList
import com.wavetalk.app.presentation.walkie.components.PttButton
import com.wavetalk.app.presentation.walkie.components.PttVisual
import com.wavetalk.app.presentation.walkie.components.StatusHeader
import com.wavetalk.app.presentation.walkie.components.TalkingBanner
import com.wavetalk.app.session.TalkEngine

/**
 * The main walkie-talkie screen.
 */
@Composable
fun WalkieScreen(
    viewModel: WalkieViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
    }

    var showChannelSheet by remember { mutableStateOf(false) }

    // Engine events → snackbar; mic permission request event.
    val eventTextNoWifi = stringResource(R.string.error_no_wifi)
    val eventTextMic = stringResource(R.string.error_mic_denied)
    val eventTextBind = stringResource(R.string.error_bind)
    val eventTextSend = stringResource(R.string.error_send_failed, state.channel)
    val eventTextChannel = stringResource(R.string.error_channel_rejected)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TalkEngine.EngineEvent.Message -> {
                    val text = when (event.text) {
                        "error_no_wifi" -> eventTextNoWifi
                        "error_mic_denied" -> eventTextMic
                        "error_bind" -> eventTextBind
                        "error_send_failed_generic" -> eventTextSend
                        else -> event.text
                    }
                    snackbar.showSnackbar(text)
                }
                is TalkEngine.EngineEvent.MicPermissionNeeded -> {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    // Determine button visual state
    val visual = when {
        !state.network.wifiConnected -> PttVisual.NO_WIFI
        !micGranted -> PttVisual.NO_MIC
        state.phase is TalkPhase.Busy -> PttVisual.BUSY
        state.phase is TalkPhase.Talking -> PttVisual.TALKING
        state.remoteTalker != null -> PttVisual.BUSY
        else -> PttVisual.IDLE
    }

    val (label, sub) = when (visual) {
        PttVisual.IDLE -> stringResource(R.string.ptt_idle) to null
        PttVisual.TALKING -> stringResource(R.string.ptt_talking) to stringResource(R.string.you_are_talking)
        PttVisual.BUSY -> {
            val busyName = (state.phase as? TalkPhase.Busy)?.talkerName ?: state.remoteTalker?.name ?: ""
            if (busyName.isBlank()) stringResource(R.string.ptt_busy) to null
            else stringResource(R.string.ptt_busy) to stringResource(R.string.talking_now, busyName)
        }
        PttVisual.NO_MIC -> stringResource(R.string.ptt_no_mic) to stringResource(R.string.onboarding_mic_body)
        PttVisual.NO_WIFI -> stringResource(R.string.ptt_no_wifi) to stringResource(R.string.error_no_wifi)
    }

    val icon = when (visual) {
        PttVisual.NO_MIC -> AppIcons.MicOff
        PttVisual.NO_WIFI -> AppIcons.WifiOff
        else -> AppIcons.Mic
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Top bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        text = state.myName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = AppIcons.Settings,
                        contentDescription = stringResource(R.string.settings_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            StatusHeader(
                state = state,
                onChannelClick = { showChannelSheet = true },
            )

            TalkingBanner(state = state)

            // Centered PTT button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                PttButton(
                    visual = visual,
                    label = label,
                    subLabel = sub,
                    icon = icon,
                    level = state.audioLevel,
                    enabled = state.network.wifiConnected && visual != PttVisual.NO_WIFI,
                    onPressStart = { viewModel.press(micGranted) },
                    onPressEnd = { viewModel.release() },
                )
            }

            DeviceList(
                state = state,
                onDeviceClick = { device -> viewModel.talkToDevice(device) },
            )

            Spacer(Modifier.height(24.dp))
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showChannelSheet) {
        ChannelSheet(
            currentChannel = state.channel,
            knownChannels = viewModel.knownChannelsSnapshot(),
            onJoin = { channel -> viewModel.setChannel(channel) },
            onDismiss = { showChannelSheet = false },
        )
    }
}
