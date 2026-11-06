package br.scannerdocs.app.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import java.io.File
import java.io.IOException
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Funções de imagem que dependem do Android (Bitmap). */
object Imagens {

    /** Lê a foto já de pé (respeitando a rotação gravada pela câmera) e com o lado maior <= [maxLado]. */
    fun carregar(file: File, maxLado: Int): Bitmap {
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, limites)
        if (limites.outWidth <= 0 || limites.outHeight <= 0) throw IOException("Não foi possível abrir a foto")
        var amostra = 1
        while (max(limites.outWidth, limites.outHeight) / (amostra * 2) >= maxLado) amostra *= 2
        val opcoes = BitmapFactory.Options().apply {
            inSampleSize = amostra
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.path, opcoes) ?: throw IOException("Não foi possível abrir a foto")

        val m = Matrix()
        val escala = min(1f, maxLado.toFloat() / max(bmp.width, bmp.height))
        if (escala < 1f) m.postScale(escala, escala)
        val orientacao = try {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        when (orientacao) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f)
                m.postScale(-1f, 1f)
            }
        }
        if (m.isIdentity) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun reduzir(bmp: Bitmap, maxLado: Int): Bitmap {
        val escala = maxLado.toFloat() / max(bmp.width, bmp.height)
        if (escala >= 1f) return bmp
        return Bitmap.createScaledBitmap(
            bmp,
            max(1, (bmp.width * escala).roundToInt()),
            max(1, (bmp.height * escala).roundToInt()),
            true,
        )
    }

    fun girar90(bmp: Bitmap): Bitmap =
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(90f) }, true)

    /** Cantos da folha (normalizados, ordem sup.esq., sup.dir., inf.dir., inf.esq.) ou null se não achar. */
    fun detectarFolha(bmp: Bitmap): FloatArray? {
        val pequena = reduzir(bmp, 400)
        val w = pequena.width
        val h = pequena.height
        val px = IntArray(w * h)
        pequena.getPixels(px, 0, w, 0, 0, w, h)
        val cinza = IntArray(px.size) { Pixels.luminancia(px[it]) }
        return DetectorBordas.detectar(cinza, w, h)
    }

    /** Recorta a folha e corrige a perspectiva, deixando-a retangular. */
    fun endireitar(fonte: Bitmap, cantos: FloatArray, maxLado: Int = 3000): Bitmap {
        val q = FloatArray(8) { k -> if (k % 2 == 0) cantos[k] * fonte.width else cantos[k] * fonte.height }
        fun dist(a: Int, b: Int) = hypot(q[2 * a] - q[2 * b], q[2 * a + 1] - q[2 * b + 1])
        var w = max(dist(0, 1), dist(3, 2))
        var h = max(dist(0, 3), dist(1, 2))
        if (w < 8f || h < 8f) throw IOException("Recorte pequeno demais")
        val escala = min(1f, maxLado / max(w, h))
        w *= escala
        h *= escala
        val largura = max(1, w.roundToInt())
        val altura = max(1, h.roundToInt())
        val destino = floatArrayOf(0f, 0f, largura.toFloat(), 0f, largura.toFloat(), altura.toFloat(), 0f, altura.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(q, 0, destino, 0, 4)) throw IOException("Não foi possível endireitar a página")
        val out = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(fonte, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    fun aplicarFiltro(src: Bitmap, filtro: Filtro): Bitmap {
        if (filtro == Filtro.ORIGINAL) return src
        val w = src.width
        val h = src.height
        val pequena = reduzir(src, max(8, max(w, h) / 12))
        val fw = pequena.width
        val fh = pequena.height
        val pp = IntArray(fw * fh)
        pequena.getPixels(pp, 0, fw, 0, 0, fw, fh)
        val fundo = FiltroPixels.fundo(IntArray(pp.size) { Pixels.luminancia(pp[it]) }, fw, fh)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val linha = IntArray(w)
        val saida = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(linha, 0, w, 0, y, w, 1)
            FiltroPixels.linha(linha, saida, y, w, h, fundo, fw, fh, filtro)
            out.setPixels(saida, 0, w, 0, y, w, 1)
        }
        return out
    }

    fun salvarJpeg(bmp: Bitmap, file: File, qualidade: Int = 88) {
        file.parentFile?.mkdirs()
        file.outputStream().use { if (!bmp.compress(Bitmap.CompressFormat.JPEG, qualidade, it)) throw IOException("Falha ao gravar a página") }
    }
}
