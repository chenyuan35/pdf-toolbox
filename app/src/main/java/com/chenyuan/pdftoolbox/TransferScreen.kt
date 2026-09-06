package com.chenyuan.pdftoolbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

/**
 * Local IPv4 of this device. Prefers the WiFi network (a VPN or mobile interface
 * would give the PC an unreachable address). NetworkInterface enumeration is
 * unreliable on Android 11+ (netlink is restricted for apps), so ask
 * ConnectivityManager first.
 */
private fun wifiIpv4(context: Context): String? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
    if (cm != null) {
        var fallback: String? = null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val props = cm.getLinkProperties(network) ?: continue
            val addr4 = props.linkAddresses
                .mapNotNull { it.address as? Inet4Address }
                .firstOrNull { !it.isLoopbackAddress } ?: continue
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return addr4.hostAddress
            if (fallback == null) fallback = addr4.hostAddress
        }
        if (fallback != null) return fallback
    }
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
    for (nif in interfaces) {
        for (addr in nif.inetAddresses) {
            val host = addr.hostAddress ?: continue
            if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) return host
        }
    }
    return null
}

private fun randomKey(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf("") }
    var sharedFiles by remember { mutableStateOf(listOf<Uri>()) }
    var receivedVersion by remember { mutableStateOf(0) }
    var server by remember { mutableStateOf<TransferServer?>(null) }
    var pendingSave by remember { mutableStateOf<File?>(null) }
    val key = remember { randomKey() }
    val receivedDir = remember { File(context.getExternalFilesDir(null), "Received") }

    val receivedFiles = remember(receivedVersion) {
        receivedDir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
    }

    fun buildTransferFiles(): List<TransferFile> {
        val shared = sharedFiles.mapNotNull { uri ->
            runCatching {
                val name = PdfEngine.fileName(context, uri)
                TransferFile(name) { context.contentResolver.openInputStream(uri)!! }
            }.getOrNull()
        }
        val received = receivedDir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
            .map { f -> TransferFile(f.name) { f.inputStream() } }
        return shared + received
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            sharedFiles = uris
        }
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        val src = pendingSave ?: return@rememberLauncherForActivityResult
        if (out != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(out)!!.use { o ->
                            src.inputStream().use { it.copyTo(o) }
                        }
                    }.isSuccess
                }
                Toast.makeText(context, if (ok) "Saved" else "Save failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun startServer() {
        val ip = wifiIpv4(context)
        if (ip == null) {
            error = "Connect the phone to a WiFi network first."
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                for (port in 8080..8090) {
                    val s = TransferServer(
                        port = port,
                        key = key,
                        filesProvider = { buildTransferFiles() },
                        receivedDir = receivedDir,
                        onUpload = { receivedVersion++ }
                    )
                    if (s.start()) {
                        server = s
                        url = "http://$ip:${s.boundPort}/?key=$key"
                        return@withContext true
                    }
                }
                false
            }
            if (ok) {
                error = null
                running = true
            } else {
                error = "Could not start the server. Try again."
            }
        }
    }

    fun stopServer() {
        server?.stop()
        server = null
        running = false
        url = ""
    }

    DisposableEffect(Unit) {
        onDispose { server?.stop() }
    }

    ToolScaffold(title = "Transfer to PC", onBack = onBack) {
        Text(
            "Send PDFs to a computer on the same WiFi network, or receive files from it. " +
                "Nothing goes through the internet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = { picker.launch(arrayOf("application/pdf")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (sharedFiles.isEmpty()) "Add PDFs to share" else "Change shared files")
        }
        sharedFiles.forEach { uri ->
            Text("• ${PdfEngine.fileName(context, uri)}", style = MaterialTheme.typography.bodyMedium)
        }

        if (!running) {
            Button(onClick = { startServer() }, modifier = Modifier.fillMaxWidth()) {
                Text("Start server")
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Server running", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        url,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "On the computer, open this address in a browser. Both devices must be on the same WiFi network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = { stopServer() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Stop server")
                    }
                }
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Text("Received from PC", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (receivedFiles.isEmpty()) {
            Text(
                "Files sent from the computer will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        receivedFiles.forEach { file ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(file.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            pendingSave = file
                            saver.launch(file.name)
                        }) { Text("Save as…") }
                        OutlinedButton(onClick = { sharePdf(context, file) }) { Text("Share") }
                    }
                }
            }
        }
    }
}
