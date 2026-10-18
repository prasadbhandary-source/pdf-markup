package com.prasadbhandary.pdfmarkup

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Brand = Color(0xFFC6372E)

@Composable
fun MarkupTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Brand,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFFDAD5),
            onPrimaryContainer = Color(0xFF410001),
            secondaryContainer = Color(0xFFF5DDDA),
        ),
        content = content,
    )
}

private sealed class Phase {
    data object Home : Phase()
    class Opening(val name: String) : Phase()
    class Password(val uri: Uri, val name: String, val file: File, val wrong: Boolean) : Phase()
    class Viewing(val state: ViewerState) : Phase()
}

@Composable
fun MarkupApp(incoming: MutableState<Uri?>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recentsStore = remember { Recents(context) }
    val drafts = remember { Drafts(context) }
    val recents = remember { mutableStateListOf<Recent>().apply { addAll(recentsStore.list()) } }
    var phase by remember { mutableStateOf<Phase>(Phase.Home) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    fun refreshRecents() {
        recents.clear(); recents.addAll(recentsStore.list())
    }

    fun attempt(uri: Uri, name: String, file: File, password: String?) {
        phase = Phase.Opening(name)
        scope.launch {
            val result = withContext(Dispatchers.IO) { PdfIO.open(context, uri, name, file, password) }
            when (result) {
                is OpenResult.Ok -> {
                    val st = ViewerState(result.pdf)
                    st.startPage = recentsStore.pageOf(uri)
                    val draft = drafts.load(uri, result.pdf.pageSizes.size)
                    if (draft != null) {
                        st.marks.clear(); st.marks.addAll(draft)
                        st.dirty = true
                        message = "Restored annotations you hadn't saved yet."
                    }
                    phase = Phase.Viewing(st)
                }
                is OpenResult.NeedPassword -> phase = Phase.Password(uri, name, file, result.wrong)
                is OpenResult.Failed -> {
                    phase = Phase.Home
                    message = result.message
                }
            }
        }
    }

    fun openUri(uri: Uri, fromPicker: Boolean) {
        phase = Phase.Opening("")
        scope.launch {
            val name = withContext(Dispatchers.IO) { PdfIO.displayName(context, uri) }
            phase = Phase.Opening(name)
            // Keep access so the file can be reopened from Recents and saved in place.
            var persisted = false
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                persisted = true
            } catch (_: Exception) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    persisted = true
                } catch (_: Exception) {}
            }
            if (!persisted && !fromPicker) {
                // Opened from another app: it can still be reopened while that grant lasts.
                persisted = uri.scheme == "file"
            }
            val file = try {
                withContext(Dispatchers.IO) { PdfIO.copyToCache(context, uri) }
            } catch (e: Exception) {
                phase = Phase.Home
                message = "Couldn't open that file. ${e.message ?: ""}".trim()
                if (recents.any { it.uri == uri.toString() }) {
                    recentsStore.remove(uri.toString()); refreshRecents()
                }
                return@launch
            }
            if (persisted || recents.any { it.uri == uri.toString() }) {
                recentsStore.add(uri, name); refreshRecents()
            }
            attempt(uri, name, file, null)
        }
    }

    // Files sent to us by other apps.
    LaunchedEffect(incoming.value) {
        val uri = incoming.value ?: return@LaunchedEffect
        incoming.value = null
        val current = phase
        if (current is Phase.Viewing && current.state.dirty) {
            pendingUri = uri
        } else {
            if (current is Phase.Viewing) {
                recentsStore.setPage(current.state.pdf.uri, current.state.currentPage)
                current.state.close()
            }
            openUri(uri, fromPicker = false)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openUri(uri, fromPicker = true)
    }

    when (val p = phase) {
        is Phase.Home -> HomeScreen(
            recents = recents,
            message = message,
            onMessageShown = { message = null },
            onOpenPicker = { picker.launch(arrayOf("application/pdf")) },
            onOpenRecent = { openUri(Uri.parse(it.uri), fromPicker = true) },
            onRemoveRecent = { recentsStore.remove(it.uri); refreshRecents() },
        )
        is Phase.Opening -> Box(Modifier.fillMaxSize().background(Color(0xFFF4F2F2)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(if (p.name.isBlank()) "Opening…" else "Opening ${p.name}…")
            }
        }
        is Phase.Password -> {
            Box(Modifier.fillMaxSize().background(Color(0xFFF4F2F2)))
            PasswordDialog(
                name = p.name,
                wrong = p.wrong,
                onCancel = { phase = Phase.Home },
                onSubmit = { pw -> attempt(p.uri, p.name, p.file, pw) },
            )
        }
        is Phase.Viewing -> {
            DocumentScreen(
                state = p.state,
                drafts = drafts,
                recents = recentsStore,
                startMessage = message,
                onMessageShown = { message = null },
                onRecentsChanged = { refreshRecents() },
                onClose = {
                    recentsStore.setPage(p.state.pdf.uri, p.state.currentPage)
                    p.state.close()
                    refreshRecents()
                    phase = Phase.Home
                },
            )
        }
    }

    pendingUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingUri = null },
            title = { Text("Unsaved annotations") },
            text = { Text("You have annotations that aren't saved yet. Discard them and open the new file?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingUri = null
                    val current = phase
                    if (current is Phase.Viewing) {
                        drafts.clear(current.state.pdf.uri)
                        recentsStore.setPage(current.state.pdf.uri, current.state.currentPage)
                        current.state.close()
                    }
                    openUri(uri, fromPicker = false)
                }) { Text("Discard and open") }
            },
            dismissButton = { TextButton(onClick = { pendingUri = null }) { Text("Keep editing") } },
        )
    }
}

// ---- Home -------------------------------------------------------------------------------

@Composable
private fun HomeScreen(
    recents: List<Recent>,
    message: String?,
    onMessageShown: () -> Unit,
    onOpenPicker: () -> Unit,
    onOpenRecent: (Recent) -> Unit,
    onRemoveRecent: (Recent) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onMessageShown()
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = Color(0xFFF7F5F5)) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(28.dp))
            Text("PDF Markup", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                "Open any PDF, then write, highlight and add notes.",
                color = Color(0xFF5F5A5A),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onOpenPicker,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(AppIcons.Pdf, null)
                Spacer(Modifier.width(10.dp))
                Text("Open a PDF", fontSize = 17.sp)
            }
            Spacer(Modifier.height(24.dp))
            if (recents.isEmpty()) {
                Text(
                    "Tip: in Files, Gmail, Drive or Chrome, tap a PDF and choose " +
                        "\"Open with\" → PDF Markup.",
                    color = Color(0xFF6B6565),
                    fontSize = 14.sp,
                )
            } else {
                Text("Recent", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recents, key = { it.uri }) { r ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { onOpenRecent(r) },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Row(
                                Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(AppIcons.Pdf, null, tint = Brand, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                    Text(
                                        DateUtils.getRelativeTimeSpanString(r.time).toString() +
                                            if (r.page > 0) " · page ${r.page + 1}" else "",
                                        fontSize = 12.sp, color = Color(0xFF6B6565),
                                    )
                                }
                                IconButton(onClick = { onRemoveRecent(r) }) {
                                    Icon(Icons.Filled.Close, "Remove from recent", tint = Color(0xFF8A8484))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordDialog(name: String, wrong: Boolean, onCancel: () -> Unit, onSubmit: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Password needed") },
        text = {
            Column {
                Text("\"$name\" is password-protected.")
                if (wrong) Text("That password didn't work.", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it }, singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(pw) }, enabled = pw.isNotEmpty()) { Text("Open") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

// ---- Document ---------------------------------------------------------------------------

private class TextEdit(val page: Int, val point: Offset, val existing: TextMark?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentScreen(
    state: ViewerState,
    drafts: Drafts,
    recents: Recents,
    startMessage: String?,
    onMessageShown: () -> Unit,
    onRecentsChanged: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val pdf = state.pdf

    var tool by remember { mutableStateOf(Tool.HAND) }
    var penColor by remember { mutableIntStateOf(PEN_COLORS[2]) }
    var penWidth by remember { mutableFloatStateOf(2f) }
    var hlColor by remember { mutableIntStateOf(HIGHLIGHT_COLORS[0]) }
    var hlWidth by remember { mutableFloatStateOf(14f) }
    var textColor by remember { mutableIntStateOf(PEN_COLORS[1]) }
    var textSize by remember { mutableFloatStateOf(14f) }
    var target by remember { mutableStateOf(pdf.uri) }
    var busy by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var offerCopy by remember { mutableStateOf(false) }
    var goToPage by remember { mutableStateOf(false) }
    var textEdit by remember { mutableStateOf<TextEdit?>(null) }

    fun toast(s: String) { scope.launch { snackbar.showSnackbar(s) } }

    LaunchedEffect(startMessage) {
        if (startMessage != null) {
            snackbar.showSnackbar(startMessage)
            onMessageShown()
        }
    }

    // Keep a draft of unsaved work.
    LaunchedEffect(state) {
        snapshotFlow { Pair(state.marks.toList(), state.dirty) }.collectLatest { (marks, dirty) ->
            if (!dirty) return@collectLatest
            delay(600)
            withContext(Dispatchers.IO) { drafts.save(pdf.uri, marks) }
        }
    }

    suspend fun buildOutput(): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        var name = pdf.displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (!name.lowercase().endsWith(".pdf")) name += ".pdf"
        val out = File(dir, name)
        PdfIO.writeAnnotated(context, pdf, state.snapshot(), out)
        out
    }

    fun afterSaved() {
        state.dirty = false
        drafts.clear(pdf.uri)
    }

    val createCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Saving…"
            try {
                val f = buildOutput()
                withContext(Dispatchers.IO) { PdfIO.writeToUri(context, f, uri) }
                afterSaved()
                target = uri
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                toast("Saved a copy")
            } catch (e: Exception) {
                toast("Couldn't save: ${e.message ?: "unknown error"}")
            } finally {
                busy = null
            }
        }
    }

    fun suggestedCopyName(): String {
        val base = pdf.displayName.removeSuffix(".pdf").removeSuffix(".PDF")
        return "$base (annotated).pdf"
    }

    fun save(thenClose: Boolean = false) {
        if (!pdf.canSave) {
            toast("This file can be viewed, but annotations can't be written into it.")
            return
        }
        scope.launch {
            busy = "Saving…"
            try {
                val f = buildOutput()
                withContext(Dispatchers.IO) { PdfIO.writeToUri(context, f, target) }
                afterSaved()
                if (target != pdf.uri) {
                    // keep the original's draft key consistent
                    drafts.clear(target)
                }
                if (thenClose) onClose() else toast("Saved")
            } catch (e: Exception) {
                offerCopy = true
            } finally {
                busy = null
            }
        }
    }

    fun share() {
        if (!pdf.canSave) {
            toast("This file can be viewed, but annotations can't be written into it.")
            return
        }
        scope.launch {
            busy = "Preparing…"
            try {
                val f = buildOutput()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Share PDF"))
            } catch (e: ActivityNotFoundException) {
                toast("No app available to share with.")
            } catch (e: Exception) {
                toast("Couldn't prepare the file: ${e.message ?: "unknown error"}")
            } finally {
                busy = null
            }
        }
    }

    BackHandler {
        when {
            textEdit != null -> textEdit = null
            state.dirty -> confirmExit = true
            else -> onClose()
        }
    }

    val pageLabel by remember(state) {
        androidx.compose.runtime.derivedStateOf { "${state.currentPage + 1} / ${state.pageCount}" }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (state.dirty) confirmExit = true else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                title = {
                    Column(Modifier.clickable { goToPage = true }) {
                        Text(pdf.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                        Text(
                            "Page $pageLabel" + if (state.dirty) " · unsaved" else "",
                            fontSize = 12.sp, color = Color(0xFF6B6565),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { state.undo() }, enabled = state.canUndo) { Icon(AppIcons.Undo, "Undo") }
                    IconButton(onClick = { state.redo() }, enabled = state.canRedo) { Icon(AppIcons.Redo, "Redo") }
                    IconButton(onClick = { save() }, enabled = busy == null) {
                        Icon(AppIcons.Save, "Save", tint = if (state.dirty) Brand else LocalContentColorFallback)
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Save as a copy…") },
                                onClick = { menuOpen = false; createCopy.launch(suggestedCopyName()) },
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                leadingIcon = { Icon(Icons.Filled.Share, null) },
                                onClick = { menuOpen = false; share() },
                            )
                            DropdownMenuItem(
                                text = { Text("Go to page…") },
                                onClick = { menuOpen = false; goToPage = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            ToolBar(
                tool = tool,
                onTool = { tool = it },
                penColor = penColor, onPenColor = { penColor = it },
                penWidth = penWidth, onPenWidth = { penWidth = it },
                hlColor = hlColor, onHlColor = { hlColor = it },
                hlWidth = hlWidth, onHlWidth = { hlWidth = it },
                textColor = textColor, onTextColor = { textColor = it },
                textSize = textSize, onTextSize = { textSize = it },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val (c, w) = when (tool) {
                Tool.HIGHLIGHT -> hlColor to hlWidth
                else -> penColor to penWidth
            }
            PdfCanvas(
                state = state,
                tool = tool,
                color = c,
                width = w,
                selectedText = textEdit?.existing?.id,
                onTextTap = { page, point, hit -> textEdit = TextEdit(page, point, hit) },
                modifier = Modifier.fillMaxSize(),
            )
            busy?.let {
                Box(Modifier.fillMaxSize().background(Color(0x55000000)), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(it)
                        }
                    }
                }
            }
        }
    }

    textEdit?.let { te ->
        TextNoteDialog(
            initialText = te.existing?.text ?: "",
            initialColor = te.existing?.color ?: textColor,
            initialSize = te.existing?.size ?: textSize,
            isNew = te.existing == null,
            onDismiss = { textEdit = null },
            onDelete = {
                val before = state.snapshot()
                state.marks.removeAll { it.id == te.existing?.id }
                state.commit(before)
                textEdit = null
            },
            onDone = { text, color, size ->
                val before = state.snapshot()
                val ex = te.existing
                if (ex != null) {
                    val idx = state.marks.indexOfFirst { it.id == ex.id }
                    if (text.isBlank()) {
                        if (idx >= 0) state.marks.removeAt(idx)
                    } else if (idx >= 0) {
                        state.marks[idx] = ex.edited(text, color, size)
                    }
                    state.commit(before)
                } else if (text.isNotBlank()) {
                    state.marks.add(TextMark(newMarkId(), te.page, te.point.x, te.point.y - size * 0.6f, text, color, size))
                    state.commit(before)
                }
                textColor = color
                textSize = size
                textEdit = null
            },
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Save your annotations?") },
            text = { Text("You have changes that aren't saved to the PDF yet.") },
            confirmButton = {
                TextButton(onClick = { confirmExit = false; save(thenClose = true) }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirmExit = false
                        drafts.clear(pdf.uri)
                        onClose()
                    }) { Text("Discard") }
                    TextButton(onClick = { confirmExit = false }) { Text("Cancel") }
                }
            },
        )
    }

    if (offerCopy) {
        AlertDialog(
            onDismissRequest = { offerCopy = false },
            title = { Text("Can't save over this file") },
            text = {
                Text(
                    "The app that shared this PDF only allows reading it. " +
                        "Save your annotated version as a new file instead?"
                )
            },
            confirmButton = {
                TextButton(onClick = { offerCopy = false; createCopy.launch(suggestedCopyName()) }) { Text("Save a copy") }
            },
            dismissButton = { TextButton(onClick = { offerCopy = false }) { Text("Cancel") } },
        )
    }

    if (goToPage) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { goToPage = false },
            title = { Text("Go to page") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { v -> input = v.filter { it.isDigit() }.take(6) },
                    singleLine = true,
                    label = { Text("Page (1–${state.pageCount})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    input.toIntOrNull()?.let { state.scrollToPage(it - 1) }
                    goToPage = false
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { goToPage = false }) { Text("Cancel") } },
        )
    }
}

private val LocalContentColorFallback = Color(0xFF3B3535)

@Composable
private fun ToolBar(
    tool: Tool,
    onTool: (Tool) -> Unit,
    penColor: Int, onPenColor: (Int) -> Unit,
    penWidth: Float, onPenWidth: (Float) -> Unit,
    hlColor: Int, onHlColor: (Int) -> Unit,
    hlWidth: Float, onHlWidth: (Float) -> Unit,
    textColor: Int, onTextColor: (Int) -> Unit,
    textSize: Float, onTextSize: (Float) -> Unit,
) {
    Surface(shadowElevation = 8.dp, color = Color.White) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            when (tool) {
                Tool.PEN -> OptionsRow(PEN_COLORS, penColor, onPenColor, "Thickness", penWidth, 0.5f..10f, onPenWidth)
                Tool.HIGHLIGHT -> OptionsRow(HIGHLIGHT_COLORS, hlColor, onHlColor, "Thickness", hlWidth, 6f..36f, onHlWidth)
                Tool.TEXT -> {
                    OptionsRow(PEN_COLORS, textColor, onTextColor, "Size", textSize, 8f..40f, onTextSize)
                    Hint("Tap the page to add text. Tap a note to edit it, or drag it to move it.")
                }
                Tool.ERASER -> Hint("Touch or drag over ink, highlights or notes to erase them.")
                Tool.HAND -> Hint("Pinch to zoom, double-tap to zoom in. Pick a tool below to annotate.")
            }
            if (tool == Tool.PEN || tool == Tool.HIGHLIGHT) {
                Hint("Draw with one finger. Use two fingers to scroll or zoom.")
            }
            HorizontalDivider(color = Color(0xFFEDE8E8))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToolButton(AppIcons.Hand, "Move", tool == Tool.HAND) { onTool(Tool.HAND) }
                ToolButton(AppIcons.Pen, "Pen", tool == Tool.PEN, tint = Color(penColor)) { onTool(Tool.PEN) }
                ToolButton(AppIcons.Highlighter, "Highlight", tool == Tool.HIGHLIGHT, tint = Color(hlColor).darker()) { onTool(Tool.HIGHLIGHT) }
                ToolButton(AppIcons.Text, "Text", tool == Tool.TEXT, tint = Color(textColor)) { onTool(Tool.TEXT) }
                ToolButton(AppIcons.Eraser, "Eraser", tool == Tool.ERASER) { onTool(Tool.ERASER) }
            }
        }
    }
}

private fun Color.darker(): Color = Color(red * 0.8f, green * 0.75f, blue * 0.5f, 1f)

@Composable
private fun Hint(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = Color(0xFF6B6565),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, tint: Color? = null, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Column(
        Modifier
            .background(bg, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .heightIn(min = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint ?: Color(0xFF3B3535), modifier = Modifier.size(24.dp))
        Text(label, fontSize = 11.sp, color = if (selected) Brand else Color(0xFF4A4444))
    }
}

@Composable
private fun OptionsRow(
    colors: List<Int>,
    selected: Int,
    onColor: (Int) -> Unit,
    sizeLabel: String,
    size: Float,
    range: ClosedFloatingPointRange<Float>,
    onSize: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            colors.forEach { c ->
                val isSel = c == selected
                Box(
                    Modifier
                        .padding(4.dp)
                        .size(30.dp)
                        .border(if (isSel) 3.dp else 1.dp, if (isSel) Color(0xFF222222) else Color(0x33000000), CircleShape)
                        .padding(if (isSel) 4.dp else 2.dp)
                        .background(Color(c), CircleShape)
                        .clickable { onColor(c) }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sizeLabel, fontSize = 12.sp, color = Color(0xFF6B6565), modifier = Modifier.width(64.dp))
            Slider(value = size, onValueChange = onSize, valueRange = range, modifier = Modifier.weight(1f))
            Text("%.1f".format(size), fontSize = 12.sp, modifier = Modifier.width(36.dp))
        }
    }
}

@Composable
private fun TextNoteDialog(
    initialText: String,
    initialColor: Int,
    initialSize: Float,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onDone: (String, Int, Float) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    var color by remember { mutableIntStateOf(initialColor) }
    var size by remember { mutableFloatStateOf(initialSize) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add text" else "Edit text") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    placeholder = { Text("Type your note") },
                )
                Spacer(Modifier.height(8.dp))
                OptionsRow(PEN_COLORS, color, { color = it }, "Size", size, 8f..40f) { size = it }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text, color, size) }) { Text("Done") } },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
