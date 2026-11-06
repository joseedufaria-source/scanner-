@file:OptIn(ExperimentalMaterial3Api::class)

package br.scannerdocs.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import br.scannerdocs.app.R
import br.scannerdocs.app.scanner.Filtro
import br.scannerdocs.app.scanner.ScannerViewModel
import br.scannerdocs.app.scanner.ScannerViewModel.Depois
import br.scannerdocs.app.scanner.ScannerViewModel.Etapa
import br.scannerdocs.app.scanner.ScannerViewModel.Pedido
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

private val FundoScanner = Color(0xFF111111)
private val Destaque = Color(0xFF2DD4BF)

private val coresBarraEscura
    @Composable get() = TopAppBarDefaults.topAppBarColors(
        containerColor = FundoScanner,
        titleContentColor = Color.White,
        navigationIconContentColor = Color.White,
        actionIconContentColor = Color.White,
    )

@Composable
fun TelaScanner(sc: ScannerViewModel, snackbar: SnackbarHostState) {
    val numero = sc.prontas.size + 1
    when (val e = sc.etapa) {
        is Etapa.Recorte -> TelaRecorte(
            e = e,
            numero = numero,
            snackbar = snackbar,
            onVoltar = sc::descartarAtual,
            onRefazer = sc::refazer,
            onGirar = sc::girarFoto,
            onFotoInteira = sc::alternarFotoInteira,
            onContinuar = sc::confirmarRecorte,
        )

        is Etapa.Ajuste -> TelaAjuste(
            e = e,
            numero = numero,
            restantes = sc.fila.size,
            snackbar = snackbar,
            onVoltar = sc::voltarAoRecorte,
            onFiltro = sc::escolherFiltro,
            onGirar = sc::girarPagina,
            onOutra = { sc.salvarPagina(if (sc.fila.isEmpty()) Depois.OUTRA_FOTO else Depois.PROXIMA) },
            onConcluir = { sc.salvarPagina(Depois.CONCLUIR) },
        )

        Etapa.Resumo -> TelaResumo(
            prontas = sc.prontas,
            snackbar = snackbar,
            onCamera = { sc.pedir(Pedido.CAMERA) },
            onGaleria = { sc.pedir(Pedido.GALERIA) },
            onExcluir = sc::excluirPronta,
            onConcluir = sc::concluir,
            onCancelar = sc::cancelar,
        )
    }
    sc.ocupado?.let { DialogoAguarde(it) }
}

// ---------------------------------------------------------------- recorte

/** Retângulo onde a imagem aparece dentro da área [tamanho], com [margem] em volta. */
private fun areaDaImagem(tamanho: IntSize, img: ImageBitmap, margem: Float): Rect {
    if (tamanho.width == 0 || tamanho.height == 0) return Rect.Zero
    val escala = min((tamanho.width - 2 * margem) / img.width, (tamanho.height - 2 * margem) / img.height)
    val w = img.width * escala
    val h = img.height * escala
    val l = (tamanho.width - w) / 2f
    val t = (tamanho.height - h) / 2f
    return Rect(l, t, l + w, t + h)
}

@Composable
private fun TelaRecorte(
    e: Etapa.Recorte,
    numero: Int,
    snackbar: SnackbarHostState,
    onVoltar: () -> Unit,
    onRefazer: () -> Unit,
    onGirar: () -> Unit,
    onFotoInteira: () -> Unit,
    onContinuar: () -> Unit,
) {
    BackHandler { onVoltar() }
    val img = remember(e) { e.previa.asImageBitmap() }
    val densidade = LocalDensity.current
    val margem = with(densidade) { 28.dp.toPx() }
    val alcance = with(densidade) { 56.dp.toPx() }
    var tamanho by remember { mutableStateOf(IntSize.Zero) }
    var arrastando by remember { mutableIntStateOf(-1) }
    val fotoInteira = ScannerViewModel.ehFotoInteira(e.cantos)

    Scaffold(
        containerColor = FundoScanner,
        topBar = {
            TopAppBar(
                colors = coresBarraEscura,
                navigationIcon = {
                    IconButton(onClick = onVoltar) { Icon(Icons.Filled.Close, contentDescription = "Descartar foto") }
                },
                title = {
                    Column {
                        Text("Ajuste os cantos")
                        Text(
                            "Página $numero · arraste os círculos até as pontas da folha",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar(containerColor = FundoScanner, contentColor = Color.White) {
                BotaoAcao(painterResource(R.drawable.ic_camera), "Refazer", onRefazer)
                BotaoAcao(painterResource(R.drawable.ic_girar), "Girar", onGirar)
                BotaoAcao(
                    painterResource(R.drawable.ic_imagem),
                    if (fotoInteira) "Detectar" else "Foto inteira",
                    onFotoInteira,
                )
                BotaoAcao(rememberVectorPainter(Icons.Filled.Check), "Continuar", onContinuar)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .onSizeChanged { tamanho = it }
                .pointerInput(e) {
                    detectDragGestures(
                        onDragStart = { toque ->
                            val r = areaDaImagem(tamanho, img, margem)
                            val c = e.cantos
                            var melhor = -1
                            var menor = Float.MAX_VALUE
                            for (i in 0 until 4) {
                                val p = Offset(r.left + c[2 * i] * r.width, r.top + c[2 * i + 1] * r.height)
                                val d = (p - toque).getDistance()
                                if (d < menor) {
                                    menor = d
                                    melhor = i
                                }
                            }
                            arrastando = if (menor <= alcance) melhor else -1
                        },
                        onDragEnd = { arrastando = -1 },
                        onDragCancel = { arrastando = -1 },
                        onDrag = { mudanca, delta ->
                            val i = arrastando
                            val r = areaDaImagem(tamanho, img, margem)
                            if (i >= 0 && r.width > 0f && r.height > 0f) {
                                mudanca.consume()
                                val c = e.cantos.copyOf()
                                c[2 * i] = (c[2 * i] + delta.x / r.width).coerceIn(0f, 1f)
                                c[2 * i + 1] = (c[2 * i + 1] + delta.y / r.height).coerceIn(0f, 1f)
                                e.cantos = c
                            }
                        },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = areaDaImagem(tamanho, img, margem)
                if (r.width <= 0f) return@Canvas
                drawImage(
                    image = img,
                    dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                    dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
                val c = e.cantos
                val pts = List(4) { Offset(r.left + c[2 * it] * r.width, r.top + c[2 * it + 1] * r.height) }
                val folha = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (k in 1 until 4) lineTo(pts[k].x, pts[k].y)
                    close()
                }
                val fora = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(r)
                    addPath(folha)
                }
                drawPath(fora, Color.Black.copy(alpha = 0.5f))
                drawPath(folha, Destaque, style = Stroke(width = 2.dp.toPx()))
                pts.forEachIndexed { i, p ->
                    drawCircle(Color.White.copy(alpha = if (i == arrastando) 0.45f else 0.2f), 20.dp.toPx(), p)
                    drawCircle(Destaque, 12.dp.toPx(), p, style = Stroke(width = 3.dp.toPx()))
                }
                if (arrastando >= 0) lupa(img, r, pts[arrastando], c[2 * arrastando], c[2 * arrastando + 1])
            }
        }
    }
}

/** Círculo com zoom em volta do canto que está sendo arrastado. */
private fun DrawScope.lupa(img: ImageBitmap, r: Rect, ponto: Offset, nx: Float, ny: Float) {
    val raio = 60.dp.toPx()
    val folga = 16.dp.toPx()
    val centro = Offset(
        if (ponto.x < size.width / 2) size.width - raio - folga else raio + folga,
        raio + folga,
    )
    val escala = r.width / img.width * 2.5f
    val circulo = Path().apply { addOval(Rect(centro, raio)) }
    clipPath(circulo) {
        drawRect(Color.Black, topLeft = centro - Offset(raio, raio), size = androidx.compose.ui.geometry.Size(raio * 2, raio * 2))
        drawImage(
            image = img,
            dstOffset = IntOffset(
                (centro.x - nx * img.width * escala).roundToInt(),
                (centro.y - ny * img.height * escala).roundToInt(),
            ),
            dstSize = IntSize((img.width * escala).roundToInt(), (img.height * escala).roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
    drawCircle(Color.White, raio, centro, style = Stroke(width = 2.dp.toPx()))
    val mira = 10.dp.toPx()
    drawLine(Destaque, centro - Offset(mira, 0f), centro + Offset(mira, 0f), strokeWidth = 2.dp.toPx())
    drawLine(Destaque, centro - Offset(0f, mira), centro + Offset(0f, mira), strokeWidth = 2.dp.toPx())
}

// ---------------------------------------------------------------- filtro

@Composable
private fun TelaAjuste(
    e: Etapa.Ajuste,
    numero: Int,
    restantes: Int,
    snackbar: SnackbarHostState,
    onVoltar: () -> Unit,
    onFiltro: (Filtro) -> Unit,
    onGirar: () -> Unit,
    onOutra: () -> Unit,
    onConcluir: () -> Unit,
) {
    BackHandler { onVoltar() }
    val img = remember(e) { e.previa.asImageBitmap() }
    Scaffold(
        containerColor = FundoScanner,
        topBar = {
            TopAppBar(
                colors = coresBarraEscura,
                navigationIcon = {
                    IconButton(onClick = onVoltar) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar aos cantos")
                    }
                },
                title = { Text("Página $numero") },
                actions = {
                    IconButton(onClick = onGirar) {
                        Icon(painterResource(R.drawable.ic_girar), contentDescription = "Girar")
                    }
                },
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(FundoScanner)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Filtro.entries.forEach { f ->
                        FilterChip(
                            selected = f == e.filtro,
                            onClick = { onFiltro(f) },
                            label = { Text(f.rotulo) },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = Color.White,
                                selectedContainerColor = Destaque,
                                selectedLabelColor = Color.Black,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onOutra,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        Text(if (restantes > 0) "Próxima foto ($restantes)" else "+ Outra página")
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = onConcluir,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Destaque, contentColor = Color.Black),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        Text("Concluir")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Image(
            bitmap = img,
            contentDescription = "Página escaneada",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp),
        )
    }
}

// ---------------------------------------------------------------- resumo

@Composable
private fun TelaResumo(
    prontas: List<File>,
    snackbar: SnackbarHostState,
    onCamera: () -> Unit,
    onGaleria: () -> Unit,
    onExcluir: (Int) -> Unit,
    onConcluir: () -> Unit,
    onCancelar: () -> Unit,
) {
    var confirmar by remember { mutableStateOf(false) }
    fun sair() {
        if (prontas.isEmpty()) onCancelar() else confirmar = true
    }
    BackHandler { sair() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { sair() }) { Icon(Icons.Filled.Close, contentDescription = "Cancelar") }
                },
                title = {
                    Text(
                        when (prontas.size) {
                            0 -> "Escanear"
                            1 -> "1 página pronta"
                            else -> "${prontas.size} páginas prontas"
                        }
                    )
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                BotaoAcao(painterResource(R.drawable.ic_camera), "Câmera", onCamera)
                BotaoAcao(painterResource(R.drawable.ic_imagem), "Galeria", onGaleria)
                if (prontas.isNotEmpty()) {
                    BotaoAcao(rememberVectorPainter(Icons.Filled.Check), "Concluir", onConcluir)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (prontas.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Tire uma foto do documento ou escolha fotos da galeria.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                modifier = Modifier.padding(pad),
                contentPadding = PaddingValues(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(prontas, key = { _, f -> f.path }) { i, f ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            Surface(
                                shadowElevation = 2.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.72f),
                            ) {
                                Miniatura(f, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            }
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.6f),
                                contentColor = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(30.dp),
                            ) {
                                IconButton(onClick = { onExcluir(i) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remover página", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }

    if (confirmar) {
        AlertDialog(
            onDismissRequest = { confirmar = false },
            title = { Text("Descartar as páginas?") },
            text = { Text("As ${prontas.size} página(s) escaneada(s) agora não serão salvas.") },
            confirmButton = {
                TextButton(onClick = { confirmar = false; onCancelar() }) {
                    Text("Descartar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmar = false }) { Text("Continuar") } },
        )
    }
}
