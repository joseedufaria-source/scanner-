package br.scannerdocs.app.scanner

import kotlin.math.max
import kotlin.math.min

enum class Filtro(val rotulo: String) {
    ORIGINAL("Original"),
    REALCAR("Realçar"),
    CINZA("Cinza"),
    PRETO_BRANCO("Preto e branco"),
}

/**
 * Filtros de "documento": estima o brilho do papel em cada ponto (para tirar sombras e
 * deixar o fundo branco) e reescala as cores em relação a ele.
 * Código Kotlin puro (sem Android) para poder ser testado no computador.
 */
object FiltroPixels {

    /** Brilho do papel numa versão pequena da imagem (luminância 0..255). */
    fun fundo(lum: IntArray, w: Int, h: Int): IntArray {
        var m = lum
        repeat(3) { m = Pixels.maximo(m, w, h) }
        return Pixels.desfocar(m, w, h, 3)
    }

    /**
     * Aplica o filtro numa linha [y] da imagem (pixels ARGB em [src], resultado em [out]).
     * [fundo] tem tamanho [fw]x[fh] e é interpolado para o tamanho [w]x[h] da imagem.
     */
    fun linha(
        src: IntArray, out: IntArray, y: Int, w: Int, h: Int,
        fundo: IntArray, fw: Int, fh: Int, filtro: Filtro,
    ) {
        if (filtro == Filtro.ORIGINAL) {
            System.arraycopy(src, 0, out, 0, w)
            return
        }
        val fy = ((y + 0.5f) * fh / h - 0.5f).coerceIn(0f, (fh - 1).toFloat())
        val y0 = fy.toInt()
        val y1 = min(y0 + 1, fh - 1)
        val ty = fy - y0
        val escalaX = fw.toFloat() / w
        for (x in 0 until w) {
            val fx = ((x + 0.5f) * escalaX - 0.5f).coerceIn(0f, (fw - 1).toFloat())
            val x0 = fx.toInt()
            val x1 = min(x0 + 1, fw - 1)
            val tx = fx - x0
            val cima = fundo[y0 * fw + x0] * (1 - tx) + fundo[y0 * fw + x1] * tx
            val baixo = fundo[y1 * fw + x0] * (1 - tx) + fundo[y1 * fw + x1] * tx
            val bg = max(cima * (1 - ty) + baixo * ty, 1f)

            val p = src[x]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[x] = when (filtro) {
                Filtro.REALCAR -> rgb(nivel(r / bg, 0.25f, 0.92f), nivel(g / bg, 0.25f, 0.92f), nivel(b / bg, 0.25f, 0.92f))
                Filtro.CINZA -> {
                    val v = nivel(Pixels.luminancia(p) / bg, 0.25f, 0.92f)
                    rgb(v, v, v)
                }
                Filtro.PRETO_BRANCO -> {
                    val v = nivel(Pixels.luminancia(p) / bg, 0.66f, 0.80f)
                    rgb(v, v, v)
                }
                Filtro.ORIGINAL -> p
            }
        }
    }

    private fun nivel(razao: Float, baixo: Float, alto: Float): Int =
        (((razao - baixo) / (alto - baixo)).coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
