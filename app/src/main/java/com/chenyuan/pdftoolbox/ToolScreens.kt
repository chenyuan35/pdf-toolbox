package com.chenyuan.pdftoolbox

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<Uri?>(null) }
    var quality by remember { mutableStateOf("Medium") }
    var busy by remember { mutableStateOf(false) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            file = uri
            savedUri = null
            summary = null
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        val src = file ?: return@rememberLauncherForActivityResult
        if (out != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(out, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val (scale, q) = when (quality) {
                            "Low" -> 0.45f to 35
                            "High" -> 0.9f to 85
                            else -> 0.65f to 55
                        }
                        PdfEngine.compressPdf(context, src, out, scale, q)
                    }
                }
                busy = false
                result.fold(
                    onSuccess = { (before, after) ->
                        summary = "${PdfEngine.formatBytes(before)} → ${PdfEngine.formatBytes(after)}"
                        savedUri = out
                        Toast.makeText(context, "Saved compressed PDF", Toast.LENGTH_LONG).show()
                    },
                    onFailure = { e ->
                        Toast.makeText(context, e.message ?: "Compression failed", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    ToolScaffold(title = "Compress PDF", onBack = onBack) {
        Text(
            "Shrinks the file by turning pages into images at a chosen quality. " +
                "Note: text in the result is part of the image and can no longer be selected.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = { picker.launch(arrayOf("application/pdf")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(file?.let { "Selected: ${PdfEngine.fileName(context, it)}" } ?: "Select a PDF file")
        }
        if (file != null) {
            Text("Quality", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Low", "Medium", "High").forEach { option ->
                    if (option == quality) Button(onClick = { quality = option }) { Text(option) }
                    else OutlinedButton(onClick = { quality = option }) { Text(option) }
                }
            }
        }
        if (savedUri == null) {
            Button(
                onClick = { saver.launch("compressed.pdf") },
                enabled = file != null && !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Compress & save") }
            BusyRow(busy, "Compressing…")
        }
        savedUri?.let { uri -> SavedRow(uri = uri, summary = summary, onDone = onBack) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtectScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            file = uri
            savedUri = null
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        val src = file ?: return@rememberLauncherForActivityResult
        if (out != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(out, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { PdfEngine.protectPdf(context, src, out, password, password) }
                }
                busy = false
                ok.fold(
                    onSuccess = {
                        Toast.makeText(context, "PDF is now password-protected", Toast.LENGTH_LONG).show()
                        savedUri = out
                    },
                    onFailure = { e ->
                        Toast.makeText(context, e.message ?: "Protection failed", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    ToolScaffold(title = "Protect PDF", onBack = onBack) {
        Text(
            "Adds a password. Anyone opening the file will need to type it first. " +
                "Keep the password safe — it cannot be recovered.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = { picker.launch(arrayOf("application/pdf")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(file?.let { "Selected: ${PdfEngine.fileName(context, it)}" } ?: "Select a PDF file")
        }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password (min. 4 characters)") },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                androidx.compose.material3.IconButton(onClick = { showPassword = !showPassword }) {
                    Text(if (showPassword) "Hide" else "Show")
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (savedUri == null) {
            Button(
                onClick = { saver.launch("protected.pdf") },
                enabled = file != null && password.length >= 4 && !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Protect & save") }
            BusyRow(busy, "Encrypting…")
        }
        savedUri?.let { uri -> SavedRow(uri = uri, onDone = onBack) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatermarkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<Uri?>(null) }
    var text by remember { mutableStateOf("") }
    var position by remember { mutableStateOf(WatermarkPosition.DIAGONAL) }
    var busy by remember { mutableStateOf(false) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            file = uri
            savedUri = null
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        val src = file ?: return@rememberLauncherForActivityResult
        if (out != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(out, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { PdfEngine.addWatermark(context, src, out, text.trim(), position) }
                }
                busy = false
                ok.fold(
                    onSuccess = {
                        Toast.makeText(context, "Watermark added", Toast.LENGTH_LONG).show()
                        savedUri = out
                    },
                    onFailure = { e ->
                        Toast.makeText(context, e.message ?: "Watermark failed", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    ToolScaffold(title = "Watermark", onBack = onBack) {
        Text(
            "Stamps a light-gray text on every page. Any language works — it is rendered by the phone itself.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = { picker.launch(arrayOf("application/pdf")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(file?.let { "Selected: ${PdfEngine.fileName(context, it)}" } ?: "Select a PDF file")
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Watermark text") },
            placeholder = { Text("e.g. DRAFT · 机密 · CONFIDENTIAL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text("Position", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Diagonal" to WatermarkPosition.DIAGONAL,
                "Header" to WatermarkPosition.HEADER,
                "Footer" to WatermarkPosition.FOOTER
            ).forEach { (label, value) ->
                if (value == position) Button(onClick = { position = value }) { Text(label) }
                else OutlinedButton(onClick = { position = value }) { Text(label) }
            }
        }
        if (savedUri == null) {
            Button(
                onClick = { saver.launch("watermarked.pdf") },
                enabled = file != null && text.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add watermark & save") }
            BusyRow(busy, "Stamping…")
        }
        savedUri?.let { uri -> SavedRow(uri = uri, onDone = onBack) }
    }
}
