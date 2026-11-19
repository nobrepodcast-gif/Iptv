package com.example.iptv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

data class Channel(val name: String, val group: String, val url: String)

fun parseM3u(text: String): List<Channel> {
    val out = mutableListOf<Channel>()
    var name = ""
    var group = "Sem grupo"
    val groupRe = Regex("group-title=\"([^\"]*)\"")
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.startsWith("#EXTINF")) {
            group = groupRe.find(line)?.groupValues?.get(1)?.ifBlank { "Sem grupo" } ?: "Sem grupo"
            name = line.substringAfterLast(",", "Canal").trim()
        } else if (line.isNotEmpty() && !line.startsWith("#")) {
            out.add(Channel(name.ifBlank { line }, group, line))
            name = ""
            group = "Sem grupo"
        }
    }
    return out
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(prefs.getString("url", "") ?: "") }
    var channels by remember { mutableStateOf(listOf<Channel>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("Todos") }
    var playing by remember { mutableStateOf<Channel?>(null) }
    var favs by remember { mutableStateOf(prefs.getStringSet("favs", emptySet()) ?: emptySet()) }
    var hidden by remember { mutableStateOf(prefs.getStringSet("hidden", emptySet()) ?: emptySet()) }
    var lists by remember {
        mutableStateOf(
            (prefs.getStringSet("listNames", emptySet()) ?: emptySet())
                .associateWith { prefs.getStringSet("list:$it", emptySet()) ?: emptySet() }
        )
    }
    var showNewList by remember { mutableStateOf(false) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var newListName by remember { mutableStateOf("") }

    fun saveLists(m: Map<String, Set<String>>, removed: String? = null) {
        lists = m
        prefs.edit().apply {
            putStringSet("listNames", m.keys.toSet())
            m.forEach { (k, v) -> putStringSet("list:$k", v) }
            if (removed != null) remove("list:$removed")
        }.apply()
    }

    fun load() {
        scope.launch {
            loading = true
            error = null
            try {
                val text = withContext(Dispatchers.IO) {
                    URL(url.trim()).openStream().bufferedReader().use { it.readText() }
                }
                channels = parseM3u(text)
                prefs.edit().putString("url", url.trim()).apply()
            } catch (e: Exception) {
                error = e.message ?: "Erro ao carregar a lista"
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { if (url.isNotBlank()) load() }

    val current = playing
    if (current != null) {
        BackHandler { playing = null }
        Player(current.url)
        return
    }

    val groups = remember(channels) { channels.map { it.group }.distinct() }
    val chips = listOf("Todos", "Favoritos") + lists.keys.sorted().map { "lista:$it" } + groups + "Ocultos"
    val shown = channels.filter { ch ->
        val inGroup = when {
            group == "Ocultos" -> ch.url in hidden
            ch.url in hidden -> false
            group == "Todos" -> true
            group == "Favoritos" -> ch.url in favs
            group.startsWith("lista:") -> ch.url in (lists[group.removePrefix("lista:")] ?: emptySet())
            else -> ch.group == group
        }
        inGroup && ch.name.contains(query, ignoreCase = true)
    }

    if (showNewList) {
        AlertDialog(
            onDismissRequest = { showNewList = false },
            title = { Text("Nova lista") },
            text = {
                OutlinedTextField(
                    value = newListName, onValueChange = { newListName = it },
                    label = { Text("Nome da lista") }, singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newListName.trim()
                    if (name.isNotEmpty() && name !in lists) {
                        val initial = pendingUrl?.let { setOf(it) } ?: emptySet()
                        saveLists(lists + (name to initial))
                        group = "lista:$name"
                    }
                    newListName = ""
                    pendingUrl = null
                    showNewList = false
                }) { Text("Criar") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUrl = null; showNewList = false }) { Text("Cancelar") }
            }
        )
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("URL da lista M3U") },
                singleLine = true, modifier = Modifier.weight(1f)
            )
            Button(onClick = { load() }) { Text("Carregar") }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Buscar canal") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                AssistChip(onClick = { pendingUrl = null; showNewList = true }, label = { Text("+ Nova lista") })
            }
            items(chips) { g ->
                val label = if (g.startsWith("lista:")) "\uD83D\uDCCB " + g.removePrefix("lista:") else g
                FilterChip(selected = group == g, onClick = { group = g }, label = { Text(label) })
            }
        }
        if (group.startsWith("lista:")) {
            TextButton(onClick = {
                val name = group.removePrefix("lista:")
                saveLists(lists - name, removed = name)
                group = "Todos"
            }) { Text("Excluir esta lista") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(shown) { ch ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().clickable { playing = ch }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(ch.name, style = MaterialTheme.typography.titleMedium)
                        Text(ch.group, style = MaterialTheme.typography.bodySmall)
                    }
                    Box {
                        TextButton(onClick = { menu = true }) {
                            Text(if (ch.url in favs) "\u2605 \u22EE" else "\u22EE")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (ch.url in favs) "Remover dos favoritos" else "Favoritar") },
                                onClick = {
                                    val n = if (ch.url in favs) favs - ch.url else favs + ch.url
                                    favs = n
                                    prefs.edit().putStringSet("favs", n).apply()
                                    menu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (ch.url in hidden) "Mostrar canal" else "Ocultar canal") },
                                onClick = {
                                    val n = if (ch.url in hidden) hidden - ch.url else hidden + ch.url
                                    hidden = n
                                    prefs.edit().putStringSet("hidden", n).apply()
                                    menu = false
                                }
                            )
                            lists.keys.sorted().forEach { name ->
                                val inList = ch.url in (lists[name] ?: emptySet())
                                DropdownMenuItem(
                                    text = { Text(if (inList) "Tirar da lista $name" else "Adicionar à lista $name") },
                                    onClick = {
                                        val cur = lists[name] ?: emptySet()
                                        val n = if (inList) cur - ch.url else cur + ch.url
                                        saveLists(lists + (name to n))
                                        menu = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Nova lista com este canal…") },
                                onClick = { pendingUrl = ch.url; showNewList = true; menu = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Player(url: String) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }
    LaunchedEffect(url) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = Modifier.fillMaxSize()
    )
}
