@file:OptIn(ExperimentalMaterial3Api::class)

package br.scannerdocs.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.scannerdocs.app.R
import br.scannerdocs.app.data.ScanDoc

@Composable
fun TelaInicio(
    docs: List<ScanDoc>,
    carregado: Boolean,
    snackbar: SnackbarHostState,
    onEscanear: () -> Unit,
    onImportar: () -> Unit,
    onAbrir: (ScanDoc) -> Unit,
    onCompartilhar: (ScanDoc) -> Unit,
    onSalvar: (ScanDoc) -> Unit,
    onRenomear: (ScanDoc) -> Unit,
    onExcluir: (ScanDoc) -> Unit,
) {
    var buscando by rememberSaveable { mutableStateOf(false) }
    var busca by rememberSaveable { mutableStateOf("") }
    val filtrados = remember(docs, busca) {
        val termo = busca.trim()
        if (termo.isEmpty()) docs else docs.filter { it.name.contains(termo, ignoreCase = true) }
    }

    BackHandler(enabled = buscando) {
        buscando = false
        busca = ""
    }

    Scaffold(
        topBar = {
            if (buscando) {
                val foco = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { foco.requestFocus() } }
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { buscando = false; busca = "" }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Fechar busca")
                        }
                    },
                    title = {
                        TextField(
                            value = busca,
                            onValueChange = { busca = it },
                            placeholder = { Text("Buscar pelo nome") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(foco),
                        )
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Scanner", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton(onClick = onImportar) {
                            Icon(painterResource(R.drawable.ic_imagem), contentDescription = "Importar fotos da galeria")
                        }
                        if (docs.isNotEmpty()) {
                            IconButton(onClick = { buscando = true }) {
                                Icon(Icons.Filled.Search, contentDescription = "Buscar")
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onEscanear,
                icon = { Icon(painterResource(R.drawable.ic_camera), contentDescription = null) },
                text = { Text("Escanear") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        when {
            !carregado -> Unit
            docs.isEmpty() -> Vazio(Modifier.padding(pad))
            filtrados.isEmpty() -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(32.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    "Nenhum documento com “${busca.trim()}”.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.padding(pad),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        if (filtrados.size == 1) "1 documento" else "${filtrados.size} documentos",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
                items(filtrados, key = { it.id }) { doc ->
                    CartaoDocumento(
                        doc = doc,
                        onAbrir = { onAbrir(doc) },
                        onCompartilhar = { onCompartilhar(doc) },
                        onSalvar = { onSalvar(doc) },
                        onRenomear = { onRenomear(doc) },
                        onExcluir = { onExcluir(doc) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CartaoDocumento(
    doc: ScanDoc,
    onAbrir: () -> Unit,
    onCompartilhar: () -> Unit,
    onSalvar: () -> Unit,
    onRenomear: () -> Unit,
    onExcluir: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(onClick = onAbrir, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Miniatura(doc.pageFiles.firstOrNull(), Modifier.size(width = 60.dp, height = 80.dp))
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
            ) {
                Text(
                    doc.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${dataHora(doc.updatedAt)}  ·  ${paginas(doc.pages.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Opções")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Compartilhar PDF") },
                        leadingIcon = { Icon(Icons.Filled.Share, null) },
                        onClick = { menu = false; onCompartilhar() },
                    )
                    DropdownMenuItem(
                        text = { Text("Salvar PDF no celular") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_salvar), null) },
                        onClick = { menu = false; onSalvar() },
                    )
                    DropdownMenuItem(
                        text = { Text("Renomear") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { menu = false; onRenomear() },
                    )
                    DropdownMenuItem(
                        text = { Text("Excluir", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onExcluir() },
                    )
                }
            }
        }
    }
}

@Composable
private fun Vazio(modifier: Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(R.drawable.ic_documento),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            modifier = Modifier.size(80.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Nenhum documento ainda", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Toque em Escanear para fotografar um documento ou importar fotos da galeria.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(72.dp))
    }
}
