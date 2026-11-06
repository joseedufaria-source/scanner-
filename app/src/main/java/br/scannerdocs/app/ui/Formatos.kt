package br.scannerdocs.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val PT_BR: Locale = Locale.forLanguageTag("pt-BR")

fun paginas(n: Int) = if (n == 1) "1 página" else "$n páginas"

fun dataHora(ms: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", PT_BR).format(Date(ms))

tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("Activity não encontrada")
}
