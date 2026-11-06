@file:OptIn(ExperimentalMaterial3Api::class)

package br.scannerdocs.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import br.scannerdocs.app.R
import br.scannerdocs.app.data.ScanDoc
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

private val Fundo = Color(0xFF111111)

@Composable
fun TelaPagina(
    doc: ScanDoc,
    indice: Int,
    snackbar: SnackbarHostState,
    onVoltar: () -> Unit,
    onMudarPagina: (Int) -> Unit,
    onGirar: (Int) -> Unit,
    onTexto: (Int) -> Unit,
    onMover: (de: Int, para: Int) -> Unit,
    onExcluir: (Int) -> Unit,
) {
    val total = doc.pages.size
    val pager = rememberPagerState(initialPage = indice.coerceIn(0, (total - 1).coerceAtLeast(0))) { doc.pages.size }
    var confirmarExclusao by remember { mutableStateOf(false) }

    // Mantém o pager e o ViewModel apontando para a mesma página.
    LaunchedEffect(indice) {
        if (indice in 0 until pager.pageCount && pager.currentPage != indice) pager.scrollToPage(indice)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { onMudarPagina(it) }
    }

    val atual = pager.currentPage.coerceIn(0, (total - 1).coerceAtLeast(0))
    val cores = TopAppBarDefaults.topAppBarColors(
        containerColor = Fundo,
        titleContentColor = Color.White,
        navigationIconContentColor = Color.White,
        actionIconContentColor = Color.White,
    )

    Scaffold(
        containerColor = Fundo,
        topBar = {
            TopAppBar(
                colors = cores,
                navigationIcon = {
                    IconButton(onClick = onVoltar) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                title = { Text("Página ${atual + 1} de $total") },
            )
        },
        bottomBar = {
            BottomAppBar(containerColor = Fundo, contentColor = Color.White) {
                BotaoAcao(painterResource(R.drawable.ic_girar), "Girar") { onGirar(atual) }
                BotaoAcao(painterResource(R.drawable.ic_texto), "Texto") { onTexto(atual) }
                BotaoAcao(rememberVectorPainter(Icons.AutoMirrored.Filled.KeyboardArrowLeft), "Mover") {
                    if (atual > 0) onMover(atual, atual - 1)
                }
                BotaoAcao(rememberVectorPainter(Icons.AutoMirrored.Filled.KeyboardArrowRight), "Mover") {
                    if (atual < total - 1) onMover(atual, atual + 1)
                }
                BotaoAcao(rememberVectorPainter(Icons.Filled.Delete), "Excluir") { confirmarExclusao = true }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        HorizontalPager(
            state = pager,
            key = { doc.pages.getOrElse(it) { "p$it" } },
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
        ) { i ->
            val nome = doc.pages.getOrNull(i)
            if (nome != null) ImagemComZoom(File(doc.dir, nome))
        }
    }

    if (confirmarExclusao) {
        AlertDialog(
            onDismissRequest = { confirmarExclusao = false },
            title = { Text("Excluir a página ${atual + 1}?") },
            text = {
                Text(
                    if (total == 1) "É a única página: o documento inteiro será excluído."
                    else "A página será apagada deste documento."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmarExclusao = false; onExcluir(atual) }) {
                    Text("Excluir", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmarExclusao = false }) { Text("Cancelar") }
            },
        )
    }
}

/**
 * Imagem com zoom de pinça e toque duplo. Com zoom 1x um dedo só passa a página;
 * com zoom ativo o arrasto move a imagem.
 */
@Composable
private fun ImagemComZoom(file: File) {
    var escala by remember { mutableFloatStateOf(1f) }
    var desloc by remember { mutableStateOf(Offset.Zero) }
    var tamanho by remember { mutableStateOf(IntSize.Zero) }

    fun limitar(o: Offset, s: Float): Offset {
        val maxX = tamanho.width * (s - 1f) / 2f
        val maxY = tamanho.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Fundo)
            .clipToBounds()
            .onSizeChanged { tamanho = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { toque ->
                        if (escala > 1f) {
                            escala = 1f
                            desloc = Offset.Zero
                        } else {
                            escala = 2.5f
                            val centro = Offset(tamanho.width / 2f, tamanho.height / 2f)
                            desloc = limitar((centro - toque) * 1.5f, escala)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val evento = awaitPointerEvent()
                        val dedos = evento.changes.count { it.pressed }
                        if (dedos >= 2 || escala > 1f) {
                            val nova = (escala * evento.calculateZoom()).coerceIn(1f, 5f)
                            escala = nova
                            desloc = if (nova <= 1f) Offset.Zero else limitar(desloc + evento.calculatePan(), nova)
                            evento.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (evento.changes.any { it.pressed })
                }
            },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(file)
                .size(2400)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = escala
                    scaleY = escala
                    translationX = desloc.x
                    translationY = desloc.y
                },
        )
    }
}
