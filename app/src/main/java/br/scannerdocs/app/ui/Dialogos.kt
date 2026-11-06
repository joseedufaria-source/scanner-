package br.scannerdocs.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.scannerdocs.app.data.ScanDoc

@Composable
fun DialogoRenomear(atual: String, onConfirmar: (String) -> Unit, onCancelar: () -> Unit) {
    var nome by remember { mutableStateOf(TextFieldValue(atual, TextRange(0, atual.length))) }
    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text("Renomear") },
        text = {
            val foco = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { foco.requestFocus() } }
            OutlinedTextField(
                value = nome,
                onValueChange = { nome = it },
                label = { Text("Nome do documento") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(foco),
            )
        },
        confirmButton = {
            TextButton(
                enabled = nome.text.isNotBlank(),
                onClick = { onConfirmar(nome.text.trim()) },
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onCancelar) { Text("Cancelar") } },
    )
}

@Composable
fun DialogoExcluir(doc: ScanDoc, onConfirmar: () -> Unit, onCancelar: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text("Excluir documento?") },
        text = { Text("“${doc.name}” (${paginas(doc.pages.size)}) será apagado deste celular.") },
        confirmButton = {
            TextButton(onClick = onConfirmar) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onCancelar) { Text("Cancelar") } },
    )
}

@Composable
fun DialogoTexto(
    texto: TextoExtraido,
    onCopiar: () -> Unit,
    onCompartilhar: () -> Unit,
    onFechar: () -> Unit,
) {
    val vazio = texto.texto.isBlank()
    AlertDialog(
        onDismissRequest = onFechar,
        title = { Text(texto.titulo) },
        text = {
            if (vazio) {
                Text("Nenhum texto foi encontrado. Tente escanear com mais luz e o documento reto.")
            } else {
                SelectionContainer {
                    Text(
                        texto.texto,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            if (vazio) {
                TextButton(onClick = onFechar) { Text("Fechar") }
            } else {
                TextButton(onClick = onCopiar) { Text("Copiar") }
            }
        },
        dismissButton = {
            if (!vazio) {
                Row {
                    TextButton(onClick = onCompartilhar) { Text("Compartilhar") }
                    TextButton(onClick = onFechar) { Text("Fechar") }
                }
            }
        },
    )
}

@Composable
fun DialogoAguarde(mensagem: String) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(18.dp))
                Text(mensagem, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
