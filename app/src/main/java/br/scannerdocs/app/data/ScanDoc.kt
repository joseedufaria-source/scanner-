package br.scannerdocs.app.data

import java.io.File

/** Um documento escaneado: uma pasta com as páginas em JPEG e um doc.json com os dados. */
data class ScanDoc(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<String>,
    val dir: File,
) {
    val pageFiles: List<File> get() = pages.map { File(dir, it) }
}
