package com.stormvr.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Cliente Bluetooth LE dos controles ESP32 ("StormVR-Ctl-R" / "-L"): procura o servico, conecta ate DOIS controles ao
 * mesmo tempo (um por vez, em fila), assina as notificacoes de KbtBlePacket (14 bytes) e alimenta o ControllerHub.
 * Reconecta sozinho. Os UUIDs estao em protocol.h do firmware.
 */
@SuppressLint("MissingPermission")
object BleController {
    private val SERVICE: UUID = UUID.fromString("7d0e0001-5354-4f52-4d56-525f43544c30")
    private val DATA: UUID = UUID.fromString("7d0e0002-5354-4f52-4d56-525f43544c30")
    private val CMD: UUID = UUID.fromString("7d0e0003-5354-4f52-4d56-525f43544c30")
    private val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val MAX_DEVICES = 2

    private val handler = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var running = false

    /** Dispositivos prontos (endereco -> gatt) e a caracteristica de comando de cada um. */
    private val gatts = LinkedHashMap<String, BluetoothGatt>()
    private val cmdChars = HashMap<String, BluetoothGattCharacteristic>()
    private var pending: String? = null            // conexao em andamento (uma por vez)

    fun permissionsNeeded(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermission(c: Context): Boolean =
        permissionsNeeded().all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun status(s: String) { ControllerHub.bleStatus = s }
    private fun refreshStatus() {
        val n = gatts.size
        status(
            when {
                n >= MAX_DEVICES -> "ESP32: 2 controles conectados por Bluetooth"
                n == 1 -> "ESP32: 1 controle conectado por Bluetooth; procurando o outro…"
                else -> "Procurando o ESP32 (StormVR-Ctl)…"
            }
        )
    }

    fun connectedCount(): Int = gatts.size

    fun start(c: Context) {
        if (running) return
        ctx = c
        if (!hasPermission(c)) {
            status("Bluetooth: sem permissão (tela principal > Permissões Bluetooth)")
            return
        }
        running = true
        scan()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
        for (g in gatts.values) { try { g.close() } catch (_: Exception) {} }
        gatts.clear(); cmdChars.clear(); pending = null
        status("Bluetooth: parado")
    }

    /** Envia um comando a todos os ESP32 conectados (1 = calibrar giroscopio, 2 = canal, 3 = mao: arg 0 direita / 1 esquerda). */
    @Suppress("DEPRECATION")
    fun sendCommand(cmd: Int, arg: Int) {
        for ((addr, g) in gatts) {
            val ch = cmdChars[addr] ?: continue
            ch.value = byteArrayOf(cmd.toByte(), arg.toByte())
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            g.writeCharacteristic(ch)
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { scanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    private fun scan() {
        val c = ctx ?: return
        if (!running || scanning) return
        if (gatts.size + (if (pending != null) 1 else 0) >= MAX_DEVICES) { refreshStatus(); return }
        val mgr = c.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val ad: BluetoothAdapter? = mgr.adapter
        if (ad == null || !ad.isEnabled) {
            status("Bluetooth desligado no celular")
            handler.postDelayed({ scan() }, 3000)
            return
        }
        val sc = ad.bluetoothLeScanner
        scanner = sc
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        refreshStatus()
        try { sc.startScan(listOf(filter), settings, scanCb); scanning = true } catch (e: Exception) { status("Falha ao procurar: " + e.message) }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val addr = result.device.address
            if (pending != null || gatts.containsKey(addr) || gatts.size >= MAX_DEVICES) return
            pending = addr
            stopScan()
            connect(result.device)
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            status("Falha na busca Bluetooth ($errorCode)")
            handler.postDelayed({ scan() }, 3000)
        }
    }

    private fun connect(d: BluetoothDevice) {
        val c = ctx ?: return
        status("Conectando ao ESP32…")
        d.connectGatt(c, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        // se nao conectar em 10 s, libera a fila
        val addr = d.address
        handler.postDelayed({
            if (pending == addr && !gatts.containsKey(addr)) { pending = null; scan() }
        }, 10000)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            val addr = g.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status("Conectado; configurando…")
                if (!g.requestMtu(64)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                try { g.close() } catch (_: Exception) {}
                gatts.remove(addr); cmdChars.remove(addr)
                if (pending == addr) pending = null
                ControllerHub.release("ble:$addr")
                refreshStatus()
                if (running) handler.postDelayed({ scan() }, 1000)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) { g.discoverServices() }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val addr = g.device.address
            val s = g.getService(SERVICE)
            val data = s?.getCharacteristic(DATA)
            if (data == null) { status("Serviço do StormVR não encontrado"); g.disconnect(); return }
            s?.getCharacteristic(CMD)?.let { cmdChars[addr] = it }
            gatts[addr] = g
            if (pending == addr) pending = null
            g.setCharacteristicNotification(data, true)
            val d = data.getDescriptor(CCC)
            if (d != null) {
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }
            refreshStatus()
            handler.postDelayed({ scan() }, 600)          // procura o segundo controle
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) { parse(g.device.address, value) }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val v = ch.value
            if (v != null) parse(g.device.address, v)
        }
    }

    private val t16Last = HashMap<String, Int>()
    private val t16Hi = HashMap<String, Int>()
    /** millis() do ESP32 vem com 16 bits (da a volta a cada 65 s): desenrola para um contador crescente. */
    private fun unwrapT16(addr: String, t16: Int): Int {
        val prev = t16Last[addr]
        var hi = t16Hi[addr] ?: 0
        if (prev != null && t16 < prev - 30000) hi += 65536
        t16Last[addr] = t16; t16Hi[addr] = hi
        return hi + t16
    }

    private fun parse(addr: String, v: ByteArray) {
        if (v.size < 14) return
        val bb = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN)
        if (v.size >= 16) {                                   // firmware novo: KbtBlePacketQ (quaternion int16 x 32767)
            val qb = bb.getShort(0).toInt() and 0xFFFF
            val qjx = ((bb.getShort(2).toInt() and 0xFFFF) - 2048) / 2048f
            val qjy = (2048 - (bb.getShort(4).toInt() and 0xFFFF)) / 2048f
            val q = floatArrayOf(bb.getShort(6) / 32767f, bb.getShort(8) / 32767f, bb.getShort(10) / 32767f, bb.getShort(12) / 32767f)
            val qf = v[14].toInt() and 0xFF
            val ts = if (v.size >= 18) unwrapT16(addr, bb.getShort(16).toInt() and 0xFFFF) else -1
            val ql = (qf and ControllerUdp.FLAG_HAND_LEFT) != 0
            val eul = ControllerUdp.quatToEuler(q[0], q[1], q[2], q[3])
            val qh = ControllerHub.submit("ble:$addr", ql, qb, qjx, qjy, eul[0], eul[1], eul[2], "ESP32 Bluetooth")
            if (qh != null) BtForward.onBle(qh, qb, qjx, qjy, eul[0], eul[1], eul[2], q, qf, ts)
            return
        }
        val buttons = bb.getShort(0).toInt() and 0xFFFF
        val jx = ((bb.getShort(2).toInt() and 0xFFFF) - 2048) / 2048f
        val jy = (2048 - (bb.getShort(4).toInt() and 0xFFFF)) / 2048f
        val roll = bb.getShort(6) / 100f
        val pitch = bb.getShort(8) / 100f
        val yaw = bb.getShort(10) / 100f
        val flags = v[12].toInt() and 0xFF
        val left = (flags and ControllerUdp.FLAG_HAND_LEFT) != 0
        val hand = ControllerHub.submit("ble:$addr", left, buttons, jx, jy, roll, pitch, yaw, "ESP32 Bluetooth")
        if (hand != null) BtForward.onBle(hand, buttons, jx, jy, roll, pitch, yaw)       // opcional: reenvia ao PC (ver BtForward.enabled)
    }
}
