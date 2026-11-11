package com.waltsteb.z607control

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val bluetoothPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        ) else emptyArray()

    private val devices = mutableStateListOf<BluetoothDevice>()
    private var status by mutableStateOf("Listo para buscar dispositivos")
    private var scanning by mutableStateOf(false)
    private lateinit var adapter: BluetoothAdapter
    private lateinit var audioManager: AudioManager

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val audioFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        fileChooserCallback?.onReceiveValue(uris.toTypedArray())
        fileChooserCallback = null
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) refreshPairedAndScan()
        else status = "Permisos Bluetooth no concedidos. Puedes activarlos en Ajustes."
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let { if (devices.none { d -> d.address == it.address }) devices.add(it) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanning = false
                    status = "Búsqueda terminada. Selecciona tu Logitech Z607."
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        adapter = manager.adapter
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF43D8E8),
                background = androidx.compose.ui.graphics.Color(0xFF0B1119),
                surface = androidx.compose.ui.graphics.Color(0xFF172331)
            )) {
                ControlScreen()
            }
        }
    }

    private fun hasBluetoothPermissions(): Boolean =
        Build.VERSION.SDK_INT < 31 || bluetoothPermissions.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun refreshPairedAndScan() {
        if (!hasBluetoothPermissions()) return
        try {
            devices.clear()
            adapter.bondedDevices?.forEach { devices.add(it) }
            if (!adapter.isEnabled) {
                status = "Activa Bluetooth para continuar."
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                return
            }
            adapter.cancelDiscovery()
            scanning = adapter.startDiscovery()
            status = if (scanning) "Buscando dispositivos cercanos…" else "No se pudo iniciar la búsqueda. Abre Ajustes Bluetooth."
        } catch (_: SecurityException) {
            status = "Android no concedió el permiso Bluetooth."
        }
    }

    private fun requestBluetoothAndScan() {
        if (adapter == null) {
            status = "Este teléfono no informa compatibilidad con Bluetooth."
            return
        }
        if (hasBluetoothPermissions()) refreshPairedAndScan()
        else if (Build.VERSION.SDK_INT >= 31) permissionLauncher.launch(bluetoothPermissions)
        else refreshPairedAndScan()
    }

    private fun pairDevice(device: BluetoothDevice) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(bluetoothPermissions)
                return
            }
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                status = "Dispositivo emparejado. Abre Ajustes para elegirlo como salida de audio."
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } else {
                status = "Solicitando emparejamiento a Android… confirma el aviso del sistema."
                device.createBond()
            }
        } catch (_: Exception) {
            status = "No se pudo emparejar. Inténtalo desde Ajustes Bluetooth."
        }
    }

    private fun openBluetoothSettings() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    private fun setMediaVolume(value: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value.toInt().coerceIn(0, max), 0)
    }

    private inner class PlayerBridge {
        @JavascriptInterface
        fun openBluetoothSettings() {
            runOnUiThread { openBluetoothSettings() }
        }
    }

    @Composable
    private fun ControlScreen() {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        var volume by remember { mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text("Z607 SMART CONTROL PRO", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text("CONTROL BLUETOOTH • VOLUMEN", style = MaterialTheme.typography.labelSmall)
                }
                item {
                    Text("REPRODUCTOR DE MÚSICA", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().height(980.dp),
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = true
                                settings.allowContentAccess = true
                                settings.mediaPlaybackRequiresUserGesture = true
                                addJavascriptInterface(PlayerBridge(), "Z607Android")
                                webViewClient = WebViewClient()
                                webChromeClient = object : WebChromeClient() {
                                    override fun onShowFileChooser(
                                        webView: WebView?,
                                        filePathCallback: ValueCallback<Array<Uri>>?,
                                        fileChooserParams: FileChooserParams?
                                    ): Boolean {
                                        this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                                        this@MainActivity.fileChooserCallback = filePathCallback
                                        audioFilePicker.launch(arrayOf("audio/*"))
                                        return true
                                    }
                                }
                                loadUrl("file:///android_asset/index.html")
                            }
                        }
                    )
                }
                item {
                    Card {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("CONEXIÓN BLUETOOTH", style = MaterialTheme.typography.titleMedium)
                            Text("Busca tu Logitech Z607 y solicita el emparejamiento mediante Android.")
                            Button(onClick = { requestBluetoothAndScan() }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (scanning) "BUSCANDO…" else "BUSCAR DISPOSITIVOS")
                            }
                            OutlinedButton(onClick = { openBluetoothSettings() }, modifier = Modifier.fillMaxWidth()) {
                                Text("ABRIR AJUSTES BLUETOOTH")
                            }
                            Text(status, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("DISPOSITIVOS ENCONTRADOS", style = MaterialTheme.typography.titleMedium)
                            Text("Toca un dispositivo para emparejarlo o abrir sus ajustes.")
                        }
                    }
                }
                items(devices, key = { it.address }) { device ->
                    val name = try { device.name ?: "Dispositivo Bluetooth" } catch (_: SecurityException) { "Dispositivo Bluetooth" }
                    Card(onClick = { pairDevice(device) }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(name, style = MaterialTheme.typography.titleSmall)
                            Text(if (device.bondState == BluetoothDevice.BOND_BONDED) "Emparejado" else "Toca para emparejar", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("VOLUMEN EXTRA", style = MaterialTheme.typography.titleMedium)
                            Text("Volumen multimedia del teléfono: ${volume.toInt()} / $maxVolume")
                            Slider(value = volume, onValueChange = {
                                volume = it
                                setMediaVolume(it)
                            }, valueRange = 0f..maxVolume.toFloat())
                            Text("Este control lleva el volumen del sistema hasta su máximo. No aumenta la potencia física del Z607 ni supera el límite de Android.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Text("Nota: Android administra el audio Bluetooth. La app no puede encender el parlante ni cambiar sus fuentes AUX/USB/FM.", style = MaterialTheme.typography.bodySmall)
                    Text("Z607 SMART CONTROL PRO • Reproductor integrado", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    override fun onDestroy() {
        try { adapter.cancelDiscovery() } catch (_: Exception) {}
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
