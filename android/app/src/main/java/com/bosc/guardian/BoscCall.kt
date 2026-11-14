package com.bosc.guardian

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bosc.core.Speaker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Another phone running Bosc on the same Wi-Fi. */
data class Peer(val name: String, val host: String, val port: Int, val serviceName: String = name)

enum class CallPhase { IDLE, OUTGOING, INCOMING, ACTIVE, ENDED }

data class CallUi(
    val phase: CallPhase = CallPhase.IDLE,
    val peerName: String = "",
    /** This phone placed the call (shows the caller's script prompts). */
    val outgoing: Boolean = false,
    /** Incoming: the name was chosen by the caller and is not verified (scammers fake names). */
    val nameUnverified: Boolean = false,
    /** Incoming: the saved contact behind a chosen display name, if it differs. */
    val realName: String = "",
    val startedAt: Long = 0,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    /** Scam listening is running for this call (transcribing and scoring on this phone). */
    val listening: Boolean = false,
    val endedReason: String = "",
    /** What the other person is saying right now (partial transcript). */
    val partial: String = "",
)

/**
 * Bosc calls: real two-way voice calls between phones on the same Wi-Fi.
 *
 * Because Bosc places the call itself, it owns both audio streams. That is what lets scam
 * listening hear the other side, which Android never allows for SIM or WhatsApp calls.
 *
 * Wire format on one TCP connection: [type:1][length:2][payload]. Type 1 = control text
 * (HELLO|name, ACCEPT, DECLINE, BUSY, HANGUP), type 2 = 20 ms of 16 kHz mono PCM16.
 * Plain PCM over the LAN; a real product would use Opus and SRTP.
 */
object CallManager {
    private const val TAG = "BoscCall"
    const val DEFAULT_PORT = 47123
    private const val T_CONTROL = 1
    private const val T_AUDIO = 2
    private const val RATE = 16_000
    private const val FRAME_BYTES = 640 // 20 ms

    private lateinit var app: Context
    private val _ui = MutableStateFlow(CallUi())
    val ui: StateFlow<CallUi> = _ui.asStateFlow()

    private var server: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private val writeLock = Any()

    @Volatile private var record: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var mediaRunning = false
    @Volatile private var capture: DebugCapture? = null
    private var proximity: android.os.PowerManager.WakeLock? = null

    /** Screen off when the phone is at the ear (earpiece only), like a normal dialer: no cheek taps. */
    private fun setProximity(on: Boolean) {
        val pm = app.getSystemService(android.os.PowerManager::class.java)
        if (on) {
            if (proximity == null && pm.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximity = pm.newWakeLock(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "bosc:incall").apply { acquire(60 * 60 * 1000L) }
            }
        } else {
            runCatching { proximity?.release() }; proximity = null
        }
    }
    @Volatile private var remoteAsr: AsrStream? = null
    @Volatile private var localAsr: AsrStream? = null
    private var ringtone: Ringtone? = null

    val port get() = server?.localPort ?: 0

    fun init(context: Context) { if (!::app.isInitialized) app = context.applicationContext }

    // ---------------------------------------------------------------- server

    fun startServer(): Int {
        server?.let { return it.localPort }
        val ss = runCatching { ServerSocket(DEFAULT_PORT) }.getOrElse { ServerSocket(0) }
        server = ss
        Thread({
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                Thread({ handleIncoming(s) }, "bosc-call-in").start()
            }
        }, "bosc-call-server").start()
        Log.i(TAG, "listening on port ${ss.localPort}")
        return ss.localPort
    }

    fun stopServer() { runCatching { server?.close() }; server = null }

    private fun handleIncoming(s: Socket) {
        s.tcpNoDelay = true
        val input = DataInputStream(s.getInputStream().buffered())
        val o = DataOutputStream(s.getOutputStream().buffered())
        val hello = runCatching { readFrame(input) }.getOrNull()
        val host = s.inetAddress?.hostAddress ?: ""
        if (hello == null || hello.first != T_CONTROL) { s.close(); return }
        val msg = String(hello.second)
        if (msg.startsWith("SYNC|")) {
            // Contact sync: swap names and ports, both phones save each other.
            val (n, p) = parseNamePort(msg.removePrefix("SYNC|"))
            Contacts.save(app, Peer(n, host, p))
            runCatching { sendOn(o, T_CONTROL, "SYNC|${Prefs(app).myName}|$port".toByteArray()) }
            s.close(); return
        }
        if (!msg.startsWith("HELLO|")) { s.close(); return }
        val (name, callerPort) = parseNamePort(msg.removePrefix("HELLO|"))
        val shownAs = msg.removePrefix("HELLO|").split('|').getOrNull(2)?.trim().orEmpty()
        if (host.isNotBlank()) Contacts.save(app, Peer(name, host, callerPort))
        synchronized(this) {
            if (_ui.value.phase == CallPhase.OUTGOING || _ui.value.phase == CallPhase.INCOMING || _ui.value.phase == CallPhase.ACTIVE) {
                runCatching { sendOn(o, T_CONTROL, "BUSY".toByteArray()) }; s.close(); return
            }
            socket = s; out = o
            _ui.value = CallUi(phase = CallPhase.INCOMING, peerName = shownAs.ifBlank { name }, nameUnverified = true,
                realName = if (shownAs.isNotBlank() && shownAs != name) name else "")
        }
        Log.i(TAG, "incoming call from $name as '${shownAs.ifBlank { name }}'")
        ring(shownAs.ifBlank { name })
        readLoop(input, s)
    }

    // ---------------------------------------------------------------- outgoing

    fun call(peer: Peer) {
        if (_ui.value.phase == CallPhase.ACTIVE || _ui.value.phase == CallPhase.OUTGOING) return
        _ui.value = CallUi(phase = CallPhase.OUTGOING, peerName = peer.name, outgoing = true)
        Thread({
            try {
                val s = Socket()
                s.connect(InetSocketAddress(peer.host, peer.port), 4000)
                s.tcpNoDelay = true
                val o = DataOutputStream(s.getOutputStream().buffered())
                synchronized(this) { socket = s; out = o }
                val pr = Prefs(app)
                send(T_CONTROL, "HELLO|${pr.myName}|$port|${pr.showAs.replace('|', ' ')}".toByteArray())
                readLoop(DataInputStream(s.getInputStream().buffered()), s)
            } catch (e: Exception) {
                Log.w(TAG, "call failed", e)
                teardown("Couldn't reach ${peer.name}. Check both phones are on the same Wi-Fi.")
            }
        }, "bosc-call-out").start()
    }

    private fun parseNamePort(v: String): Pair<String, Int> {
        val parts = v.split('|')
        val name = parts[0].ifBlank { "Unknown" }
        val p = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: DEFAULT_PORT
        return name to p
    }

    /** One-time contact sync with a phone at a known address. Calls back on the main thread. */
    fun sync(host: String, peerPort: Int, onDone: (Peer?) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            val peer = runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, peerPort), 4000)
                    s.soTimeout = 4000
                    val o = DataOutputStream(s.getOutputStream().buffered())
                    sendOn(o, T_CONTROL, "SYNC|${Prefs(app).myName}|$port".toByteArray())
                    val (type, payload) = readFrame(DataInputStream(s.getInputStream().buffered()))
                    if (type != T_CONTROL || !String(payload).startsWith("SYNC|")) null
                    else parseNamePort(String(payload).removePrefix("SYNC|")).let { (n, p) -> Peer(n, host, p) }
                }
            }.getOrNull()
            peer?.let { Contacts.save(app, it) }
            main.post { onDone(peer) }
        }, "bosc-sync").start()
    }

    // ---------------------------------------------------------------- controls

    fun accept() {
        if (_ui.value.phase != CallPhase.INCOMING) return
        stopRinging()
        Thread({ runCatching { send(T_CONTROL, "ACCEPT".toByteArray()) }; startMedia() }, "bosc-accept").start()
    }

    fun decline() {
        stopRinging()
        Thread({ runCatching { send(T_CONTROL, "DECLINE".toByteArray()) }; teardown("Declined") }, "bosc-decline").start()
    }

    fun hangUp() {
        Thread({ runCatching { send(T_CONTROL, "HANGUP".toByteArray()) }; teardown("Call ended") }, "bosc-hangup").start()
    }

    fun toggleMute() = _ui.update { it.copy(muted = !it.muted) }

    fun toggleSpeaker() {
        val on = !_ui.value.speaker
        routeSpeaker(on)
        setProximity(!on)
        _ui.update { it.copy(speaker = on) }
    }

    /** Turn scam listening on or off, also mid-call. */
    fun setListening(on: Boolean) {
        Prefs(app).scamListening = on
        if (_ui.value.phase != CallPhase.ACTIVE) return
        if (on && remoteAsr == null) startListening() else if (!on && remoteAsr != null) stopListening()
    }

    // ---------------------------------------------------------------- protocol

    private fun readLoop(input: DataInputStream, s: Socket) {
        try {
            while (!s.isClosed) {
                val (type, payload) = readFrame(input)
                when (type) {
                    T_AUDIO -> onRemoteAudio(payload)
                    T_CONTROL -> when (val msg = String(payload)) {
                        "ACCEPT" -> startMedia()
                        "DECLINE" -> { teardown("${_ui.value.peerName} declined"); return }
                        "BUSY" -> { teardown("${_ui.value.peerName} is on another call"); return }
                        "HANGUP" -> { teardown("${_ui.value.peerName} hung up"); return }
                        else -> Log.d(TAG, "control $msg")
                    }
                }
            }
        } catch (_: EOFException) {
            if (socket === s) teardown("Call ended")
        } catch (e: Exception) {
            if (socket === s) teardown("Connection lost")
        }
    }

    private fun readFrame(input: DataInputStream): Pair<Int, ByteArray> {
        val type = input.readUnsignedByte()
        val len = input.readUnsignedShort()
        val buf = ByteArray(len)
        input.readFully(buf)
        return type to buf
    }

    private fun send(type: Int, payload: ByteArray, len: Int = payload.size) {
        val o = out ?: return
        synchronized(writeLock) { sendOn(o, type, payload, len) }
    }

    private fun sendOn(o: DataOutputStream, type: Int, payload: ByteArray, len: Int = payload.size) {
        o.writeByte(type); o.writeShort(len); o.write(payload, 0, len); o.flush()
    }

    // ---------------------------------------------------------------- media

    @SuppressLint("MissingPermission")
    private fun startMedia() {
        synchronized(this) {
            if (mediaRunning) return
            mediaRunning = true
        }
        val am = app.getSystemService(AudioManager::class.java)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        routeSpeaker(false)

        val outMin = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(outMin, FRAME_BYTES * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().apply { play() }

        val inMin = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val clean = Prefs(app).callMicMode == "clean"
        val source = if (clean) MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.VOICE_COMMUNICATION
        val rec = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(inMin, FRAME_BYTES * 8))
        // Echo cancelling always (the other side's voice must not come back); heavy noise suppression only in "call" mode.
        if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true }
        if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.enabled = !clean }
        Log.i(TAG, "mic mode ${if (clean) "clean (VOICE_RECOGNITION + AEC)" else "call (VOICE_COMMUNICATION)"}")
        record = rec
        capture = if (Prefs(app).debugCaptureAudio) DebugCapture.open(app, _ui.value.peerName) else null

        _ui.update { it.copy(phase = CallPhase.ACTIVE, startedAt = System.currentTimeMillis()) }
        setProximity(true)
        if (Prefs(app).scamListening) startListening()

        Thread({
            val buf = ByteArray(FRAME_BYTES)
            val silence = ByteArray(FRAME_BYTES)
            rec.startRecording()
            try {
                while (mediaRunning) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val muted = _ui.value.muted
                    send(T_AUDIO, if (muted) silence else buf, n)
                    if (!muted) localAsr?.feed(buf, n)
                }
            } catch (e: Exception) {
                if (mediaRunning) { Log.w(TAG, "send failed", e); teardown("Connection lost") }
            }
        }, "bosc-call-mic").start()
        Log.i(TAG, "media started")
    }

    private fun onRemoteAudio(pcm: ByteArray) {
        if (!mediaRunning) return
        capture?.write(pcm)
        track?.write(pcm, 0, pcm.size)
        remoteAsr?.feed(pcm, pcm.size)
    }

    private fun startListening() {
        GuardState.start("bosc call: ${_ui.value.peerName}")
        // The other side is the caller; this phone's own mic is "you". The trained model scores caller turns only.
        if (Prefs(app).asrEngine == "vosk") {
            VoskAsr.load(app)
            remoteAsr = VoskAsr.Stream("remote",
                onPartial = { p -> _ui.update { it.copy(partial = p) } },
                onFinal = { GuardState.addUtterance(Speaker.CALLER, it) })
            localAsr = VoskAsr.Stream("local", onPartial = {}, onFinal = { GuardState.addUtterance(Speaker.YOU, it, score = false) })
        } else {
            WhisperAsr.load(app)
            remoteAsr = WhisperAsr.Stream("remote",
                onSpeaking = { on -> _ui.update { it.copy(partial = if (on) "…" else "") } },
                onFinal = { GuardState.addUtterance(Speaker.CALLER, it) })
            localAsr = WhisperAsr.Stream("local", onSpeaking = {}, onFinal = { GuardState.addUtterance(Speaker.YOU, it, score = false) })
        }
        _ui.update { it.copy(listening = true) }
    }

    private fun stopListening() {
        remoteAsr?.close(); localAsr?.close(); remoteAsr = null; localAsr = null
        GuardState.end()
        _ui.update { it.copy(listening = false, partial = "") }
    }

    private fun routeSpeaker(on: Boolean) {
        val am = app.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
            else am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }?.let { am.setCommunicationDevice(it) }
                ?: am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
    }

    @Synchronized
    private fun teardown(reason: String) {
        if (_ui.value.phase == CallPhase.IDLE || _ui.value.phase == CallPhase.ENDED) return
        Log.i(TAG, "teardown: $reason")
        stopRinging()
        setProximity(false)
        mediaRunning = false
        runCatching { record?.stop() }; runCatching { record?.release() }; record = null
        runCatching { track?.stop() }; runCatching { track?.release() }; track = null
        if (remoteAsr != null) stopListening()
        capture?.close(); capture = null
        runCatching { socket?.close() }; socket = null; out = null
        val am = app.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
        am.mode = AudioManager.MODE_NORMAL
        _ui.update { it.copy(phase = CallPhase.ENDED, endedReason = reason, partial = "", listening = false) }
        Thread({ Thread.sleep(2500); if (_ui.value.phase == CallPhase.ENDED) _ui.value = CallUi() }, "bosc-call-reset").start()
    }

    // ---------------------------------------------------------------- ringing

    private fun ring(name: String) {
        runCatching {
            ringtone = RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        }
        val open = PendingIntent.getActivity(app, 3, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(app, BoscApp.CH_CALL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("$name is calling")
            .setContentText("Bosc call on Wi-Fi")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { app.getSystemService(NotificationManager::class.java).notify(RING_ID, n) }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }; ringtone = null
        app.getSystemService(NotificationManager::class.java).cancel(RING_ID)
    }

    private const val RING_ID = 3001
}

/** Finds other phones running Bosc on the same Wi-Fi (mDNS / DNS-SD) and advertises this one. */
object Nearby {
    private const val TAG = "BoscNearby"
    private const val TYPE = "_bosccall._tcp."

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()
    private val _myAddress = MutableStateFlow("")
    val myAddress: StateFlow<String> = _myAddress.asStateFlow()

    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var myServiceName: String? = null
    private var lock: WifiManager.MulticastLock? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start(context: Context, name: String, port: Int) {
        val ctx = context.applicationContext
        if (nsd != null) return
        val m = ctx.getSystemService(NsdManager::class.java)
        nsd = m
        _myAddress.value = localIpv4(ctx)?.let { "$it:$port" } ?: ""
        startBeacon(name, port)
        lock = ctx.getSystemService(WifiManager::class.java).createMulticastLock("bosc-nsd").apply { setReferenceCounted(false); acquire() }

        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { myServiceName = info.serviceName; Log.i(TAG, "registered as ${info.serviceName}") }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { Log.w(TAG, "register failed $code") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        registration = reg
        m.registerService(NsdServiceInfo().apply { serviceName = name; serviceType = TYPE; setPort(port) }, NsdManager.PROTOCOL_DNS_SD, reg)

        val disc = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { Log.i(TAG, "discovering") }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { Log.w(TAG, "discovery failed $code") }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == myServiceName) return
                synchronized(resolveQueue) { resolveQueue.addLast(info) }
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                _peers.update { list -> list.filterNot { it.serviceName == info.serviceName } }
            }
        }
        discovery = disc
        m.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, disc)
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val m = nsd ?: return
        val next = synchronized(resolveQueue) {
            if (resolving || resolveQueue.isEmpty()) return
            resolving = true
            resolveQueue.removeFirst()
        }
        m.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { resolving = false; resolveNext() }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                if (host != null && info.serviceName != myServiceName && !host.startsWith("127.") && !isMine(host)) {
                    val peer = Peer(info.serviceName, host, info.port)
                    _peers.update { list -> (list.filterNot { it.serviceName == peer.serviceName } + peer).sortedBy { it.name.lowercase() } }
                    Log.i(TAG, "found ${peer.name} at $host:${info.port}")
                }
                resolving = false; resolveNext()
            }
        })
    }

    /** Search again (pull-to-refresh style). */
    fun refresh(context: Context, name: String, port: Int) { stop(); _peers.value = emptyList(); start(context, name, port) }

    // UDP broadcast beacon: works on networks that filter mDNS multicast but pass broadcast.
    private const val BEACON_PORT = 47124
    @Volatile private var beaconRunning = false
    private var beaconSocket: java.net.DatagramSocket? = null

    private fun startBeacon(name: String, port: Int) {
        if (beaconRunning) return
        beaconRunning = true
        val sock = runCatching {
            java.net.DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(BEACON_PORT)) }
        }.getOrNull() ?: run { beaconRunning = false; return }
        beaconSocket = sock
        Thread({
            val buf = ByteArray(512)
            while (beaconRunning) {
                val pkt = java.net.DatagramPacket(buf, buf.size)
                runCatching { sock.receive(pkt) }.onFailure { if (!beaconRunning) return@Thread } .getOrNull() ?: continue
                val msg = String(pkt.data, 0, pkt.length)
                val host = pkt.address?.hostAddress ?: continue
                if (!msg.startsWith("BOSC|") || isMine(host)) continue
                val parts = msg.split('|')
                if (parts.size < 3) continue
                val peer = Peer(parts[1], host, parts[2].toIntOrNull() ?: CallManager.DEFAULT_PORT, "beacon:${parts[1]}")
                if (_peers.value.none { it.host == peer.host && it.name == peer.name }) {
                    _peers.update { list -> (list.filterNot { it.host == peer.host } + peer).sortedBy { it.name.lowercase() } }
                    Log.i(TAG, "found ${peer.name} at $host (broadcast)")
                }
            }
        }, "bosc-beacon-rx").start()
        Thread({
            while (beaconRunning) {
                val payload = "BOSC|$name|$port".toByteArray()
                for (addr in broadcastAddresses()) runCatching { sock.send(java.net.DatagramPacket(payload, payload.size, addr, BEACON_PORT)) }
                Thread.sleep(3000)
            }
        }, "bosc-beacon-tx").start()
    }

    private fun stopBeacon() { beaconRunning = false; runCatching { beaconSocket?.close() }; beaconSocket = null }

    private fun broadcastAddresses(): List<java.net.InetAddress> =
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses.mapNotNull { a -> a.broadcast } }
            .plus(java.net.InetAddress.getByName("255.255.255.255")).distinct()

    private fun isMine(host: String) = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .any { nif -> nif.inetAddresses.toList().any { it.hostAddress == host } }

    fun stop() {
        stopBeacon()
        val m = nsd ?: return
        runCatching { discovery?.let { m.stopServiceDiscovery(it) } }
        runCatching { registration?.let { m.unregisterService(it) } }
        runCatching { lock?.release() }
        discovery = null; registration = null; nsd = null; lock = null; myServiceName = null
        synchronized(resolveQueue) { resolveQueue.clear(); resolving = false }
    }

    /**
     * This phone's address on the local network: Wi-Fi, or its own hotspot. Mobile-data addresses
     * are skipped (the active network may be mobile data while the phone is a hotspot).
     */
    @Suppress("UNUSED_PARAMETER")
    fun localIpv4(context: Context): String? {
        val candidates = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
            .filter { (_, a) -> a.isSiteLocalAddress }
        val preferred = candidates.sortedBy { (name, _) ->
            when {
                name.startsWith("wlan") -> 0
                name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("softap") -> 1
                name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("v4-") -> 9
                else -> 5
            }
        }
        return preferred.firstOrNull { (name, _) -> !name.startsWith("rmnet") && !name.startsWith("ccmni") && !name.startsWith("v4-") }?.second?.hostAddress
    }
}


/** Saved Bosc contacts (synced by address, by broadcast, or by a call). Stored on this phone only. */
object Contacts {
    private val _saved = MutableStateFlow<List<Peer>>(emptyList())
    val saved: StateFlow<List<Peer>> = _saved.asStateFlow()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences("bosc_contacts", Context.MODE_PRIVATE).getString("list", "") ?: ""
        _saved.value = raw.lines().mapNotNull { line ->
            val p = line.split('\t'); if (p.size < 3) null else Peer(p[0], p[1], p[2].toIntOrNull() ?: CallManager.DEFAULT_PORT)
        }
    }

    @Synchronized
    fun save(context: Context, peer: Peer) {
        load(context)
        val clean = peer.copy(name = peer.name.replace('\t', ' ').replace('\n', ' '), serviceName = peer.name)
        val list = (_saved.value.filterNot { it.name == clean.name || it.host == clean.host } + clean).sortedBy { it.name.lowercase() }
        _saved.value = list
        context.getSharedPreferences("bosc_contacts", Context.MODE_PRIVATE).edit()
            .putString("list", list.joinToString("\n") { "${it.name}\t${it.host}\t${it.port}" }).apply()
    }

    fun remove(context: Context, peer: Peer) {
        val list = _saved.value.filterNot { it.host == peer.host && it.name == peer.name }
        _saved.value = list
        context.getSharedPreferences("bosc_contacts", Context.MODE_PRIVATE).edit()
            .putString("list", list.joinToString("\n") { "${it.name}\t${it.host}\t${it.port}" }).apply()
    }
}


/**
 * Debug only, off by default: writes the audio received from the other phone to
 * <external files>/debug_audio/<time>_<peer>.wav (16 kHz mono PCM16), so speech-to-text can be benchmarked
 * on exactly what it heard. For the team's own test calls. Delete the files after measuring.
 */
class DebugCapture private constructor(private val file: java.io.File) {
    private val out = java.io.RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
    private var bytes = 0L

    @Synchronized fun write(pcm: ByteArray) { out.write(pcm); bytes += pcm.size }

    @Synchronized fun close() {
        fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        out.seek(0)
        out.write("RIFF".toByteArray()); out.write(le32(36 + bytes)); out.write("WAVEfmt ".toByteArray())
        out.write(le32(16)); out.write(le16(1)); out.write(le16(1)); out.write(le32(16000)); out.write(le32(32000))
        out.write(le16(2)); out.write(le16(16)); out.write("data".toByteArray()); out.write(le32(bytes))
        out.close()
        Log.i("BoscCall", "debug capture saved ${file.absolutePath} (${bytes / 32000}s)")
    }

    companion object {
        fun open(context: Context, peer: String): DebugCapture? = runCatching {
            val dir = java.io.File(context.getExternalFilesDir(null), "debug_audio").apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            DebugCapture(java.io.File(dir, "${stamp}_${peer.replace(Regex("[^A-Za-z0-9]+"), "_")}.wav"))
        }.getOrNull()
    }
}
