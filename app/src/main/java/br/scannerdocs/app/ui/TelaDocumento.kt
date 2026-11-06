@file:OptIn(ExperimentalMaterial3Api::class)

package br.scannerdocs.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.scannerdocs.app.R
import br.scannerdocs.app.data.ScanDoc
import java.io.File

@Composable
fun TelaDocumento(
    doc: ScanDoc,
    snackbar: SnackbarHostState,
    onVoltar: () -> Unit,
    onAbrirPagina: (Int) -> Unit,
    onAdicionar: () -> Unit,
    onCompartilhar: () -> Unit,
    onCompartilharImagens: () -> Unit,
    onSalvar: () -> Unit,
    onTexto: () -> Unit,
    onRenomear: () -> Unit,
    onExcluir: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onVoltar) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                title = {
                    Column(Modifier.clickable(onClick = onRenomear)) {
                        Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            paginas(doc.pages.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRenomear) {
                        Icon(Icons.Filled.Edit, contentDescription = "Renomear")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Mais opções")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Compartilhar como imagens (JPG)") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_imagem), null) },
                                onClick = { menu = false; onCompartilharImagens() },
                            )
                            DropdownMenuItem(
                                text = { Text("Excluir documento", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = { menu = false; onExcluir() },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                BotaoAcao(rememberVectorPainter(Icons.Filled.Share), "Compartilhar", onCompartilhar)
                BotaoAcao(painterResource(R.drawable.ic_salvar), "Salvar PDF", onSalvar)
                BotaoAcao(painterResource(R.drawable.ic_texto), "Extrair texto", onTexto)
                BotaoAcao(painterResource(R.drawable.ic_adicionar_pagina), "Adicionar", onAdicionar)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier.padding(pad),
            contentPadding = PaddingValues(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(doc.pages, key = { _, nome -> nome }) { i, nome ->
                Column(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onAbrirPagina(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = 1.dp,
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.72f),
                    ) {
                        Miniatura(File(doc.dir, nome), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }
            }
        }
    }
}
