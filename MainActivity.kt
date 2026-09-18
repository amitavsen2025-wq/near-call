package com.nearcall.v2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Tasks
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.*

class MainActivity : AppCompatActivity() {
    companion object {
        const val SERVICE_ID = "com.nearcall.v2.mesh"
        const val REQ = 90
        const val TYPE_HELLO = 1
        const val TYPE_TOPO = 2
        const val TYPE_CHAT = 3
        const val TYPE_INVITE = 4
        const val TYPE_ACCEPT = 5
        const val TYPE_AUDIO = 6
        const val TYPE_END = 7
        const val HEADER_MAGIC = 0x4E43
    }

    private val strategy = Strategy.P2P_CLUSTER
    private lateinit var connections: ConnectionsClient
    private lateinit var status: TextView
    private lateinit var peersText: TextView
    private lateinit var chatBox: TextView
    private lateinit var input: EditText
    private val handler = Handler(Looper.getMainLooper())
    private val endpointByNode = ConcurrentHashMap<String, String>()
    private val nodeByEndpoint = ConcurrentHashMap<String, String>()
    private val names = ConcurrentHashMap<String, String>()
    private val graph = ConcurrentHashMap<String, MutableSet<String>>()
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val pendingNames = ConcurrentHashMap<String, String>()
    private val prefs by lazy { getSharedPreferences("nearcall", Context.MODE_PRIVATE) }
    private val nodeId by lazy { prefs.getString("node_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("node_id", it).apply() } }
    private val displayName by lazy { prefs.getString("name", null) ?: "User-${nodeId.take(4)}" }

    @Volatile private var running = false
    @Volatile private var callId: String? = null
    @Volatile private var callPeer: String? = null
    @Volatile private var inCall = false
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var audioThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(makeUi())
        connections = Nearby.getConnectionsClient(this)
        requestPermissionsIfNeeded()
    }

    private fun makeUi(): LinearLayout {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { textSize = 18f; text = "Starting…" }
        peersText = TextView(this).apply { text = "No nearby users"; textSize = 16f }
        chatBox = TextView(this).apply { text = "Chat\n"; textSize = 16f; setPadding(0, 16, 0, 16) }
        input = EditText(this).apply { hint = "Message" }
        val start = Button(this).apply { text = "Start / Refresh Mesh"; setOnClickListener { startMesh() } }
        val stop = Button(this).apply { text = "Stop Mesh"; setOnClickListener { stopMesh() } }
        val send = Button(this).apply { text = "Send Message"; setOnClickListener { val s = input.text.toString().trim(); if (s.isNotEmpty()) { sendChat(s); input.setText("") } } }
        val call = Button(this).apply { text = "Call selected user"; setOnClickListener { showCallPicker() } }
        root.addView(status); root.addView(peersText); root.addView(start); root.addView(stop); root.addView(chatBox)
        root.addView(input); root.addView(send); root.addView(call)
        return root
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 31) permissions += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else permissions += Manifest.permission.ACCESS_FINE_LOCATION
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ) else startMesh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQ && results.all { it == PackageManager.PERMISSION_GRANTED }) startMesh()
        else status.text = "Bluetooth/Microphone permissions are required."
    }

    private fun startMesh() {
        if (running) return
        running = true
        status.text = "Mesh running • $displayName • ${nodeId.take(8)}"
        val advertising = connections.startAdvertising(
            nodeId.toByteArray(), SERVICE_ID,
            connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        )
        val discovery = connections.startDiscovery(
            SERVICE_ID, endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        )
        Tasks.whenAllComplete(advertising, discovery).addOnFailureListener { e -> status.text = "Start failed: ${e.message}" }
        handler.postDelayed({ broadcastTopology() }, 2500)
    }

    private fun stopMesh() {
        running = false
        connections.stopAdvertising(); connections.stopDiscovery(); connections.stopAllEndpoints()
        endpointByNode.clear(); nodeByEndpoint.clear(); names.clear(); graph.clear(); updatePeers()
        stopCall(false)
        status.text = "Mesh stopped"
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val remoteNode = String(info.endpointInfo, Charsets.UTF_8)
            if (remoteNode == nodeId) return
            pendingNames[endpointId] = "User-${remoteNode.take(4)}"
            connections.requestConnection(nodeId.toByteArray(), endpointId, connectionLifecycleCallback)
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connections.acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                val remoteNode = pendingNames.remove(endpointId) ?: endpointId
                endpointByNode[remoteNode] = endpointId
                nodeByEndpoint[endpointId] = remoteNode
                names[remoteNode] = "User-${remoteNode.take(4)}"
                graph.computeIfAbsent(nodeId) { ConcurrentHashMap.newKeySet() }.add(remoteNode)
                graph.computeIfAbsent(remoteNode) { ConcurrentHashMap.newKeySet() }.add(nodeId)
                updatePeers(); sendHello(endpointId); broadcastTopology()
            }
        }
        override fun onDisconnected(endpointId: String) {
            val n = nodeByEndpoint.remove(endpointId) ?: return
            endpointByNode.remove(n)
            graph[nodeId]?.remove(n); graph[n]?.remove(nodeId)
            updatePeers(); broadcastTopology()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            handlePacket(bytes)
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun sendHello(endpointId: String) {
        sendToEndpoint(endpointId, packet(TYPE_HELLO, UUID.randomUUID().toString(), nodeId, nodeId, 1, displayName.toByteArray()))
    }

    private fun handlePacket(bytes: ByteArray) {
        if (bytes.size < 2 + 1 + 1 + 16 + 36 + 36 + 4 + 1) return
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (b.short.toInt() != HEADER_MAGIC) return
        val type = b.get().toInt(); val ttl = b.get().toInt()
        val id = readFixed(b, 16); val src = readFixed(b, 36); val dst = readFixed(b, 36); val seq = b.int; val len = b.get().toInt() and 255
        if (len > b.remaining()) return
        val data = ByteArray(len); b.get(data)
        if (!seen.add(id)) return
        when (type) {
            TYPE_HELLO -> {
                names[src] = String(data, Charsets.UTF_8); updatePeers()
            }
            TYPE_TOPO -> {
                val parts = String(data, Charsets.UTF_8).split("|")
                if (parts.size >= 2) {
                    graph[parts[0]] = ConcurrentHashMap.newKeySet<String>().apply { addAll(parts[1].split(',').filter { it.isNotBlank() }) }
                    if (ttl > 0) floodRaw(bytes, endpointId)
                }
            }
            TYPE_CHAT -> {
                if (dst == nodeId) appendChat("${names[src] ?: "${src.take(4)}"}: ${String(data, Charsets.UTF_8)}") else if (ttl > 0) forward(type, id, src, dst, ttl, seq, data)
            }
            TYPE_INVITE -> {
                if (dst == nodeId) incomingInvite(src, String(data, Charsets.UTF_8)) else if (ttl > 0) forward(type, id, src, dst, ttl, seq, data)
            }
            TYPE_ACCEPT -> {
                if (dst == nodeId) { callId = String(data, Charsets.UTF_8); callPeer = src; startAudio() } else if (ttl > 0) forward(type, id, src, dst, ttl, seq, data)
            }
            TYPE_AUDIO -> {
                if (dst == nodeId) playAudio(data) else if (ttl > 0) forward(type, id, src, dst, ttl, seq, data)
            }
            TYPE_END -> {
                if (dst == nodeId) stopCall(false) else if (ttl > 0) forward(type, id, src, dst, ttl, seq, data)
            }
        }
    }

    private fun forward(type: Int, id: String, src: String, dst: String, ttl: Int, seq: Int, data: ByteArray) {
        val next = nextHop(dst)
        if (next != null) sendToNode(next, packet(type, id, src, dst, ttl - 1, seq, data))
        else if (type == TYPE_CHAT) flood(type, id, src, dst, ttl - 1, seq, data)
    }

    private fun sendChat(text: String) {
        val target = chooseTarget() ?: run { appendChat("System: no known users"); return }
        val p = packet(TYPE_CHAT, UUID.randomUUID().toString(), nodeId, target, 10, 0, text.toByteArray())
        seen.add(readPacketId(p)); sendToNode(target, p)
    }

    private fun chooseTarget(): String? = allKnownNodes().firstOrNull { it != nodeId }

    private fun allKnownNodes(): List<String> = (endpointByNode.keys + graph.keys + graph.values.flatten() + names.keys).filter { it != nodeId }.distinct()

    private fun showCallPicker() {
        val users = allKnownNodes()
        if (users.isEmpty()) { appendChat("System: no connected users"); return }
        val labels = users.map { names[it] ?: "User-${it.take(4)}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Call user").setItems(labels) { _, which -> startCall(users[which]) }.show()
    }

    private fun startCall(target: String) {
        if (inCall) return
        val cid = UUID.randomUUID().toString(); callId = cid; callPeer = target; inCall = true
        appendChat("Calling ${names[target] ?: target.take(4)}…")
        val p = packet(TYPE_INVITE, cid, nodeId, target, 10, 0, cid.toByteArray())
        seen.add(readPacketId(p)); sendToNode(target, p)
        handler.postDelayed({ if (inCall && callId == cid) appendChat("Call waiting…") }, 1000)
    }

    private fun incomingInvite(src: String, cid: String) {
        if (inCall) return
        AlertDialog.Builder(this).setTitle("Incoming call").setMessage("${names[src] ?: src.take(4)} is calling")
            .setPositiveButton("Answer") { _, _ ->
                callId = cid; callPeer = src; inCall = true
                sendToNode(src, packet(TYPE_ACCEPT, cid, nodeId, src, 10, 0, cid.toByteArray()))
                startAudio()
            }.setNegativeButton("Decline", null).show()
    }

    private fun startAudio() {
        if (!inCall) return
        try {
            val rate = 8000; val minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val minOut = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minIn, 2048))
            player = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(max(minOut, 2048)).setTransferMode(AudioTrack.MODE_STREAM).build()
            recorder!!.startRecording(); player!!.play()
            audioThread = Thread { audioLoop() }.also { it.start() }
            appendChat("Voice call connected. Tap End Call in notification/status bar or close app to stop.")
        } catch (e: Exception) { appendChat("Audio error: ${e.message}"); stopCall(false) }
    }

    private fun audioLoop() {
        val pcm = ShortArray(160); val encoded = ByteArray(160); var seq = 0
        while (inCall) {
            val n = recorder?.read(pcm, 0, pcm.size) ?: -1
            if (n <= 0) continue
            for (i in 0 until n) encoded[i] = pcmToMuLaw(pcm[i])
            val data = encoded.copyOf(n)
            val peer = callPeer ?: continue
            val cid = callId ?: continue
            val p = packet(TYPE_AUDIO, cid, nodeId, peer, 10, seq++, data)
            seen.add(readPacketId(p)); sendToNode(peer, p)
        }
    }

    private fun playAudio(mu: ByteArray) {
        val pcm = ShortArray(mu.size)
        for (i in mu.indices) pcm[i] = muLawToPcm(mu[i])
        try { player?.write(pcm, 0, pcm.size) } catch (_: Exception) {}
    }

    private fun stopCall(sendEnd: Boolean) {
        val peer = callPeer; val cid = callId
        if (sendEnd && peer != null && cid != null) sendToNode(peer, packet(TYPE_END, cid, nodeId, peer, 10, 0, ByteArray(0)))
        inCall = false; callPeer = null; callId = null
        try { recorder?.stop() } catch (_: Exception) {}; try { recorder?.release() } catch (_: Exception) {}; recorder = null
        try { player?.stop() } catch (_: Exception) {}; try { player?.release() } catch (_: Exception) {}; player = null
    }

    private fun sendToNode(node: String, bytes: ByteArray) {
        val next = nextHop(node)
        if (next != null && endpointByNode[next] != null) sendToEndpoint(endpointByNode[next]!!, bytes)
        else floodRaw(bytes, null)
    }
    private fun sendToEndpoint(endpoint: String, bytes: ByteArray) { connections.sendPayload(endpoint, Payload.fromBytes(bytes)) }

    private fun flood(type: Int, id: String, src: String, dst: String, ttl: Int, seq: Int, data: ByteArray) {
        if (ttl <= 0) return
        val p = packet(type, id, src, dst, ttl, seq, data); floodRaw(p, null)
    }

    private fun floodRaw(bytes: ByteArray, exceptEndpoint: String?) {
        endpointByNode.values.forEach { ep -> if (ep != exceptEndpoint) connections.sendPayload(ep, Payload.fromBytes(bytes)) }
    }

    private fun broadcastTopology() {
        val local = graph[nodeId]?.toList()?.joinToString(",") ?: ""
        val data = "$nodeId|$local".toByteArray()
        val p = packet(TYPE_TOPO, UUID.randomUUID().toString(), nodeId, "", 5, 0, data); seen.add(readPacketId(p))
        endpointByNode.values.forEach { sendToEndpoint(it, p) }
        if (running) handler.postDelayed({ broadcastTopology() }, 5000)
    }

    private fun nextHop(destination: String): String? {
        if (destination == nodeId) return nodeId
        if (endpointByNode.containsKey(destination)) return destination
        val q = ArrayDeque<String>(); val prev = HashMap<String, String?>(); q.add(nodeId); prev[nodeId] = null
        while (q.isNotEmpty()) { val cur = q.removeFirst(); for (n in graph[cur].orEmpty()) if (!prev.containsKey(n)) { prev[n] = cur; q.add(n); if (n == destination) break } }
        if (!prev.containsKey(destination)) return null
        var cur = destination; while (prev[cur] != nodeId) cur = prev[cur] ?: return null
        return cur
    }

    private fun updatePeers() { runOnUiThread { peersText.text = if (endpointByNode.isEmpty()) "No connected users" else endpointByNode.keys.joinToString("\n") { "🟢 ${names[it] ?: "User-${it.take(4)}"}  (${it.take(8)})" } } }
    private fun appendChat(s: String) { runOnUiThread { chatBox.append("\n$s") } }

    private fun packet(type: Int, id: String, src: String, dst: String, ttl: Int, seq: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(); val b = ByteBuffer.allocate(2 + 1 + 1 + 16 + 36 + 36 + 4 + 1).order(ByteOrder.BIG_ENDIAN)
        b.putShort(HEADER_MAGIC.toShort()).put(type.toByte()).put(ttl.coerceIn(0, 255).toByte())
        b.put(id.replace("-", "").padEnd(16, '0').take(16).toByteArray())
        b.put(src.padEnd(36, '0').take(36).toByteArray()); b.put(dst.padEnd(36, '0').take(36).toByteArray())
        b.putInt(seq); b.put(data.size.coerceAtMost(255).toByte()); out.write(b.array()); out.write(data, 0, data.size.coerceAtMost(255)); return out.toByteArray()
    }
    private fun readFixed(b: ByteBuffer, n: Int): String = ByteArray(n).also { b.get(it) }.toString(Charsets.UTF_8).trim('\u0000', '0', ' ')
    private fun readPacketId(bytes: ByteArray): String = if (bytes.size >= 20) bytes.copyOfRange(4, 20).toString(Charsets.UTF_8) else UUID.randomUUID().toString()

    private fun pcmToMuLaw(sample: Short): Byte {
        var s = sample.toInt(); val sign = if (s < 0) 0x80 else 0; if (s < 0) s = -s; s = min(s, 32635)
        var exponent = 7; var mask = 0x4000; while (exponent > 0 && (s and mask) == 0) { exponent--; mask = mask shr 1 }
        val mantissa = (s shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa xor 0xFF).toByte()
    }
    private fun muLawToPcm(mu: Byte): Short {
        val u = mu.toInt() and 0xFF xor 0xFF; val sign = u and 0x80; val exponent = (u shr 4) and 7; val mantissa = u and 15
        var sample = ((mantissa shl 3) + 132) shl exponent; sample -= 132; return (if (sign != 0) -sample else sample).toShort()
    }

    override fun onDestroy() { stopMesh(); super.onDestroy() }
}
