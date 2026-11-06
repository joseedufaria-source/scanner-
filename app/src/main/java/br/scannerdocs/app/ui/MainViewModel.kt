package br.scannerdocs.app.ui

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.scannerdocs.app.data.DocStore
import br.scannerdocs.app.data.ScanDoc
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date

sealed interface Tela {
    data object Inicio : Tela
    data class Documento(val id: String) : Tela
    data class Pagina(val id: String, val indice: Int) : Tela
}

data class TextoExtraido(val titulo: String, val texto: String)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = DocStore(app)
    private val mutex = Mutex()
    private var ocrCliente: TextRecognizer? = null

    var docs by mutableStateOf<List<ScanDoc>>(emptyList())
        private set
    var carregado by mutableStateOf(false)
        private set
    var tela by mutableStateOf<Tela>(Tela.Inicio)
        private set

    /** Mensagem do aviso "aguarde" (null = nada em andamento). */
    var ocupado by mutableStateOf<String?>(null)
        private set
    var textoExtraido by mutableStateOf<TextoExtraido?>(null)
        private set

    private val _avisos = Channel<String>(Channel.BUFFERED)
    val avisos = _avisos.receiveAsFlow()

    private val _envios = Channel<Intent>(Channel.BUFFERED)
    /** Intents de compartilhamento para a tela abrir. */
    val envios = _envios.receiveAsFlow()

    /** Documento que vai receber as páginas do próximo scan (null = documento novo). */
    private var anexarEm: String? = null

    init {
        viewModelScope.launch {
            docs = withContext(Dispatchers.IO) { store.list() }
            carregado = true
        }
    }

    fun doc(id: String): ScanDoc? = docs.firstOrNull { it.id == id }

    fun avisar(msg: String) {
        _avisos.trySend(msg)
    }

    // ---------- navegação ----------

    fun abrirDocumento(id: String) {
        tela = Tela.Documento(id)
    }

    fun abrirPagina(id: String, indice: Int) {
        tela = Tela.Pagina(id, indice)
    }

    fun mudarPagina(indice: Int) {
        val t = tela
        if (t is Tela.Pagina && t.indice != indice) tela = t.copy(indice = indice)
    }

    fun voltar() {
        tela = when (val t = tela) {
            is Tela.Pagina -> Tela.Documento(t.id)
            else -> Tela.Inicio
        }
    }

    // ---------- scanner ----------

    fun prepararScan(anexarEm: String?) {
        this.anexarEm = anexarEm
    }

    fun aoEscanear(uris: List<Uri>) {
        val alvo = anexarEm
        anexarEm = null
        if (uris.isEmpty()) return
        executar("Salvando ${paginas(uris.size)}…") {
            if (alvo != null && store.get(alvo) != null) {
                store.addPages(alvo, uris)
                avisar(if (uris.size == 1) "1 página adicionada" else "${uris.size} páginas adicionadas")
                Tela.Documento(alvo)
            } else {
                val doc = store.create(nomePadrao(), uris)
                Tela.Documento(doc.id)
            }
        }
    }

    // ---------- edição ----------

    fun renomear(id: String, nome: String) = executar(null) {
        store.rename(id, nome)
        null
    }

    fun excluir(id: String) = executar(null) {
        store.delete(id)
        avisar("Documento excluído")
        Tela.Inicio
    }

    fun excluirPagina(id: String, indice: Int) = executar(null) {
        val doc = store.deletePage(id, indice)
        if (doc == null) {
            avisar("Documento excluído")
            Tela.Inicio
        } else {
            Tela.Pagina(id, indice.coerceAtMost(doc.pages.lastIndex))
        }
    }

    fun moverPagina(id: String, de: Int, para: Int) = executar(null) {
        store.movePage(id, de, para)
        Tela.Pagina(id, para)
    }

    fun girarPagina(id: String, indice: Int) = executar("Girando…") {
        store.rotatePage(id, indice)
        null
    }

    // ---------- PDF e compartilhamento ----------

    fun salvarPdf(id: String, destino: Uri) = executar("Gerando PDF…") {
        val doc = store.get(id) ?: return@executar null
        val out = getApplication<Application>().contentResolver.openOutputStream(destino, "wt")
            ?: throw IOException("Não foi possível gravar o arquivo")
        out.use { store.writePdf(doc, it) }
        avisar("PDF salvo")
        null
    }

    fun compartilharPdf(id: String) = executar("Gerando PDF…") {
        val doc = store.get(id) ?: return@executar null
        val uri = uriDe(store.pdfForSharing(doc))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, doc.name)
            clipData = ClipData.newRawUri(doc.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        _envios.send(Intent.createChooser(intent, "Compartilhar PDF"))
        null
    }

    fun compartilharImagens(id: String) = executar("Preparando imagens…") {
        val doc = store.get(id) ?: return@executar null
        val uris = store.imagesForSharing(doc).map { uriDe(it) }
        val clip = ClipData.newRawUri(doc.name, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = "image/jpeg"
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        _envios.send(Intent.createChooser(intent, "Compartilhar imagens"))
        null
    }

    private fun uriDe(file: File): Uri {
        val app = getApplication<Application>()
        return FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
    }

    // ---------- texto (OCR) ----------

    fun extrairTexto(id: String, pagina: Int? = null) = executar("Lendo o texto…") {
        val doc = store.get(id) ?: return@executar null
        val arquivos = doc.pageFiles
        val texto = if (pagina != null) {
            lerTexto(arquivos[pagina])
        } else {
            arquivos.mapIndexed { i, f ->
                if (arquivos.size > 1) {
                    withContext(Dispatchers.Main) { ocupado = "Lendo o texto… página ${i + 1} de ${arquivos.size}" }
                }
                val t = lerTexto(f)
                if (arquivos.size > 1) "— Página ${i + 1} —\n$t" else t
            }.joinToString("\n\n")
        }
        val titulo = if (pagina != null) "Texto da página ${pagina + 1}" else "Texto do documento"
        withContext(Dispatchers.Main) { textoExtraido = TextoExtraido(titulo, texto.trim()) }
        null
    }

    fun fecharTexto() {
        textoExtraido = null
    }

    private suspend fun lerTexto(file: File): String {
        val image = InputImage.fromFilePath(getApplication<Application>(), Uri.fromFile(file))
        val cliente = ocrCliente
            ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { ocrCliente = it }
        return cliente.process(image).await().text
    }

    // ---------- infraestrutura ----------

    /**
     * Roda [bloco] fora da tela principal, uma operação por vez, e depois recarrega a lista.
     * O bloco devolve a tela para onde ir (ou null para ficar onde está).
     */
    private fun executar(mensagem: String?, bloco: suspend () -> Tela?) {
        viewModelScope.launch {
            ocupado = mensagem
            var destino: Tela? = null
            try {
                destino = mutex.withLock { withContext(Dispatchers.IO) { bloco() } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                avisar("Erro: ${e.message ?: e.javaClass.simpleName}")
            }
            docs = withContext(Dispatchers.IO) { store.list() }
            ocupado = null
            if (destino != null) tela = destino
            corrigirTela()
        }
    }

    /** Se o documento/página aberto deixou de existir, volta para um lugar válido. */
    private fun corrigirTela() {
        when (val t = tela) {
            is Tela.Documento -> if (doc(t.id) == null) tela = Tela.Inicio
            is Tela.Pagina -> {
                val d = doc(t.id)
                tela = when {
                    d == null || d.pages.isEmpty() -> Tela.Inicio
                    t.indice > d.pages.lastIndex -> t.copy(indice = d.pages.lastIndex)
                    else -> t
                }
            }
            Tela.Inicio -> Unit
        }
    }

    private fun nomePadrao() = "Scan " + SimpleDateFormat("dd-MM-yyyy HH'h'mm", PT_BR).format(Date())

    override fun onCleared() {
        super.onCleared()
        ocrCliente?.close()
    }
}
