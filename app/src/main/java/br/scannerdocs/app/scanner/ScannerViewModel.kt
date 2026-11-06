package br.scannerdocs.app.scanner

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Scanner próprio do app (não depende do Google Play Services):
 * foto da câmera ou da galeria -> ajuste dos cantos -> filtro -> página pronta.
 */
class ScannerViewModel(app: Application) : AndroidViewModel(app) {

    enum class Pedido { CAMERA, GALERIA }

    enum class Depois { OUTRA_FOTO, PROXIMA, CONCLUIR }

    class Resultado(val anexarEm: String?, val arquivos: List<File>)

    sealed interface Etapa {
        /** Páginas prontas até agora (ou nenhuma, enquanto a câmera abre). */
        data object Resumo : Etapa

        /** Ajuste dos 4 cantos da folha na foto. */
        class Recorte(
            val fonte: Bitmap,
            val previa: Bitmap,
            val detectados: FloatArray?,
            cantosIniciais: FloatArray,
        ) : Etapa {
            var cantos by mutableStateOf(cantosIniciais)
        }

        /** Página já endireitada: escolha do filtro e rotação. */
        class Ajuste(val recorte: Recorte, val pagina: Bitmap, val filtro: Filtro, val previa: Bitmap) : Etapa
    }

    var ativo by mutableStateOf(false)
        private set
    var etapa by mutableStateOf<Etapa>(Etapa.Resumo)
        private set
    var ocupado by mutableStateOf<String?>(null)
        private set

    /** Páginas prontas (JPEG na pasta temporária). */
    val prontas = mutableStateListOf<File>()

    /** Fotos ainda não ajustadas (várias quando vêm da galeria). */
    val fila = mutableStateListOf<File>()

    private var anexarEm: String? = null
    private var tarefa: Job? = null

    private val pasta = File(app.cacheDir, "scanner").apply { mkdirs() }
    private val prefs = app.getSharedPreferences("scanner", Context.MODE_PRIVATE)
    private var filtroPadrao: Filtro =
        prefs.getString("filtro", null)?.let { nome -> Filtro.entries.firstOrNull { it.name == nome } } ?: Filtro.REALCAR

    private val _pedidos = Channel<Pedido>(Channel.BUFFERED)
    val pedidos = _pedidos.receiveAsFlow()

    private val _avisos = Channel<String>(Channel.BUFFERED)
    val avisos = _avisos.receiveAsFlow()

    private val _concluidos = Channel<Resultado>(Channel.BUFFERED)
    val concluidos = _concluidos.receiveAsFlow()

    fun avisar(msg: String) {
        _avisos.trySend(msg)
    }

    // ---------- início e entrada de fotos ----------

    fun iniciar(anexarEm: String?, pedido: Pedido) {
        limparSobras()
        this.anexarEm = anexarEm
        prontas.clear()
        fila.clear()
        etapa = Etapa.Resumo
        ativo = true
        _pedidos.trySend(pedido)
    }

    fun pedir(pedido: Pedido) {
        _pedidos.trySend(pedido)
    }

    fun novoArquivoFoto(): File = File(pasta, "foto_${System.currentTimeMillis()}.jpg")

    fun fotoTirada(file: File) {
        ativo = true
        fila.add(file)
        if (etapa is Etapa.Resumo) trabalhar("Abrindo a foto…") { abrirProxima() }
    }

    /** A câmera ou a galeria foi fechada sem escolher nada. */
    fun capturaCancelada() {
        if (etapa is Etapa.Resumo && prontas.isEmpty() && fila.isEmpty()) cancelar()
    }

    fun importar(uris: List<Uri>) {
        ativo = true
        trabalhar("Abrindo ${if (uris.size == 1) "a foto" else "${uris.size} fotos"}…") {
            val resolver = getApplication<Application>().contentResolver
            val arquivos = withContext(Dispatchers.IO) {
                uris.mapIndexedNotNull { i, uri ->
                    val destino = File(pasta, "galeria_${System.currentTimeMillis()}_$i.jpg")
                    val ok = resolver.openInputStream(uri)?.use { entrada ->
                        destino.outputStream().use { entrada.copyTo(it) }
                        true
                    } ?: false
                    if (ok) destino else null
                }
            }
            fila.addAll(arquivos)
            if (etapa is Etapa.Resumo) abrirProxima()
        }
    }

    private suspend fun abrirProxima() {
        val arquivo = if (fila.isEmpty()) null else fila.removeAt(0)
        if (arquivo == null) {
            etapa = Etapa.Resumo
            if (prontas.isEmpty()) cancelar()
            return
        }
        val (fonte, previa, detectados) = withContext(Dispatchers.Default) {
            val fonte = Imagens.carregar(arquivo, MAX_LADO)
            Triple(fonte, Imagens.reduzir(fonte, 1600), Imagens.detectarFolha(fonte))
        }
        arquivo.delete()
        etapa = Etapa.Recorte(fonte, previa, detectados, detectados ?: TELA_TODA)
    }

    // ---------- etapa de recorte ----------

    fun girarFoto() {
        val e = etapa as? Etapa.Recorte ?: return
        trabalhar(null) {
            val (fonte, previa) = withContext(Dispatchers.Default) { Imagens.girar90(e.fonte) to Imagens.girar90(e.previa) }
            etapa = Etapa.Recorte(fonte, previa, e.detectados?.let { girarCantos(it) }, girarCantos(e.cantos))
        }
    }

    /** Alterna entre a folha detectada e a foto inteira. */
    fun alternarFotoInteira() {
        val e = etapa as? Etapa.Recorte ?: return
        e.cantos = if (ehFotoInteira(e.cantos)) e.detectados ?: MARGEM else TELA_TODA
    }

    fun confirmarRecorte() {
        val e = etapa as? Etapa.Recorte ?: return
        trabalhar("Endireitando a página…") {
            val pagina = withContext(Dispatchers.Default) { Imagens.endireitar(e.fonte, e.cantos, MAX_LADO) }
            mostrarAjuste(e, pagina, filtroPadrao)
        }
    }

    /** Descarta a foto atual e tira outra. */
    fun refazer() {
        if (etapa !is Etapa.Recorte) return
        etapa = Etapa.Resumo
        _pedidos.trySend(Pedido.CAMERA)
    }

    /** Descarta a foto atual (voltar). */
    fun descartarAtual() {
        if (etapa !is Etapa.Recorte) return
        etapa = Etapa.Resumo
        if (fila.isNotEmpty()) {
            trabalhar("Abrindo a próxima foto…") { abrirProxima() }
        } else if (prontas.isEmpty()) {
            cancelar()
        }
    }

    // ---------- etapa de filtro ----------

    private suspend fun mostrarAjuste(recorte: Etapa.Recorte, pagina: Bitmap, filtro: Filtro) {
        val previa = withContext(Dispatchers.Default) { Imagens.aplicarFiltro(Imagens.reduzir(pagina, 1400), filtro) }
        etapa = Etapa.Ajuste(recorte, pagina, filtro, previa)
    }

    fun escolherFiltro(filtro: Filtro) {
        val e = etapa as? Etapa.Ajuste ?: return
        if (filtro == e.filtro) return
        filtroPadrao = filtro
        prefs.edit().putString("filtro", filtro.name).apply()
        trabalhar(null) { mostrarAjuste(e.recorte, e.pagina, filtro) }
    }

    fun girarPagina() {
        val e = etapa as? Etapa.Ajuste ?: return
        trabalhar(null) {
            val pagina = withContext(Dispatchers.Default) { Imagens.girar90(e.pagina) }
            mostrarAjuste(e.recorte, pagina, e.filtro)
        }
    }

    fun voltarAoRecorte() {
        val e = etapa as? Etapa.Ajuste ?: return
        etapa = e.recorte
    }

    fun salvarPagina(depois: Depois) {
        val e = etapa as? Etapa.Ajuste ?: return
        trabalhar("Salvando a página…") {
            val arquivo = File(pasta, "pagina_${System.nanoTime()}.jpg")
            withContext(Dispatchers.Default) {
                Imagens.salvarJpeg(Imagens.aplicarFiltro(e.pagina, e.filtro), arquivo)
            }
            prontas.add(arquivo)
            etapa = Etapa.Resumo
            when (depois) {
                Depois.OUTRA_FOTO -> _pedidos.send(Pedido.CAMERA)
                Depois.PROXIMA -> abrirProxima()
                Depois.CONCLUIR -> concluirAgora()
            }
        }
    }

    // ---------- resumo e conclusão ----------

    fun excluirPronta(indice: Int) {
        if (indice !in prontas.indices) return
        prontas.removeAt(indice).delete()
    }

    fun concluir() {
        trabalhar("Salvando…") { concluirAgora() }
    }

    /** Termina as fotos que ainda estão na fila (cantos automáticos) e entrega as páginas. */
    private suspend fun concluirAgora() {
        val total = fila.size
        var feitas = 0
        while (fila.isNotEmpty()) {
            val arquivo = fila.removeAt(0)
            feitas++
            ocupado = "Processando as fotos… $feitas de $total"
            val pagina = File(pasta, "pagina_${System.nanoTime()}.jpg")
            withContext(Dispatchers.Default) {
                val fonte = Imagens.carregar(arquivo, MAX_LADO)
                val cantos = Imagens.detectarFolha(fonte) ?: TELA_TODA
                Imagens.salvarJpeg(Imagens.aplicarFiltro(Imagens.endireitar(fonte, cantos, MAX_LADO), filtroPadrao), pagina)
            }
            arquivo.delete()
            prontas.add(pagina)
        }
        if (prontas.isEmpty()) {
            cancelar()
            return
        }
        _concluidos.send(Resultado(anexarEm, prontas.toList()))
        prontas.clear()
        etapa = Etapa.Resumo
        ativo = false
    }

    fun cancelar() {
        prontas.forEach { it.delete() }
        fila.forEach { it.delete() }
        prontas.clear()
        fila.clear()
        etapa = Etapa.Resumo
        ativo = false
    }

    // ---------- infraestrutura ----------

    private fun trabalhar(mensagem: String?, bloco: suspend () -> Unit) {
        if (tarefa?.isActive == true) return
        tarefa = viewModelScope.launch {
            ocupado = mensagem
            try {
                bloco()
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                avisar("Memória insuficiente para processar esta foto")
            } catch (e: Throwable) {
                avisar("Erro: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                ocupado = null
            }
        }
    }

    /** Apaga fotos esquecidas de sessões anteriores (páginas só depois de 1 hora, por segurança). */
    private fun limparSobras() {
        val agora = System.currentTimeMillis()
        pasta.listFiles()?.forEach { f ->
            val pagina = f.name.startsWith("pagina_")
            if (!pagina || agora - f.lastModified() > 60 * 60 * 1000L) f.delete()
        }
    }

    companion object {
        const val MAX_LADO = 3000
        val TELA_TODA = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        private val MARGEM = floatArrayOf(0.08f, 0.08f, 0.92f, 0.08f, 0.92f, 0.92f, 0.08f, 0.92f)

        fun ehFotoInteira(c: FloatArray) = c.indices.all { kotlin.math.abs(c[it] - TELA_TODA[it]) < 0.005f }

        /** Cantos depois de girar a foto 90° no sentido horário: (x, y) -> (1 - y, x). */
        private fun girarCantos(c: FloatArray): FloatArray =
            DetectorBordas.ordenar(FloatArray(8) { k -> val i = k / 2; if (k % 2 == 0) 1f - c[2 * i + 1] else c[2 * i] })
    }
}

