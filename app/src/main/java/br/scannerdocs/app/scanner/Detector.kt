package br.scannerdocs.app.scanner

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Acha a folha numa foto. Trabalha numa imagem pequena em tons de cinza (lado maior ~400 px)
 * e devolve os 4 cantos normalizados (0..1) na ordem: sup. esq., sup. dir., inf. dir., inf. esq.
 *
 * Tenta dois jeitos e fica com o que forma o quadrilátero mais "limpo":
 *  1. bordas: contorno fechado de bordas fortes (folha sobre qualquer fundo com contraste);
 *  2. brilho: maior região clara (folha branca sobre mesa escura).
 * Código Kotlin puro (sem Android) para poder ser testado no computador.
 */
object DetectorBordas {

    fun detectar(gray: IntArray, w: Int, h: Int): FloatArray? {
        if (w < 16 || h < 16 || gray.size != w * h) return null
        val suave = Pixels.desfocar(Pixels.desfocar(gray, w, h, 2), w, h, 2)
        val mag = gradiente(suave, w, h)
        val media = mag.sum().toFloat() / mag.size
        // Vários níveis de sensibilidade: borda forte (mesa escura) até borda fraca (folha na sombra).
        val limiares = listOf(max(40f, media * 2.5f), max(24f, media * 1.5f), 14f).distinct()
        val candidatos = limiares.mapNotNull { porBordas(suave, mag, w, h, it) } + listOfNotNull(porBrilho(suave, w, h))
        // Prefere o candidato mais parecido com um quadrilátero e com bordas mais nítidas.
        val melhor = candidatos.filter { it.nota >= 0.85f }
            .maxByOrNull { it.nota * min(1f, it.contraste / 40f) } ?: return null
        return melhor.cantos
    }

    private class Candidato(val cantos: FloatArray, val nota: Float, val contraste: Float)

    /** Intensidade da borda (Sobel) em cada pixel. */
    private fun gradiente(g: IntArray, w: Int, h: Int): IntArray {
        val mag = IntArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = (g[i - w + 1] + 2 * g[i + 1] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - 1] + g[i + w - 1])
                val gy = (g[i + w - 1] + 2 * g[i + w] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - w] + g[i - w + 1])
                mag[i] = abs(gx) + abs(gy)
            }
        }
        return mag
    }

    private fun porBordas(g: IntArray, mag: IntArray, w: Int, h: Int, limiar: Float): Candidato? {
        val n = w * h
        var borda = BooleanArray(n) { mag[it] > limiar }
        borda = Pixels.dilatar(Pixels.dilatar(borda, w, h), w, h)

        // "fora" = tudo que se alcança a partir da margem da foto sem atravessar uma borda.
        val fora = BooleanArray(n)
        val fila = IntArray(n)
        var ini = 0
        var fim = 0
        fun semear(i: Int) {
            if (!borda[i] && !fora[i]) {
                fora[i] = true
                fila[fim++] = i
            }
        }
        for (x in 0 until w) {
            semear(x)
            semear((h - 1) * w + x)
        }
        for (y in 0 until h) {
            semear(y * w)
            semear(y * w + w - 1)
        }
        while (ini < fim) {
            val i = fila[ini++]
            val x = i % w
            if (x > 0) semear(i - 1)
            if (x < w - 1) semear(i + 1)
            if (i >= w) semear(i - w)
            if (i < n - w) semear(i + w)
        }
        // A faixa de borda engordou ~3 px para fora da folha: encolhe o resultado de volta.
        return avaliar(g, BooleanArray(n) { !fora[it] }, w, h, encolher = 3f)
    }

    private fun porBrilho(g: IntArray, w: Int, h: Int): Candidato? {
        val t = otsu(g)
        return avaliar(g, BooleanArray(g.size) { g[it] > t }, w, h, encolher = 0f)
    }

    private fun avaliar(g: IntArray, mascara: BooleanArray, w: Int, h: Int, encolher: Float): Candidato? {
        val (comp, area) = maiorComponente(mascara, w, h) ?: return null
        val fracao = area.toFloat() / (w * h)
        if (fracao < 0.12f || fracao > 0.97f) return null
        val casco = cascoConvexo(contorno(comp, w, h))
        if (casco.size < 8) return null
        val quad = quadrilatero(casco) ?: return null
        val areaQuad = areaPoligono(quad)
        if (areaQuad < 0.10f * w * h) return null
        val razao = areaQuad / area
        val nota = if (razao > 1f) 1f / razao else razao
        val final = if (encolher > 0f) encolherQuad(quad, encolher) else quad
        // Uma folha de verdade tem contraste nos 4 lados; sombra ou reflexo não.
        val contraste = contrasteMinimoDosLados(g, w, h, final)
        if (contraste < 10f) return null
        val norm = FloatArray(8) { k ->
            val v = if (k % 2 == 0) (final[k] + 0.5f) / w else (final[k] + 0.5f) / h
            v.coerceIn(0f, 1f)
        }
        return Candidato(norm, nota, contraste)
    }

    /**
     * Para cada lado do quadrilátero compara o brilho logo dentro e logo fora da linha.
     * Devolve o menor contraste entre os lados que dá para medir (lados colados na margem da foto não contam).
     */
    private fun contrasteMinimoDosLados(g: IntArray, w: Int, h: Int, q: FloatArray): Float {
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        fun valor(x: Float, y: Float): Int? {
            val xi = x.toInt()
            val yi = y.toInt()
            return if (xi in 0 until w && yi in 0 until h) g[yi * w + xi] else null
        }
        var menor = Float.MAX_VALUE
        for (k in 0 until 4) {
            val ax = q[2 * k]
            val ay = q[2 * k + 1]
            val bx = q[(2 * k + 2) % 8]
            val by = q[(2 * k + 3) % 8]
            val comp = kotlin.math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay)).coerceAtLeast(1f)
            var nx = (by - ay) / comp
            var ny = -(bx - ax) / comp
            if (nx * ((ax + bx) / 2 - cx) + ny * ((ay + by) / 2 - cy) < 0) {
                nx = -nx
                ny = -ny
            }
            var soma = 0f
            var validos = 0
            for (s in 1..19) {
                val t = s / 20f
                val px = ax + (bx - ax) * t
                val py = ay + (by - ay) * t
                val dentro = valor(px - nx * 5, py - ny * 5) ?: continue
                val fora = valor(px + nx * 5, py + ny * 5) ?: continue
                soma += abs(dentro - fora)
                validos++
            }
            if (validos >= 6) menor = min(menor, soma / validos)
        }
        return menor
    }

    /** Ordena 4 pontos (x0,y0,...,x3,y3) como sup. esq., sup. dir., inf. dir., inf. esq. */
    fun ordenar(q: FloatArray): FloatArray {
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        // Com y para baixo, ângulo crescente = sentido horário na tela.
        val idx = (0 until 4).sortedBy { atan2((q[2 * it + 1] - cy).toDouble(), (q[2 * it] - cx).toDouble()) }
        val inicio = (0 until 4).minByOrNull { q[2 * idx[it]] + q[2 * idx[it] + 1] } ?: 0
        return FloatArray(8) { k -> q[2 * idx[(inicio + k / 2) % 4] + k % 2] }
    }

    fun areaPoligono(q: FloatArray): Float {
        var s = 0f
        val m = q.size / 2
        for (i in 0 until m) {
            val j = (i + 1) % m
            s += q[2 * i] * q[2 * j + 1] - q[2 * j] * q[2 * i + 1]
        }
        return abs(s) / 2f
    }

    /** Maior quadrilátero com vértices no casco, procurando a orientação que dá mais área. */
    private fun quadrilatero(casco: List<IntArray>): FloatArray? {
        var melhor: FloatArray? = null
        var melhorArea = 0f
        var grau = 0
        while (grau < 90) {
            val base = Math.toRadians(grau + 45.0)
            val q = FloatArray(8)
            for (k in 0 until 4) {
                val a = base + k * PI / 2
                val dx = cos(a)
                val dy = sin(a)
                var escolhido = casco[0]
                var maior = -Double.MAX_VALUE
                for (p in casco) {
                    val v = p[0] * dx + p[1] * dy
                    if (v > maior) {
                        maior = v
                        escolhido = p
                    }
                }
                q[2 * k] = escolhido[0].toFloat()
                q[2 * k + 1] = escolhido[1].toFloat()
            }
            val a = areaPoligono(q)
            if (a > melhorArea) {
                melhorArea = a
                melhor = q
            }
            grau += 2
        }
        return melhor?.let { ordenar(it) }
    }

    private fun encolherQuad(q: FloatArray, px: Float): FloatArray {
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        return FloatArray(8) { k ->
            val i = k / 2
            val dx = q[2 * i] - cx
            val dy = q[2 * i + 1] - cy
            val d = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val f = max(0f, d - px * 1.41f) / d
            if (k % 2 == 0) cx + dx * f else cy + dy * f
        }
    }

    private fun otsu(g: IntArray): Int {
        val hist = IntArray(256)
        for (v in g) hist[v.coerceIn(0, 255)]++
        val total = g.size
        var soma = 0.0
        for (i in 0..255) soma += i * hist[i].toDouble()
        var somaB = 0.0
        var pesoB = 0
        var maiorVar = 0.0
        var t = 127
        for (i in 0..255) {
            pesoB += hist[i]
            if (pesoB == 0) continue
            val pesoF = total - pesoB
            if (pesoF == 0) break
            somaB += i * hist[i].toDouble()
            val mB = somaB / pesoB
            val mF = (soma - somaB) / pesoF
            val v = pesoB.toDouble() * pesoF * (mB - mF) * (mB - mF)
            if (v > maiorVar) {
                maiorVar = v
                t = i
            }
        }
        return t
    }

    private fun maiorComponente(m: BooleanArray, w: Int, h: Int): Pair<BooleanArray, Int>? {
        val n = w * h
        val rotulo = IntArray(n)
        val fila = IntArray(n)
        var atual = 0
        var melhorRotulo = 0
        var melhorArea = 0
        for (s in 0 until n) {
            if (!m[s] || rotulo[s] != 0) continue
            atual++
            var ini = 0
            var fim = 0
            fila[fim++] = s
            rotulo[s] = atual
            while (ini < fim) {
                val i = fila[ini++]
                val x = i % w
                if (x > 0 && m[i - 1] && rotulo[i - 1] == 0) { rotulo[i - 1] = atual; fila[fim++] = i - 1 }
                if (x < w - 1 && m[i + 1] && rotulo[i + 1] == 0) { rotulo[i + 1] = atual; fila[fim++] = i + 1 }
                if (i >= w && m[i - w] && rotulo[i - w] == 0) { rotulo[i - w] = atual; fila[fim++] = i - w }
                if (i < n - w && m[i + w] && rotulo[i + w] == 0) { rotulo[i + w] = atual; fila[fim++] = i + w }
            }
            if (fim > melhorArea) {
                melhorArea = fim
                melhorRotulo = atual
            }
        }
        if (melhorRotulo == 0) return null
        return BooleanArray(n) { rotulo[it] == melhorRotulo } to melhorArea
    }

    private fun contorno(m: BooleanArray, w: Int, h: Int): List<IntArray> {
        val pts = ArrayList<IntArray>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!m[i]) continue
                val naBorda = x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
                    !m[i - 1] || !m[i + 1] || !m[i - w] || !m[i + w]
                if (naBorda) pts.add(intArrayOf(x, y))
            }
        }
        return pts
    }

    /** Casco convexo (cadeia monótona de Andrew). */
    private fun cascoConvexo(pontos: List<IntArray>): List<IntArray> {
        if (pontos.size < 3) return pontos
        val p = pontos.sortedWith(compareBy<IntArray>({ it[0] }, { it[1] }))
        fun cruz(o: IntArray, a: IntArray, b: IntArray): Long =
            (a[0] - o[0]).toLong() * (b[1] - o[1]) - (a[1] - o[1]).toLong() * (b[0] - o[0])
        val casco = ArrayList<IntArray>(p.size * 2)
        for (pt in p) {
            while (casco.size >= 2 && cruz(casco[casco.size - 2], casco[casco.size - 1], pt) <= 0) casco.removeAt(casco.size - 1)
            casco.add(pt)
        }
        val limite = casco.size + 1
        for (i in p.size - 2 downTo 0) {
            val pt = p[i]
            while (casco.size >= limite && cruz(casco[casco.size - 2], casco[casco.size - 1], pt) <= 0) casco.removeAt(casco.size - 1)
            casco.add(pt)
        }
        casco.removeAt(casco.size - 1)
        return casco
    }
}

/** Operações simples em imagens guardadas como IntArray (um valor 0..255 por pixel). */
object Pixels {

    /** Desfoque de caixa separável com raio [r]. */
    fun desfocar(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        val janela = 2 * r + 1
        for (y in 0 until h) {
            val base = y * w
            var soma = 0
            for (k in -r..r) soma += src[base + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[base + x] = soma / janela
                soma += src[base + min(x + r + 1, w - 1)] - src[base + max(x - r, 0)]
            }
        }
        for (x in 0 until w) {
            var soma = 0
            for (k in -r..r) soma += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = soma / janela
                soma += tmp[min(y + r + 1, h - 1) * w + x] - tmp[max(y - r, 0) * w + x]
            }
        }
        return out
    }

    fun dilatar(m: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = false
                var dy = -1
                while (dy <= 1 && !v) {
                    val yy = y + dy
                    if (yy in 0 until h) {
                        var dx = -1
                        while (dx <= 1) {
                            val xx = x + dx
                            if (xx in 0 until w && m[yy * w + xx]) {
                                v = true
                                break
                            }
                            dx++
                        }
                    }
                    dy++
                }
                out[y * w + x] = v
            }
        }
        return out
    }

    /** Máximo numa vizinhança 3x3 (apaga traços escuros finos, como letras). */
    fun maximo(src: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = 0
                for (yy in max(0, y - 1)..min(h - 1, y + 1)) {
                    for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                        val p = src[yy * w + xx]
                        if (p > v) v = p
                    }
                }
                out[y * w + x] = v
            }
        }
        return out
    }

    fun luminancia(argb: Int): Int =
        (((argb shr 16) and 0xFF) * 77 + ((argb shr 8) and 0xFF) * 150 + (argb and 0xFF) * 29) shr 8
}
