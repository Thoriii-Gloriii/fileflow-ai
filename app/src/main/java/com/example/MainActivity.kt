package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            PermissionWrapper {
                ChatScreen()
            }
        }
      }
    }
  }
}

@Composable
fun PermissionWrapper(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true // Fallback for older versions, relying on normal manifest permissions
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasPermission = Environment.isExternalStorageManager()
        }
    }

    if (hasPermission) {
        content()
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Storage Permission Required",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "This app requires full storage access to manage your files. Please grant 'All files access' in Settings.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:${context.packageName}")
                        launcher.launch(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        launcher.launch(intent)
                    }
                }
            }) {
                Text("Grant Permission")
            }
        }
    }
}

sealed class PendingAction {
    data class ConfirmDelete(val path: String) : PendingAction()
    data class ConfirmWrite(val path: String, val content: String) : PendingAction()
    data class ConfirmMove(val source: String, val dest: String) : PendingAction()
    data class ConfirmUseTemplate(val templateName: String, val destPath: String) : PendingAction()
    data class ConfirmOrganize(val moves: List<OrganizeMove>) : PendingAction()
}

data class OrganizeMove(val from: String, val to: String)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val action: PendingAction? = null,
    val imagePath: String? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                text = "Hello! I am your file manager agent. I can list, read, write, move, and delete files based on simple commands. How can I help you organize your device today?\n\nExamples:\n- \"List files in Download\"\n- \"Write 'hello' to Download/test.txt\"\n- \"Delete Download/test.txt\"\n- \"Move Download/test.txt to Download/Docs/test.txt\"\n- \"Organize Download\"\n- \"Group .pdf .docx into Docs\"",
                isUser = false
            )
        )
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val rootPath = Environment.getExternalStorageDirectory().absolutePath

    // --- Online Mode settings, persisted locally on-device only ---
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _onlineModeEnabled = MutableStateFlow(prefs.getBoolean(KEY_ONLINE_MODE, false))
    val onlineModeEnabled: StateFlow<Boolean> = _onlineModeEnabled.asStateFlow()

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, "") ?: "")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _selectedModel = MutableStateFlow(prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL)
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    fun setOnlineMode(enabled: Boolean) {
        _onlineModeEnabled.value = enabled
        prefs.edit().putBoolean(KEY_ONLINE_MODE, enabled).apply()
    }

    fun setApiKey(key: String) {
        _apiKey.value = key
        prefs.edit().putString(KEY_API_KEY, key).apply()
    }

    fun setModel(model: String) {
        val value = model.ifBlank { DEFAULT_MODEL }
        _selectedModel.value = value
        prefs.edit().putString(KEY_MODEL, value).apply()
    }

    fun sendMessage(text: String) {
        val userMsg = ChatMessage(text = text, isUser = true)
        _messages.value = _messages.value + userMsg
        if (_onlineModeEnabled.value && _apiKey.value.isNotBlank()) {
            processCommandOnline(text)
        } else {
            processCommand(text)
        }
    }

    // --- Online Mode: route the command through Gemini, fall back offline on any failure ---
    private fun processCommandOnline(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val raw = callGemini(SYSTEM_PROMPT, text)
                withContext(Dispatchers.Main) { dispatchOnlineAction(raw, text) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addBotMessage("Online request failed (${e.message ?: "unknown error"}). Falling back to offline parsing.")
                    processCommand(text)
                }
            }
        }
    }

    private fun callGemini(systemPrompt: String, userText: String): String {
        val payload = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemPrompt) }) })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply { put(JSONObject().apply { put("text", userText) }) })
                })
            })
            put("generationConfig", JSONObject().apply { put("responseMimeType", "application/json") })
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${_selectedModel.value}:generateContent?key=${_apiKey.value}")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val bodyStr = response.body?.string() ?: throw IOException("Empty response")
            val json = JSONObject(bodyStr)
            val candidates = json.optJSONArray("candidates") ?: throw IOException("No candidates in response")
            if (candidates.length() == 0) throw IOException("No candidates in response")
            val parts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            return parts.getJSONObject(0).getString("text")
        }
    }

    private fun dispatchOnlineAction(rawResponse: String, originalInput: String) {
        try {
            val cleaned = rawResponse.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val obj = JSONObject(cleaned)
            when (obj.optString("action")) {
                "chat" -> addBotMessage(obj.optString("reply", "OK."))
                "list" -> executeList(obj.optString("target", ""))
                "read" -> executeRead(obj.optString("target", ""))
                "preview" -> executePreview(obj.optString("target", ""))
                "search" -> executeSearch(obj.optString("target", ""))
                "organize" -> executeOrganize(obj.optString("target", ""))
                "group_files_by_type" -> {
                    val extensions = obj.optJSONArray("extensions")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList()
                    executeGroupByType(
                        obj.optString("target", ""),
                        extensions,
                        obj.optString("folder_name", "Grouped")
                    )
                }
                "delete" -> {
                    val file = resolveFile(obj.optString("target", ""))
                    addBotMessage("Are you sure you want to delete '${file.absolutePath}'?", PendingAction.ConfirmDelete(file.absolutePath))
                }
                "write" -> {
                    val file = resolveFile(obj.optString("target", ""))
                    addBotMessage("Are you sure you want to write to '${file.absolutePath}'?", PendingAction.ConfirmWrite(file.absolutePath, obj.optString("content", "")))
                }
                "move" -> {
                    val source = resolveFile(obj.optString("target", ""))
                    val dest = resolveFile(obj.optString("dest", ""))
                    addBotMessage("Are you sure you want to move '${source.absolutePath}' to '${dest.absolutePath}'?", PendingAction.ConfirmMove(source.absolutePath, dest.absolutePath))
                }
                else -> processCommand(originalInput)
            }
        } catch (e: Exception) {
            addBotMessage("Couldn't parse the online response. Falling back to offline parsing.")
            processCommand(originalInput)
        }
    }

    private fun resolveFile(path: String): File {
        return if (path.startsWith("/")) File(path) else File(rootPath, path)
    }

    private fun processCommand(input: String) {
        val lower = input.lowercase()
        // Extract quoted strings
        val quotes = Regex("['\"](.*?)['\"]").findAll(input).map { it.groupValues[1] }.toList()
        val words = input.split(" ")
        val lastWord = words.lastOrNull() ?: ""

        try {
            // Delete Logic
            if (lower.contains("delete") || lower.contains("remove") || lower.contains("trash")) {
                val target = quotes.firstOrNull() ?: words.find { it.contains("/") || it.contains(".") } ?: lastWord
                if (target.isNotBlank() && target.length > 2) {
                    val file = resolveFile(target)
                    addBotMessage("Are you sure you want to delete '${file.absolutePath}'?", PendingAction.ConfirmDelete(file.absolutePath))
                    return
                }
            }

            // Move / Arrange Logic
            if (lower.contains("move") || lower.contains("arrange")) {
                if (quotes.size >= 2) {
                    val source = quotes[0]
                    val dest = quotes[1]
                    addBotMessage("Are you sure you want to move '${source}' to '${dest}'?", PendingAction.ConfirmMove(resolveFile(source).absolutePath, resolveFile(dest).absolutePath))
                    return
                }
            }

            // List Logic
            if (lower.contains("list") || lower.contains("show") || lower.contains("dir")) {
                val target = quotes.firstOrNull() ?: words.find { it.contains("/") && !it.contains("list") } ?: ""
                executeList(target)
                return
            }

            // Read Logic
            if (lower.contains("read") || lower.contains("cat")) {
                val target = quotes.firstOrNull() ?: words.find { it.contains("/") || it.contains(".") } ?: lastWord
                executeRead(target)
                return
            }

            // Write Logic
            if (lower.contains("write") || (lower.contains("create") && !lower.contains("template")) || lower.contains("edit")) {
                if (quotes.size >= 2) {
                    val content = quotes[0]
                    val target = quotes[1]
                    val file = resolveFile(target)
                    addBotMessage("Are you sure you want to write to '${file.absolutePath}'?", PendingAction.ConfirmWrite(file.absolutePath, content))
                    return
                }
            }

            // Template Logic
            if (lower.contains("save template") || (lower.contains("create template"))) {
                if (quotes.size >= 2) {
                    val templateName = quotes[0]
                    val path = quotes[1]
                    val source = resolveFile(path)
                    val templateDir = File(rootPath, ".FileAgentTemplates")
                    templateDir.mkdirs()
                    val dest = File(templateDir, templateName)
                    try {
                        source.copyRecursively(dest, overwrite = true)
                        addBotMessage("Template '$templateName' saved successfully.")
                    } catch (e: Exception) {
                        addBotMessage("Error saving template: ${e.message}")
                    }
                    return
                }
            }

            if (lower.contains("use template") || lower.contains("apply template") || lower.contains("from template")) {
                if (quotes.size >= 2) {
                    val templateName = quotes[0]
                    val path = quotes[1]
                    val dest = resolveFile(path)
                    val templateDir = File(rootPath, ".FileAgentTemplates")
                    val source = File(templateDir, templateName)
                    if (source.exists()) {
                        addBotMessage("Are you sure you want to create item at '${dest.absolutePath}' using template '$templateName'?", PendingAction.ConfirmUseTemplate(templateName, dest.absolutePath))
                    } else {
                        addBotMessage("Template '$templateName' not found.")
                    }
                    return
                }
            }

            // Group-by-type Logic: "group .pdf .docx into Docs", "move .apk files to APKs"
            GROUP_BY_TYPE_REGEX.find(input)?.let { m ->
                val rawExts = m.groupValues[2]
                val exts = EXT_TOKEN_REGEX.findAll(rawExts)
                    .map { it.value }
                    .filter { it.length > 1 && !it.equals(".files", ignoreCase = true) }
                    .toList()
                val folder = m.groupValues[3].trim().replaceFirstChar { it.uppercase() }
                if (exts.isNotEmpty() && folder.isNotBlank()) {
                    executeGroupByType("", exts, folder)
                    return
                }
            }

            // Organize Logic (AI-planned in Online Mode, EXT_MAP-based offline)
            if (lower.contains("organize") || lower.contains("organise") || lower.contains("tidy")) {
                val target = quotes.firstOrNull() ?: words.find { it.contains("/") } ?: ""
                executeOrganize(target)
                return
            }

            // Search Logic
            if (lower.startsWith("search") || lower.startsWith("find")) {
                val query = quotes.firstOrNull() ?: words.drop(1).joinToString(" ").trim()
                if (query.isNotBlank()) {
                    executeSearch(query)
                    return
                }
            }

            // Preview Logic
            if (lower.startsWith("preview")) {
                val target = quotes.firstOrNull() ?: words.find { it.contains("/") || it.contains(".") } ?: lastWord
                executePreview(target)
                return
            }

            addBotMessage("I didn't quite understand that. ${if (_onlineModeEnabled.value) "Online Mode is on but couldn't map that to an action." else "To ensure offline capabilities, I use a fast rule-based parser."} Try commands like:\n- List files in 'Download'\n- Read 'Download/note.txt'\n- Delete 'Download/old_folder'\n- Write 'hello' to 'Download/note.txt'\n- Move 'Download/A.txt' to 'Download/B.txt'\n- Preview 'Download/image.png'\n- Search for 'query'\n- Organize 'Download'\n- Group .pdf .docx into 'Docs'\n- Create template 'name' from 'path'\n- Use template 'name' at 'path'")
        } catch (e: Exception) {
            addBotMessage("Failed to parse or execute command: ${e.message}")
        }
    }

    fun executeAction(action: PendingAction) {
        when (action) {
            is PendingAction.ConfirmDelete -> {
                val file = File(action.path)
                try {
                    if (file.exists()) {
                        val success = file.deleteRecursively()
                        if (success) {
                            addBotMessage("Successfully deleted: ${action.path}")
                        } else {
                            addBotMessage("Failed to delete: ${action.path}")
                        }
                    } else {
                        addBotMessage("File no longer exists: ${action.path}")
                    }
                } catch (e: Exception) {
                    addBotMessage("Error deleting: ${e.message}")
                }
            }
            is PendingAction.ConfirmWrite -> {
                val file = File(action.path)
                try {
                    file.parentFile?.mkdirs()
                    file.writeText(action.content)
                    addBotMessage("Successfully written to: ${action.path}")
                } catch (e: Exception) {
                    addBotMessage("Failed to write: ${e.message}")
                }
            }
            is PendingAction.ConfirmMove -> {
                val source = File(action.source)
                val dest = File(action.dest)
                try {
                    dest.parentFile?.mkdirs()
                    if (source.renameTo(dest)) {
                        addBotMessage("Successfully moved to: ${action.dest}")
                    } else {
                        // Fallback to copy+delete
                        source.copyRecursively(dest, overwrite = true)
                        source.deleteRecursively()
                        addBotMessage("Successfully moved to: ${action.dest}")
                    }
                } catch (e: Exception) {
                    addBotMessage("Failed to move: ${e.message}")
                }
            }
            is PendingAction.ConfirmUseTemplate -> {
                val templateDir = File(rootPath, ".FileAgentTemplates")
                val source = File(templateDir, action.templateName)
                val dest = File(action.destPath)
                try {
                    if (source.isDirectory) {
                        dest.mkdirs()
                        source.copyRecursively(dest, overwrite = true)
                    } else {
                        dest.parentFile?.mkdirs()
                        source.copyRecursively(dest, overwrite = true)
                    }
                    addBotMessage("Successfully created item at: ${action.destPath} from template '${action.templateName}'")
                } catch (e: Exception) {
                    addBotMessage("Failed to apply template: ${e.message}")
                }
            }
            is PendingAction.ConfirmOrganize -> {
                var successCount = 0
                val failures = mutableListOf<String>()
                for (move in action.moves) {
                    try {
                        val source = File(move.from)
                        val dest = File(move.to)
                        if (!source.exists()) {
                            failures.add(source.name)
                            continue
                        }
                        dest.parentFile?.mkdirs()
                        if (!source.renameTo(dest)) {
                            source.copyRecursively(dest, overwrite = true)
                            source.deleteRecursively()
                        }
                        successCount++
                    } catch (e: Exception) {
                        failures.add(File(move.from).name)
                    }
                }
                val summary = StringBuilder("Organized $successCount item(s).")
                if (failures.isNotEmpty()) summary.append("\nFailed to move: ${failures.joinToString(", ")}")
                addBotMessage(summary.toString())
            }
        }
        
        // Remove the action from the previous message so it can't be clicked again
        val updated = _messages.value.map {
            if (it.action == action) it.copy(action = null) else it
        }
        _messages.value = updated
    }

    private fun addBotMessage(text: String, action: PendingAction? = null, imagePath: String? = null) {
        _messages.value = _messages.value + ChatMessage(text = text, isUser = false, action = action, imagePath = imagePath)
    }

    // Shared by both the offline rule-based parser and Online Mode's action dispatcher
    private fun executeList(targetRaw: String) {
        val dir = if (targetRaw.isBlank()) File(rootPath) else resolveFile(targetRaw)
        if (dir.exists() && dir.isDirectory) {
            val files = dir.listFiles()
            val listStr = files?.joinToString("\n") { (if (it.isDirectory) "\uD83D\uDCC1 " else "\uD83D\uDCC4 ") + it.name } ?: "Empty or cannot read."
            addBotMessage("Contents of ${dir.absolutePath}:\n$listStr")
        } else {
            addBotMessage("Directory '${dir.absolutePath}' not found.")
        }
    }

    private fun executeRead(targetRaw: String) {
        val file = resolveFile(targetRaw)
        if (file.exists() && file.isFile) {
            try {
                val content = file.readText().take(2000)
                addBotMessage("Content of ${file.name}:\n$content")
            } catch (e: Exception) {
                addBotMessage("Error reading file: ${e.message}")
            }
        } else {
            addBotMessage("File '${file.absolutePath}' not found.")
        }
    }

    private fun executePreview(targetRaw: String) {
        val file = resolveFile(targetRaw)
        if (file.exists() && file.isFile) {
            val ext = file.extension.lowercase()
            if (ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")) {
                addBotMessage("Previewing image ${file.name}:", imagePath = file.absolutePath)
            } else {
                try {
                    val content = file.readText().take(2000)
                    addBotMessage("Preview of ${file.name}:\n$content")
                } catch (e: Exception) {
                    addBotMessage("Error reading file: ${e.message}")
                }
            }
        } else {
            addBotMessage("File '${file.absolutePath}' not found.")
        }
    }

    private fun executeSearch(query: String) {
        if (query.isBlank()) {
            addBotMessage("Please specify a search query.")
            return
        }
        addBotMessage("Searching for '$query'...")
        viewModelScope.launch(Dispatchers.IO) {
            val results = performSearch(rootPath, query)
            val resultStr = if (results.isEmpty()) "No results found." else results.joinToString("\n") { it.absolutePath }
            addBotMessage("Search results:\n$resultStr")
        }
    }

    // Organize a folder. In Online Mode (with an API key) Gemini proposes a custom grouping
    // scheme. Otherwise this falls back to a deterministic, offline EXT_MAP-based sort — no
    // network or API key required.
    private fun executeOrganize(targetRaw: String) {
        val dir = if (targetRaw.isBlank()) File(rootPath) else resolveFile(targetRaw)
        if (!dir.exists() || !dir.isDirectory) {
            addBotMessage("Directory '${dir.absolutePath}' not found.")
            return
        }

        if (_onlineModeEnabled.value && _apiKey.value.isNotBlank()) {
            addBotMessage("Looking at '${dir.absolutePath}' to plan an organization...")
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val entries = dir.listFiles()?.map { it.name + if (it.isDirectory) "/" else "" } ?: emptyList()
                    if (entries.isEmpty()) {
                        withContext(Dispatchers.Main) { addBotMessage("That folder is empty - nothing to organize.") }
                        return@launch
                    }
                    val relativeMoves = requestOrganizePlan(dir.absolutePath, entries)
                    // Validate against what's actually on disk, in case the model named something that doesn't exist.
                    val moves = relativeMoves.mapNotNull { (relFrom, relTo) ->
                        val fromFile = File(dir, relFrom)
                        if (!fromFile.exists()) return@mapNotNull null
                        val toFile = File(dir, relTo)
                        OrganizeMove(fromFile.absolutePath, toFile.absolutePath)
                    }
                    withContext(Dispatchers.Main) {
                        offerOrganizeMoves(dir, moves, "The organizer didn't suggest any changes - looks tidy already.")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        addBotMessage("Couldn't reach Gemini for an organize plan (${e.message ?: "unknown error"}). Falling back to offline sorting by file type.")
                        val moves = computeOfflineOrganizeMoves(dir)
                        offerOrganizeMoves(dir, moves, "Nothing to move - everything's already sorted by type.")
                    }
                }
            }
        } else {
            val moves = computeOfflineOrganizeMoves(dir)
            offerOrganizeMoves(dir, moves, "Nothing to move - everything's already sorted by type.")
        }
    }

    // Deterministic offline sort: buckets files in `dir` into Images / Videos / Documents /
    // Audio / Archives / Other subfolders by extension. No AI, no network needed.
    private fun computeOfflineOrganizeMoves(dir: File): List<OrganizeMove> {
        val moves = mutableListOf<OrganizeMove>()
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) return@forEach
            val ext = file.extension.lowercase()
            val category = EXT_MAP.entries.firstOrNull { ext in it.value }?.key ?: "Other"
            // Skip files that are already sitting in their target category subfolder.
            if (file.parentFile?.name == category) return@forEach
            val dest = File(File(dir, category), file.name)
            moves.add(OrganizeMove(file.absolutePath, dest.absolutePath))
        }
        return moves
    }

    // "group .pdf .docx into Docs" / online action group_files_by_type: moves files matching
    // a given extension list into a single named subfolder, without touching anything else.
    private fun executeGroupByType(targetRaw: String, extensionsRaw: List<String>, folderNameRaw: String) {
        val dir = if (targetRaw.isBlank()) File(rootPath) else resolveFile(targetRaw)
        if (!dir.exists() || !dir.isDirectory) {
            addBotMessage("Directory '${dir.absolutePath}' not found.")
            return
        }
        val normExts = extensionsRaw
            .map { it.trim().lowercase().removePrefix(".") }
            .filter { it.isNotBlank() }
            .toSet()
        if (normExts.isEmpty()) {
            addBotMessage("Please specify which file extensions to group, e.g. \"Group .pdf .docx into Docs\".")
            return
        }
        // Guard against path traversal - only ever use the leaf name as the subfolder.
        val safeName = File(folderNameRaw.ifBlank { "Grouped" }).name.ifEmpty { "Grouped" }
        val moves = mutableListOf<OrganizeMove>()
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) return@forEach
            if (file.extension.lowercase() in normExts) {
                if (file.parentFile?.name == safeName) return@forEach
                val dest = File(File(dir, safeName), file.name)
                moves.add(OrganizeMove(file.absolutePath, dest.absolutePath))
            }
        }
        val extList = normExts.joinToString(", ") { ".$it" }
        offerOrganizeMoves(dir, moves, "No files matching $extList found in ${dir.name}.")
    }

    // Shared confirmation preview for any batch of proposed moves (offline organize, online
    // organize, and group-by-type all funnel through here).
    private fun offerOrganizeMoves(dir: File, moves: List<OrganizeMove>, emptyMessage: String) {
        if (moves.isEmpty()) {
            addBotMessage(emptyMessage)
            return
        }
        val preview = moves.joinToString("\n") {
            "${File(it.from).name} -> ${it.to.removePrefix(dir.absolutePath + "/")}"
        }
        addBotMessage(
            "Here's a suggested organization for '${dir.absolutePath}' (${moves.size} item(s) to move):\n$preview",
            PendingAction.ConfirmOrganize(moves)
        )
    }

    private fun requestOrganizePlan(dirPath: String, entries: List<String>): List<Pair<String, String>> {
        val userText = "Folder: $dirPath\nItems:\n" + entries.joinToString("\n")
        val content = callGemini(ORGANIZE_SYSTEM_PROMPT, userText)
        val cleaned = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = JSONArray(cleaned)
        val moves = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val from = obj.optString("from", "")
            val to = obj.optString("to", "")
            if (from.isNotBlank() && to.isNotBlank() && from != to) {
                moves.add(from to to)
            }
        }
        return moves
    }

    companion object {
        private const val PREFS_NAME = "fileflow_prefs"
        private const val KEY_ONLINE_MODE = "online_mode_enabled"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_MODEL = "gemini_model"
        const val DEFAULT_MODEL = "gemini-flash-latest"

        // Offline, no-API-key extension -> category map used by executeOrganize's fallback
        // path and available generally for deterministic file-type sorting.
        private val EXT_MAP: Map<String, Set<String>> = mapOf(
            "Images" to setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp"),
            "Videos" to setOf("mp4", "mkv", "mov", "avi", "webm", "3gp"),
            "Documents" to setOf("pdf", "doc", "docx", "txt", "xlsx", "pptx", "csv"),
            "Audio" to setOf("mp3", "wav", "m4a", "ogg", "opus"),
            "Archives" to setOf("zip", "rar", "7z", "tar", "gz")
        )

        // Matches things like: "group .pdf .docx into Docs", "move apk files to APKs",
        // "gather .png, .jpg into Photos"
        val GROUP_BY_TYPE_REGEX = Regex(
            """(group|move|sort|gather|put)\s+((?:\.?\w+[\s,]*)+?)\s+(?:files?\s+)?(?:in(?:to)?|to)\s+(\w[\w\- ]*)""",
            RegexOption.IGNORE_CASE
        )
        val EXT_TOKEN_REGEX = Regex("""\.?\w+""")

        private val SYSTEM_PROMPT = """
            You are the command interpreter for an Android file manager agent. Given a user's natural language request, decide which single action to take and reply with ONLY a raw JSON object — no markdown, no code fences, no explanation, nothing before or after it. Schema:

            {"action": "list" | "read" | "delete" | "write" | "move" | "search" | "preview" | "organize" | "group_files_by_type" | "chat", "target": "<path, relative to device storage root unless it starts with />", "content": "<only for write>", "dest": "<only for move, destination path>", "extensions": ["<only for group_files_by_type, e.g. \"pdf\", \"docx\">"], "folder_name": "<only for group_files_by_type, the subfolder to group matching files into>", "reply": "<only for chat, a short conversational reply>"}

            Rules:
            - Use "organize" when the user wants a folder tidied up generally, without specifying exact file types themselves (e.g. "organize my Downloads folder"). "target" is the folder to organize.
            - Use "group_files_by_type" when the user names specific file extensions and a destination subfolder (e.g. "group my pdfs and docs into Documents", "move all .apk files into APKs"). "target" is the folder to scan, "extensions" is the list of extensions (without dots), "folder_name" is the subfolder name to move them into.
            - Use "chat" only when the request is not a file operation (greetings, questions about your capabilities, etc). Leave other fields empty in that case.
            - Never invent file contents for read/list/search/preview — leave "content" empty for those actions.
            - "target" and "dest" should be plain relative paths like "Download/notes.txt" unless the user gave an absolute path.
            - Respond with the JSON object and nothing else.
        """.trimIndent()

        private val ORGANIZE_SYSTEM_PROMPT = """
            You help organize a folder on an Android device. You will be given a folder path and a flat list of its immediate contents (files and subfolders; subfolders end with /). Propose a tidy reorganization by grouping items into sensible subfolders — by type (Images, Documents, Videos, Audio, Archives, Apps) or another scheme that clearly fits the actual items you see.

            Reply with ONLY a raw JSON array — no markdown, no commentary before or after it — of move operations:
            [{"from": "<item name exactly as given, without trailing />", "to": "<new path relative to the same folder>"}]

            Rules:
            - Only include items that should move; omit items already well-placed.
            - "from" must exactly match one of the given item names (minus any trailing /).
            - "to" is always relative to the same folder (e.g. "Images/photo.jpg"), never absolute.
            - Do not propose moving a folder into itself.
            - If nothing needs reorganizing, reply with an empty array: []
        """.trimIndent()
    }

    private fun performSearch(dirPath: String, query: String): List<File> {
        val results = mutableListOf<File>()
        val dir = File(dirPath)
        if (!dir.exists()) return results
        
        try {
            dir.walkTopDown()
                .onEnter { !it.name.startsWith(".") && it.name != "Android" } // Skip hidden and Android/data to speed up
                .filter {
                    val nameMatch = it.name.contains(query, ignoreCase = true)
                    val extMatch = it.isFile && it.extension.contains(query, ignoreCase = true)
                    val contentMatch = it.isFile && it.length() < 1024 * 1024 && try { it.readText().contains(query, ignoreCase = true) } catch(e:Exception) { false }
                    nameMatch || extMatch || contentMatch
                }
                .take(20)
                .toCollection(results)
        } catch (e: Exception) {
            // Handle access exceptions
        }
        return results
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val messages by viewModel.messages.collectAsState()
    val onlineModeEnabled by viewModel.onlineModeEnabled.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    if (showSettingsDialog) {
        SettingsDialog(viewModel = viewModel, onDismiss = { showSettingsDialog = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Local File Agent")
                        Text(
                            text = if (onlineModeEnabled && apiKey.isNotBlank()) "Online" else "Offline",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                state = listState,
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(
                        message = msg,
                        onAction = { viewModel.executeAction(it) }
                    )
                }
            }

            // Scroll to bottom when new messages arrive
            LaunchedEffect(messages.size) {
                if (messages.isNotEmpty()) {
                    listState.animateScrollToItem(messages.size - 1)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a command...") },
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputText.isNotBlank()) {
                                viewModel.sendMessage(inputText)
                                inputText = ""
                            }
                        }
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                FloatingActionButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendMessage(inputText)
                            inputText = ""
                        }
                    },
                    shape = RoundedCornerShape(50),
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    val onlineModeEnabled by viewModel.onlineModeEnabled.collectAsState()
    val savedApiKey by viewModel.apiKey.collectAsState()
    val savedModel by viewModel.selectedModel.collectAsState()

    var enabled by remember { mutableStateOf(onlineModeEnabled) }
    var keyInput by remember { mutableStateOf(savedApiKey) }
    var modelInput by remember { mutableStateOf(savedModel) }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Online Mode") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Route commands through Gemini")
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                if (enabled) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Requires a Gemini API key. Get one at aistudio.google.com/apikey (free tier available).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("Gemini API key") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showKey) "Hide key" else "Show key"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        label = { Text("Model") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (keyInput.isBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Add your API key above to use Online Mode. Offline parsing is used until one is set.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.setOnlineMode(enabled)
                viewModel.setApiKey(keyInput.trim())
                viewModel.setModel(modelInput.trim())
                onDismiss()
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun MessageBubble(message: ChatMessage, onAction: (PendingAction) -> Unit) {
    val isUser = message.isUser
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bgColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isUser) 16.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 16.dp
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .background(bgColor, shape)
                .padding(16.dp)
        ) {
            Column {
                Text(
                    text = message.text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge
                )
                
                if (message.imagePath != null) {
                    val bitmap = remember(message.imagePath) {
                        BitmapFactory.decodeFile(message.imagePath)?.asImageBitmap()
                    }
                    if (bitmap != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Image preview",
                            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Failed to load image preview", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }

                if (message.action != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row {
                        Button(
                            onClick = { onAction(message.action) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Text(
                                when (message.action) {
                                    is PendingAction.ConfirmDelete -> "Confirm Delete"
                                    is PendingAction.ConfirmWrite -> "Confirm Write"
                                    is PendingAction.ConfirmMove -> "Confirm Move"
                                    is PendingAction.ConfirmUseTemplate -> "Confirm Apply Template"
                                    is PendingAction.ConfirmOrganize -> "Confirm Organize (${message.action.moves.size} item${if (message.action.moves.size == 1) "" else "s"})"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
