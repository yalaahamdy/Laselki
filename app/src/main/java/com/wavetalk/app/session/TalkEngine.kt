package com.wavetalk.app.session

import android.content.Context
import com.wavetalk.app.audio.AudioCapturer
import com.wavetalk.app.audio.AudioPlayer
import com.wavetalk.app.audio.AdpcmCodec
import com.wavetalk.app.audio.ToneFeedback
import com.wavetalk.app.core.AppConstants
import com.wavetalk.app.core.WtLog
import com.wavetalk.app.discovery.DiscoveryService
import com.wavetalk.app.discovery.PresenceTracker
import com.wavetalk.app.domain.Device
import com.wavetalk.app.domain.DeviceStatus
import com.wavetalk.app.domain.EngineState
import com.wavetalk.app.domain.NetworkState
import com.wavetalk.app.domain.RemoteTalkState
import com.wavetalk.app.domain.TalkPhase
import com.wavetalk.app.domain.TalkScope
import com.wavetalk.app.network.NetworkMonitor
import com.wavetalk.app.network.WifiNetworkUtils
import com.wavetalk.app.settings.AppSettings
import com.wavetalk.app.settings.SettingsRepository
import com.wavetalk.app.transport.Link
import com.wavetalk.app.transport.TcpAudioServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.SocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The conductor of everything WaveTalk does: discovery lifecycle, presence,
 * audio server/client wiring, push-to-talk flow, half-duplex enforcement,
 * and network-change recovery.
 */
class TalkEngine(
    private val context: Context,
    private val settings: SettingsRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(EngineState(channel = SettingsRepository.DEFAULT_CHANNEL))
    val state: StateFlow<EngineState> = _state

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    sealed interface EngineEvent {
        data class Message(val text: String) : EngineEvent
        data object MicPermissionNeeded : EngineEvent
    }

    // ---- identity & components (created in start()) ----
    private lateinit var selfId: UUID
    private var presence: PresenceTracker? = null
    private var discovery: DiscoveryService? = null
    private var server: TcpAudioServer? = null
    private var monitor: NetworkMonitor? = null
    private val links = ConcurrentHashMap<UUID, Link>()
    private val capturer: AudioCapturer
    private val player: AudioPlayer
    private val feedback = ToneFeedback(context)

    @Volatile private var currentSettings: AppSettings = AppSettings()
    @Volatile private var discoveryTargets: List<SocketAddress> = emptyList()
    private val audioSeq = AtomicInteger(0)

    private val pressMutex = Mutex()
    private var transmitJob: Job? = null

    init {
        capturer = AudioCapturer(
            gainProvider = { currentSettings.micGain },
            onFrame = ::onCapturedFrame,
            onError = { message ->
                WtLog.e(TAG, "Capture error: $message")
                stopTransmit()
                _state.update { it.copy(phase = TalkPhase.Idle) }
            },
        )
        player = AudioPlayer(onLevel = { level ->
            _state.update { if (it.receivingFrom != null) it.copy(audioLevel = level) else it }
        })
    }

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        scope.launch {
            if (isStarted) return@launch
            isStarted = true

            settings.ensureIdentity()
            selfId = UUID.fromString(settings.deviceId())
            settings.settings.collect { applied ->
                // First emission initializes; later ones apply live changes.
                val first = _state.value.myName.isEmpty()
                currentSettings = applied
                _state.update {
                    it.copy(
                        myName = applied.deviceName ?: it.myName,
                        channel = if (first) applied.lastChannel else it.channel,
                    )
                }
                if (!first) discovery?.announceOnce()
            }
        }

        scope.launch {
            if (isInfrastructureStarted) return@launch
            isInfrastructureStarted = true

            // Wait until identity is ready before building identity-bound pieces.
            while (!this@TalkEngine::selfId.isInitialized) delay(20)

            presence = PresenceTracker().also { p ->
                scope.launch {
                    p.devices.collect { devices -> _state.update { it.copy(devices = devices) } }
                }
            }

            server = TcpAudioServer(
                scope = scope,
                selfId = selfId,
                selfName = { _state.value.myName },
                channelProvider = { _state.value.channel },
                listeners = serverListeners,
            ).also { it.start() }

            discovery = DiscoveryService(
                scope = scope,
                presence = presence!!,
                selfId = selfId,
                selfName = { _state.value.myName },
                selfChannel = { _state.value.channel },
                selfAudioPort = { server?.boundPort ?: 0 },
                selfTalking = { _state.value.phase is TalkPhase.Talking },
                sendTargets = { discoveryTargets },
                socketFactory = {
                    java.net.DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(java.net.InetSocketAddress(AppConstants.DISCOVERY_PORT))
                        broadcast = true
                    }
                },
                onFatal = {
                    WtLog.e(TAG, "Discovery socket bind failed", it)
                    _state.update { s -> s.copy(engineError = true) }
                    emitMessage("error_bind")
                },
            )

            monitor = NetworkMonitor(context, scope).also { m ->
                scope.launch {
                    m.wifiUp.collect { up -> handleNetworkChange(up) }
                }
                m.start()
            }

            scope.launch {
                while (isActive) {
                    delay(1_000)
                    presence?.sweep()
                }
            }
        }
    }

    @Volatile private var isStarted = false
    @Volatile private var isInfrastructureStarted = false

    // ------------------------------------------------------------------ network changes

    private suspend fun handleNetworkChange(wifiUp: Boolean) {
        WtLog.i(TAG, "Network change: wifiUp=$wifiUp")
        if (!wifiUp) {
            discovery?.stop()
            discoveryTargets = emptyList()
            presence?.clear()
            closeAllLinks()
            if (_state.value.phase is TalkPhase.Talking) stopTransmit()
            _state.update {
                it.copy(
                    network = NetworkState(false, null),
                    phase = if (it.phase is TalkPhase.Talking) TalkPhase.Idle else it.phase,
                    receivingFrom = null,
                    audioLevel = 0f,
                )
            }
            player.stop()
            return
        }

        val local = WifiNetworkUtils.findLocalInterface()
        if (local == null) {
            _state.update { it.copy(network = NetworkState(false, null)) }
            return
        }
        val broadcasts = WifiNetworkUtils.broadcastAddresses(local)
            .map { java.net.InetSocketAddress(it, AppConstants.DISCOVERY_PORT) }
        discoveryTargets = broadcasts

        _state.update { it.copy(network = NetworkState(true, local.address.hostAddress), engineError = false) }

        // Fresh subnet → stale entries must go; restart discovery with burst.
        presence?.clear()
        closeAllLinks()
        discovery?.restart()
    }

    // ------------------------------------------------------------------ receiving path

    private val serverListeners = object : TcpAudioServer.Listeners {
        override fun onSessionOpened(deviceId: UUID, name: String) {}
        override fun onSessionClosed(deviceId: UUID) {}

        override fun onTalkStarted(deviceId: UUID, name: String) {
            _state.update { it.copy(receivingFrom = name) }
            player.start()
        }

        override fun onTalkStopped(deviceId: UUID) {
            _state.update { it.copy(receivingFrom = null, audioLevel = 0f) }
            player.stop()
        }

        override fun onAudioFrame(deviceId: UUID, payload: ByteArray) {
            if (payload.size <= SEQ_BYTES) return
            player.offerEncodedFrame(payload.copyOfRange(SEQ_BYTES, payload.size))
        }

        override fun onServerFailed() {
            _state.update { it.copy(engineError = true) }
            emitMessage("error_bind")
        }
    }

    // ------------------------------------------------------------------ push to talk

    /**
     * Begins transmission. Returns the resulting phase so the UI can react
     * immediately (Busy / Idle on rejection, Talking on success).
     */
    suspend fun press(micGranted: Boolean): TalkPhase = pressMutex.withLock {
        val s = _state.value
        if (s.phase is TalkPhase.Talking) return s.phase
        if (!s.network.wifiConnected) {
            emitMessage("error_no_wifi")
            return TalkPhase.Idle
        }
        if (!micGranted) {
            _events.emit(EngineEvent.MicPermissionNeeded)
            emitMessage("error_mic_denied")
            return TalkPhase.Idle
        }

        // Half-duplex: block if someone audible in our target is already talking.
        val busyTalker = when (val sc = s.scope) {
            is TalkScope.Channel -> s.devices.firstOrNull {
                it.status == DeviceStatus.ONLINE &&
                    it.talkState == RemoteTalkState.TALKING &&
                    it.channel.equals(s.channel, ignoreCase = true)
            }
            is TalkScope.Single -> s.devices.firstOrNull {
                it.status == DeviceStatus.ONLINE && it.talkState == RemoteTalkState.TALKING &&
                    (it.id == sc.device.id || it.channel.equals(s.channel, ignoreCase = true))
            }
        }
        if (busyTalker != null) {
            _state.update { it.copy(phase = TalkPhase.Busy(busyTalker.name)) }
            return TalkPhase.Busy(busyTalker.name)
        }

        audioSeq.set(0)
        _state.update { it.copy(phase = TalkPhase.Talking, audioLevel = 0f) }
        discovery?.sendTalkEvent(true)
        feedback.pressBeep(currentSettings.soundEffectsEnabled)
        feedback.vibrate(currentSettings.vibrationEnabled)

        capturer.start(micGranted)

        transmitJob = scope.launch {
            val targets = when (val sc = _state.value.scope) {
                is TalkScope.Channel -> _state.value.devices.filter {
                    it.status == DeviceStatus.ONLINE && it.channel.equals(_state.value.channel, ignoreCase = true)
                }
                is TalkScope.Single -> listOf(sc.device).filter { it.status == DeviceStatus.ONLINE }
            }
            try {
                withTimeout(2_500) { ensureLinks(targets) }
            } catch (_: Exception) {
                // Continue with whatever links became ready.
            }
            val startPayload = ByteArray(0)
            for (link in links.values) {
                if (link.isReady) link.sendFrame(WireTALK_START, startPayload)
            }
            if (links.values.none { it.isReady } && targets.isNotEmpty()) {
                emitMessage("error_send_failed_generic")
            }
        }
        return TalkPhase.Talking
    }

    fun release() {
        if (_state.value.phase !is TalkPhase.Talking) return
        stopTransmit()
        feedback.releaseBeep(currentSettings.soundEffectsEnabled)
        feedback.vibrate(currentSettings.vibrationEnabled, 25)
    }

    private fun stopTransmit() {
        if (_state.value.phase !is TalkPhase.Talking) return
        _state.update { it.copy(phase = TalkPhase.Idle, audioLevel = 0f) }
        capturer.stop()
        discovery?.sendTalkEvent(false)
        transmitJob?.cancel()
        transmitJob = null
        scope.launch(Dispatchers.IO) {
            for (link in links.values) {
                if (link.isReady) link.sendFrame(WireTALK_STOP, ByteArray(0))
            }
        }
    }

    private fun onCapturedFrame(pcm: ShortArray, count: Int, level: Float) {
        if (_state.value.phase !is TalkPhase.Talking) return
        val fixed = if (count == AppConstants.FRAME_SAMPLES) pcm else {
            val padded = ShortArray(AppConstants.FRAME_SAMPLES)
            System.arraycopy(pcm, 0, padded, 0, count.coerceAtMost(AppConstants.FRAME_SAMPLES))
            padded
        }
        val encoded = AdpcmCodec.encode(fixed)
        val seq = (audioSeq.getAndIncrement() and 0xFFFF)
        val payload = ByteArray(SEQ_BYTES + encoded.size)
        payload[0] = (seq and 0xFF).toByte()
        payload[1] = ((seq shr 8) and 0xFF).toByte()
        encoded.copyInto(payload, SEQ_BYTES)

        for (link in links.values) {
            if (link.isReady) link.sendFrame(WireAUDIO, payload)
        }
        _state.update { it.copy(audioLevel = level) }
    }

    // ------------------------------------------------------------------ links

    private suspend fun ensureLinks(targets: List<Device>) {
        // Drop links that are no longer relevant.
        val targetIds = targets.map { it.id }.toSet()
        for ((id, link) in links) {
            if (id !in targetIds) {
                link.close()
                links.remove(id, link)
            }
        }
        val missing = targets.filter { links[it.id]?.isReady != true }
        if (missing.isEmpty()) return

        val direct = targets.size == 1
        val jobs = missing.map { device ->
            scope.launch(Dispatchers.IO) {
                val link = Link(
                    scope = scope,
                    remoteDeviceId = device.id,
                    remoteHost = device.host,
                    remotePort = device.audioPort,
                    selfId = selfId,
                    selfName = { _state.value.myName },
                    channelProvider = { _state.value.channel },
                    directScope = direct,
                    events = linkEvents,
                )
                link.connect()
            }
        }
        jobs.forEach { it.join() }
    }

    private val linkEvents = object : Link.Events {
        override fun onLinkReady(link: Link) {
            links[link.remoteDeviceId] = link
            if (_state.value.phase is TalkPhase.Talking) {
                scope.launch(Dispatchers.IO) { link.sendFrame(WireTALK_START, ByteArray(0)) }
            }
        }

        override fun onLinkClosed(link: Link) {
            links.remove(link.remoteDeviceId, link)
        }

        override fun onBusyReceived(link: Link) {
            // Remote refused our transmission — half-duplex collision.
            if (_state.value.phase is TalkPhase.Talking) {
                WtLog.w(TAG, "Busy received — aborting transmission")
                feedback.errorBeep(currentSettings.soundEffectsEnabled)
                _state.update { s ->
                    val name = s.devices.firstOrNull { it.id == link.remoteDeviceId }?.name ?: ""
                    s.copy(phase = if (name.isBlank()) TalkPhase.Idle else TalkPhase.Busy(name))
                }
                capturer.stop()
                discovery?.sendTalkEvent(false)
                transmitJob?.cancel()
                scope.launch(Dispatchers.IO) {
                    for (l in links.values) {
                        if (l.isReady) l.sendFrame(WireTALK_STOP, ByteArray(0))
                    }
                }
            }
        }
    }

    private fun closeAllLinks() {
        for ((_, link) in links) link.close()
        links.clear()
    }

    // ------------------------------------------------------------------ public controls

    fun setChannel(channel: String) {
        val trimmed = channel.trim().take(SettingsRepository.MAX_CHANNEL_LENGTH)
        if (trimmed.isEmpty()) return
        if (trimmed.equals(_state.value.channel, ignoreCase = true)) return
        if (_state.value.phase is TalkPhase.Talking) stopTransmit()
        closeAllLinks()
        player.stop()
        _state.update { it.copy(channel = trimmed, scope = TalkScope.Channel, receivingFrom = null, audioLevel = 0f) }
        scope.launch {
            settings.setLastChannel(trimmed)
            delay(100)
            discovery?.restart() // announce with the new channel immediately
        }
    }

    fun setScope(scope: TalkScope) {
        if (_state.value.phase is TalkPhase.Talking) stopTransmit()
        _state.update { it.copy(scope = scope) }
    }

    fun clearScope() {
        if (_state.value.phase is TalkPhase.Talking) stopTransmit()
        _state.update { it.copy(scope = TalkScope.Channel) }
    }

    fun knownChannels(): List<Pair<String, Int>> =
        _state.value.devices
            .filter { it.status == DeviceStatus.ONLINE }
            .groupBy { it.channel }
            .map { (channel, devices) -> channel to devices.size }
            .sortedByDescending { it.second }

    private fun emitMessage(stringKey: String) {
        _events.tryEmit(EngineEvent.Message(stringKey))
    }

    private companion object {
        const val TAG = "Engine"
        const val SEQ_BYTES = 2

        // Frame type singletons to avoid re-allocation in hot paths.
        val WireAUDIO = WireTypeCache.audio()
        val WireTALK_START = WireTypeCache.talkStart()
        val WireTALK_STOP = WireTypeCache.talkStop()
    }
}

/** Tiny holder so the engine references frame types without importing protocol everywhere. */
private object WireTypeCache {
    fun audio() = com.wavetalk.app.transport.WireProtocol.FrameType.AUDIO
    fun talkStart() = com.wavetalk.app.transport.WireProtocol.FrameType.TALK_START
    fun talkStop() = com.wavetalk.app.transport.WireProtocol.FrameType.TALK_STOP
}
