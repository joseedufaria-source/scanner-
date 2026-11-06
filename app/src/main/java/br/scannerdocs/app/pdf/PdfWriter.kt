package br.scannerdocs.app.pdf

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/**
 * Monta um PDF com uma página por imagem, embutindo o JPEG original (sem recomprimir).
 * Assim o PDF fica pequeno e com a mesma qualidade da foto escaneada.
 * Cada página tem a largura de uma folha A4 e a altura proporcional à imagem.
 */
object PdfWriter {

    private const val PAGE_WIDTH = 595.28f // largura A4 em pontos (1/72 pol.)

    fun write(images: List<File>, output: OutputStream) {
        require(images.isNotEmpty()) { "Documento sem páginas" }
        val out = CountingStream(BufferedOutputStream(output))
        val offsets = HashMap<Int, Long>()
        val total = 2 + 3 * images.size // catálogo, páginas e (página, conteúdo, imagem) por página

        fun obj(num: Int, body: () -> Unit) {
            offsets[num] = out.count
            out.ascii("$num 0 obj\n")
            body()
            out.ascii("\nendobj\n")
        }

        out.ascii("%PDF-1.4\n")
        out.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

        obj(1) { out.ascii("<< /Type /Catalog /Pages 2 0 R >>") }
        val kids = images.indices.joinToString(" ") { "${3 + 3 * it} 0 R" }
        obj(2) { out.ascii("<< /Type /Pages /Kids [$kids] /Count ${images.size} >>") }

        images.forEachIndexed { i, file ->
            val bytes = file.readBytes()
            val info = JpegInfo.read(bytes) ?: throw IOException("Página ${i + 1} não é um JPEG válido")
            val pageNum = 3 + 3 * i
            val w = PAGE_WIDTH
            val h = PAGE_WIDTH * info.height / info.width

            obj(pageNum) {
                out.ascii(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(w)} ${n(h)}] " +
                        "/Resources << /XObject << /Im0 ${pageNum + 2} 0 R >> /ProcSet [/PDF /ImageB /ImageC] >> " +
                        "/Contents ${pageNum + 1} 0 R >>"
                )
            }

            val content = "q\n${n(w)} 0 0 ${n(h)} 0 0 cm\n/Im0 Do\nQ\n".toByteArray(Charsets.US_ASCII)
            obj(pageNum + 1) {
                out.ascii("<< /Length ${content.size} >>\nstream\n")
                out.write(content)
                out.ascii("endstream")
            }

            val colorSpace = when (info.components) {
                1 -> "/DeviceGray"
                4 -> "/DeviceCMYK /Decode [1 0 1 0 1 0 1 0]"
                else -> "/DeviceRGB"
            }
            obj(pageNum + 2) {
                out.ascii(
                    "<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
                        "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length ${bytes.size} >>\nstream\n"
                )
                out.write(bytes)
                out.ascii("\nendstream")
            }
        }

        val xref = out.count
        out.ascii("xref\n0 ${total + 1}\n0000000000 65535 f \n")
        for (k in 1..total) {
            out.ascii(String.format(Locale.US, "%010d 00000 n \n", offsets.getValue(k)))
        }
        out.ascii("trailer\n<< /Size ${total + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.flush()
    }

    /** Número com ponto decimal, independente do idioma do celular. */
    private fun n(v: Float) = String.format(Locale.US, "%.2f", v)

    private class CountingStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()

        fun ascii(s: String) = write(s.toByteArray(Charsets.US_ASCII))
    }
}

/** Largura, altura e número de canais de um JPEG, lidos do cabeçalho (marcador SOF). */
class JpegInfo(val width: Int, val height: Int, val components: Int) {
    companion object {
        fun read(b: ByteArray): JpegInfo? {
            fun u8(i: Int) = b[i].toInt() and 0xFF
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)

            if (b.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return null
            var i = 2
            while (i + 9 < b.size) {
                if (u8(i) != 0xFF) {
                    i++
                    continue
                }
                val marker = u8(i + 1)
                when {
                    marker == 0xFF -> i++ // byte de preenchimento
                    marker == 0x01 || marker in 0xD0..0xD8 -> i += 2 // marcadores sem tamanho
                    marker == 0xDA || marker == 0xD9 -> return null // chegou nos dados sem achar o SOF
                    marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> {
                        val h = u16(i + 5)
                        val w = u16(i + 7)
                        val c = u8(i + 9)
                        return if (w > 0 && h > 0) JpegInfo(w, h, c) else null
                    }
                    else -> i += 2 + u16(i + 2)
                }
            }
            return null
        }
    }
}
