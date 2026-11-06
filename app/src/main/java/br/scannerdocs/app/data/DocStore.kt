package br.scannerdocs.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import br.scannerdocs.app.pdf.JpegInfo
import br.scannerdocs.app.pdf.PdfWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Guarda os documentos na memória interna do app (files/docs/<id>/).
 * Não é thread-safe: quem chama deve serializar as operações (o ViewModel usa um Mutex).
 */
class DocStore(private val context: Context) {

    private val root = File(context.filesDir, "docs").apply { mkdirs() }

    fun list(): List<ScanDoc> {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        val docs = mutableListOf<ScanDoc>()
        for (dir in dirs) {
            val doc = load(dir)
            if (doc != null) {
                docs += doc
            } else if (System.currentTimeMillis() - dir.lastModified() > 60 * 60 * 1000L) {
                // Pasta sem doc.json (gravação interrompida): limpa depois de 1 hora.
                dir.deleteRecursively()
            }
        }
        return docs.sortedByDescending { it.updatedAt }
    }

    fun get(id: String): ScanDoc? = load(File(root, id))

    fun create(name: String, sources: List<Uri>): ScanDoc {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val pages = sources.map { copyIn(it, dir) }
        val now = System.currentTimeMillis()
        return save(ScanDoc(id, name, now, now, pages, dir))
    }

    fun addPages(id: String, sources: List<Uri>): ScanDoc? {
        val doc = get(id) ?: return null
        val added = sources.map { copyIn(it, doc.dir) }
        return save(doc.copy(pages = doc.pages + added, updatedAt = System.currentTimeMillis()))
    }

    fun rename(id: String, name: String): ScanDoc? {
        val doc = get(id) ?: return null
        return save(doc.copy(name = name, updatedAt = System.currentTimeMillis()))
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    /** Apaga uma página. Se era a última, apaga o documento e devolve null. */
    fun deletePage(id: String, index: Int): ScanDoc? {
        val doc = get(id) ?: return null
        if (index !in doc.pages.indices) return doc
        File(doc.dir, doc.pages[index]).delete()
        val pages = doc.pages.toMutableList().apply { removeAt(index) }
        if (pages.isEmpty()) {
            delete(id)
            return null
        }
        return save(doc.copy(pages = pages, updatedAt = System.currentTimeMillis()))
    }

    fun movePage(id: String, from: Int, to: Int): ScanDoc? {
        val doc = get(id) ?: return null
        if (from !in doc.pages.indices || to !in doc.pages.indices) return doc
        val pages = doc.pages.toMutableList()
        pages.add(to, pages.removeAt(from))
        return save(doc.copy(pages = pages, updatedAt = System.currentTimeMillis()))
    }

    /** Gira a página 90° no sentido horário. Grava num arquivo novo para a miniatura atualizar. */
    fun rotatePage(id: String, index: Int): ScanDoc? {
        val doc = get(id) ?: return null
        val oldName = doc.pages.getOrNull(index) ?: return doc
        val old = File(doc.dir, oldName)
        val bmp = BitmapFactory.decodeFile(old.path) ?: throw IOException("Não foi possível ler a página")
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(90f) }, true)
        if (rotated !== bmp) bmp.recycle()
        val newName = newPageName()
        File(doc.dir, newName).outputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        rotated.recycle()
        old.delete()
        val pages = doc.pages.toMutableList().apply { this[index] = newName }
        return save(doc.copy(pages = pages, updatedAt = System.currentTimeMillis()))
    }

    fun writePdf(doc: ScanDoc, out: OutputStream) = PdfWriter.write(doc.pageFiles, out)

    /** Gera o PDF numa pasta temporária, para compartilhar. */
    fun pdfForSharing(doc: ScanDoc): File {
        val file = File(shareDir(), fileName(doc.name) + ".pdf")
        file.outputStream().use { writePdf(doc, it) }
        return file
    }

    /** Copia as páginas (JPG) para a pasta temporária, com o nome do documento. */
    fun imagesForSharing(doc: ScanDoc): List<File> {
        val dir = shareDir()
        val base = fileName(doc.name)
        return doc.pageFiles.mapIndexed { i, src ->
            val name = if (doc.pages.size == 1) "$base.jpg" else "$base - ${i + 1}.jpg"
            src.copyTo(File(dir, name), overwrite = true)
        }
    }

    private fun shareDir(): File =
        File(context.cacheDir, "compartilhar").apply {
            deleteRecursively()
            mkdirs()
        }

    private fun load(dir: File): ScanDoc? {
        val meta = File(dir, META)
        if (!meta.isFile) return null
        return try {
            val o = JSONObject(meta.readText())
            val arr = o.getJSONArray("pages")
            val pages = (0 until arr.length()).map { arr.getString(it) }.filter { File(dir, it).isFile }
            ScanDoc(
                id = dir.name,
                name = o.getString("name"),
                createdAt = o.getLong("createdAt"),
                updatedAt = o.getLong("updatedAt"),
                pages = pages,
                dir = dir,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun save(doc: ScanDoc): ScanDoc {
        val json = JSONObject()
            .put("name", doc.name)
            .put("createdAt", doc.createdAt)
            .put("updatedAt", doc.updatedAt)
            .put("pages", JSONArray(doc.pages))
            .toString()
        val tmp = File(doc.dir, "$META.tmp")
        tmp.writeText(json)
        val meta = File(doc.dir, META)
        if (!tmp.renameTo(meta)) {
            meta.writeText(json)
            tmp.delete()
        }
        return doc
    }

    /** Copia uma imagem para dentro do documento, sempre como JPEG. */
    private fun copyIn(uri: Uri, dir: File): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Não foi possível ler a imagem escaneada")
        val name = newPageName()
        val dest = File(dir, name)
        if (JpegInfo.read(bytes) != null) {
            dest.writeBytes(bytes)
        } else {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IOException("Formato de imagem não suportado")
            dest.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bmp.recycle()
        }
        return name
    }

    private fun newPageName() = "pag_${UUID.randomUUID()}.jpg"

    companion object {
        private const val META = "doc.json"

        /** Nome seguro para arquivo (sem / : * ? etc.). */
        fun fileName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "-").trim().take(80).ifEmpty { "documento" }
    }
}
