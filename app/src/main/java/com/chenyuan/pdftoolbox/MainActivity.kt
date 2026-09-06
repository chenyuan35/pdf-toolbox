package com.chenyuan.pdftoolbox

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { Merge, Split, ImagesToPdf, Compress, Watermark, Protect, Transfer }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        setContent {
            PdfToolboxTheme {
                var screen by remember { mutableStateOf<Screen?>(null) }
                screen?.let { current ->
                    when (current) {
                        Screen.Merge -> MergeScreen(onBack = { screen = null })
                        Screen.Split -> SplitScreen(onBack = { screen = null })
                        Screen.ImagesToPdf -> ImagesToPdfScreen(onBack = { screen = null })
                        Screen.Compress -> CompressScreen(onBack = { screen = null })
                        Screen.Watermark -> WatermarkScreen(onBack = { screen = null })
                        Screen.Protect -> ProtectScreen(onBack = { screen = null })
                        Screen.Transfer -> TransferScreen(onBack = { screen = null })
                    }
                } ?: HomeScreen(onTool = { screen = it })
            }
        }
    }
}

@Composable
fun PdfToolboxTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            content()
        }
    }
}

@Composable
fun HomeScreen(onTool: (Screen) -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "PDF Toolbox",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Merge, split and convert PDF files. Everything happens on your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            ToolCard(
                title = "Merge PDFs",
                subtitle = "Combine several PDFs into one file, in any order"
            ) { onTool(Screen.Merge) }
            ToolCard(
                title = "Extract pages",
                subtitle = "Save selected pages of a PDF as a new file"
            ) { onTool(Screen.Split) }
            ToolCard(
                title = "Images to PDF",
                subtitle = "Turn photos and screenshots into a single PDF"
            ) { onTool(Screen.ImagesToPdf) }
            ToolCard(
                title = "Compress PDF",
                subtitle = "Shrink the file size for easier sharing"
            ) { onTool(Screen.Compress) }
            ToolCard(
                title = "Watermark",
                subtitle = "Stamp DRAFT or confidential text on every page"
            ) { onTool(Screen.Watermark) }
            ToolCard(
                title = "Protect PDF",
                subtitle = "Add a password so only you can open it"
            ) { onTool(Screen.Protect) }
            ToolCard(
                title = "Transfer to PC",
                subtitle = "Send and receive PDFs over WiFi — no cables, no cloud"
            ) { onTool(Screen.Transfer) }
            Spacer(Modifier.weight(1f))
            Text(
                text = "No ads · No accounts · No cloud — files only go where you send them",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
fun ToolCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun BusyRow(visible: Boolean, label: String) {
    if (!visible) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

fun sharePdf(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share PDF"))
}

fun sharePdf(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share PDF"))
}

@Composable
fun SavedRow(uri: Uri, summary: String? = null, onDone: () -> Unit) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("✓ Saved", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            summary?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                PdfEngine.fileName(context, uri),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { sharePdf(context, uri) }) { Text("Share or email") }
                OutlinedButton(onClick = onDone) { Text("Done") }
            }
        }
    }
}

@Composable
fun PageThumb(bitmap: Bitmap, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = if (selected) "Page, selected" else "Page",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth()
        )
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(listOf<Uri>()) }
    var busy by remember { mutableStateOf(false) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            files = (files + uris).distinct()
            savedUri = null
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        if (out != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(out, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { PdfEngine.merge(context, files, out) }.isSuccess
                }
                busy = false
                Toast.makeText(context, if (ok) "Saved merged PDF" else "Merge failed", Toast.LENGTH_LONG).show()
                if (ok) savedUri = out
            }
        }
    }

    ToolScaffold(title = "Merge PDFs", onBack = onBack) {
        Text(
            "Select two or more PDF files. They will be merged top to bottom — use the arrows to reorder.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf")) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (files.isEmpty()) "Select PDF files" else "Add more PDF files")
        }
        if (files.isNotEmpty()) {
            OutlinedButton(onClick = {
                files = emptyList()
                savedUri = null
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Clear list")
            }
        }
        files.forEachIndexed { index, uri ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index + 1}. ${PdfEngine.fileName(context, uri)}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(enabled = index > 0, onClick = {
                    files = files.toMutableList().apply { add(index - 1, removeAt(index)) }
                }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up") }
                IconButton(enabled = index < files.lastIndex, onClick = {
                    files = files.toMutableList().apply { add(index + 1, removeAt(index)) }
                }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down") }
                IconButton(onClick = {
                    files = files.filterIndexed { i, _ -> i != index }
                }) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
            }
        }
        if (savedUri == null) {
            Button(
                onClick = { saver.launch("merged.pdf") },
                enabled = files.size >= 2 && !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Merge ${files.size} files") }
            BusyRow(busy, "Merging…")
        }
        savedUri?.let { uri -> SavedRow(uri = uri, onDone = onBack) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<Uri?>(null) }
    var pageCount by remember { mutableStateOf<Int?>(null) }
    var rangeText by remember { mutableStateOf("") }
    var rangeError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }
    var thumbnails by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var selectedPages by remember { mutableStateOf(setOf<Int>()) }
    var pendingPages by remember { mutableStateOf<List<Int>?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            file = uri; pageCount = null; savedUri = null
            thumbnails = emptyList(); selectedPages = emptySet(); pendingPages = null
        }
    }
    LaunchedEffect(file) {
        val src = file ?: return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            runCatching {
                PdfEngine.pageCount(context, src) to PdfEngine.renderPageThumbnails(context, src, maxPages = 30)
            }
        }
        result.fold(
            onSuccess = { (n, thumbs) ->
                pageCount = n
                thumbnails = thumbs
            },
            onFailure = {
                pageCount = null
                Toast.makeText(context, "Could not read this PDF", Toast.LENGTH_LONG).show()
            }
        )
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        val src = file ?: return@rememberLauncherForActivityResult
        val pages = pendingPages
        if (out != null && pages != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(out, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { PdfEngine.extractPages(context, src, out, pages) }
                }
                busy = false
                ok.fold(
                    onSuccess = { Toast.makeText(context, "Saved extracted pages", Toast.LENGTH_LONG).show(); savedUri = out },
                    onFailure = { e -> Toast.makeText(context, e.message ?: "Extract failed", Toast.LENGTH_LONG).show() }
                )
            }
        }
    }

    ToolScaffold(title = "Extract pages", onBack = onBack) {
        Text(
            "Pick a PDF, then tap the pages to keep, or type a range like 1-3,5,8-10.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = { picker.launch(arrayOf("application/pdf")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(file?.let { "Selected: ${PdfEngine.fileName(context, it)}" } ?: "Select a PDF file")
        }
        if (pageCount != null) {
            Text("This document has $pageCount pages.", style = MaterialTheme.typography.bodyMedium)
            if (thumbnails.isNotEmpty()) {
                Text(
                    if (pageCount!! > thumbnails.size) {
                        "Tap pages to keep them. Showing the first ${thumbnails.size} of $pageCount — type a range for the rest."
                    } else {
                        "Tap pages to keep them, or type a range below."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                thumbnails.chunked(3).forEachIndexed { rowIndex, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEachIndexed { colIndex, bmp ->
                            val pageIdx = rowIndex * 3 + colIndex
                            PageThumb(
                                bitmap = bmp,
                                selected = pageIdx in selectedPages,
                                modifier = Modifier.weight(1f)
                            ) {
                                selectedPages = if (pageIdx in selectedPages) selectedPages - pageIdx else selectedPages + pageIdx
                            }
                        }
                    }
                }
                if (selectedPages.isNotEmpty()) {
                    Text(
                        "${selectedPages.size} page${if (selectedPages.size == 1) "" else "s"} selected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            OutlinedTextField(
                value = rangeText,
                onValueChange = { rangeText = it; rangeError = null },
                label = { Text("Pages to keep") },
                placeholder = { Text("e.g. 1-3,5") },
                isError = rangeError != null,
                supportingText = { rangeError?.let { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    try {
                        pendingPages = if (selectedPages.isNotEmpty()) selectedPages.sorted()
                        else PdfEngine.parsePageIndices(rangeText, pageCount!!)
                        rangeError = null
                        saver.launch("extracted-pages.pdf")
                    } catch (e: Exception) {
                        rangeError = e.message ?: "Invalid input"
                    }
                },
                enabled = (selectedPages.isNotEmpty() || rangeText.isNotBlank()) && !busy && savedUri == null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Extract & save") }
        }
        BusyRow(busy, "Working…")
        savedUri?.let { uri -> SavedRow(uri = uri, onDone = onBack) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagesToPdfScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var images by remember { mutableStateOf(listOf<Uri>()) }
    var busy by remember { mutableStateOf(false) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { images = uris; savedUri = null }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        if (out != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(out, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { PdfEngine.imagesToPdf(context, images, out) }
                }
                busy = false
                ok.fold(
                    onSuccess = { Toast.makeText(context, "Saved PDF", Toast.LENGTH_LONG).show(); savedUri = out },
                    onFailure = { e -> Toast.makeText(context, e.message ?: "Conversion failed", Toast.LENGTH_LONG).show() }
                )
            }
        }
    }

    ToolScaffold(title = "Images to PDF", onBack = onBack) {
        Text(
            "Select one or more images. Each image becomes one page, in the order shown below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (images.isEmpty()) "Select images" else "Change selection")
        }
        images.forEach { uri ->
            Text("• ${PdfEngine.fileName(context, uri)}", style = MaterialTheme.typography.bodyMedium)
        }
        if (savedUri == null) {
            Button(
                onClick = { saver.launch("images.pdf") },
                enabled = images.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Convert ${images.size} image${if (images.size == 1) "" else "s"}") }
            BusyRow(busy, "Converting…")
        }
        savedUri?.let { uri -> SavedRow(uri = uri, onDone = onBack) }
    }
}
