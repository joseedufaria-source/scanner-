package br.scannerdocs.app.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import br.scannerdocs.app.data.DocStore
import br.scannerdocs.app.data.ScanDoc
import br.scannerdocs.app.scanner.ScannerViewModel
import br.scannerdocs.app.scanner.ScannerViewModel.Pedido
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File

@Composable
fun App(vm: MainViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }

    var renomeando by remember { mutableStateOf<ScanDoc?>(null) }
    var excluindo by remember { mutableStateOf<ScanDoc?>(null) }
    var salvarId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.avisos.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) {
        vm.envios.collect { intent ->
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                vm.avisar("Nenhum app encontrado para compartilhar")
            }
        }
    }

    // ----- scanner próprio (funciona sem o Google Play Services) -----
    val scanner: ScannerViewModel = viewModel()
    var fotoPendente by rememberSaveable { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { _ ->
        // Alguns apps de câmera devolvem "cancelado" mesmo tendo salvo a foto: vale o arquivo.
        val foto = fotoPendente?.let { File(it) }
        fotoPendente = null
        if (foto != null && foto.isFile && foto.length() > 0) {
            scanner.fotoTirada(foto)
        } else {
            foto?.delete()
            scanner.capturaCancelada()
        }
    }

    fun abrirCamera() {
        val foto = scanner.novoArquivoFoto()
        fotoPendente = foto.path
        try {
            cameraLauncher.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", foto))
        } catch (e: ActivityNotFoundException) {
            fotoPendente = null
            scanner.avisar("Nenhum app de câmera encontrado")
            scanner.capturaCancelada()
        }
    }

    val permissaoCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            abrirCamera()
        } else {
            scanner.avisar("Sem permissão para usar a câmera")
            scanner.capturaCancelada()
        }
    }

    val galeriaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isEmpty()) scanner.capturaCancelada() else scanner.importar(uris)
    }

    fun abrirGaleria() {
        try {
            galeriaLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: ActivityNotFoundException) {
            scanner.avisar("Nenhum app de galeria encontrado")
            scanner.capturaCancelada()
        }
    }

    LaunchedEffect(Unit) {
        scanner.pedidos.collect { pedido ->
            when (pedido) {
                Pedido.CAMERA -> if (precisaPermissaoCamera(context)) permissaoCamera.launch(Manifest.permission.CAMERA) else abrirCamera()
                Pedido.GALERIA -> abrirGaleria()
            }
        }
    }
    LaunchedEffect(Unit) { scanner.avisos.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { scanner.concluidos.collect { vm.salvarEscaneadas(it.anexarEm, it.arquivos) } }

    // ----- scanner do Google (usado quando o celular tem; senão, o próprio) -----
    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        val uris = if (res.resultCode == Activity.RESULT_OK) {
            GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pages?.map { it.imageUri }.orEmpty()
        } else {
            emptyList()
        }
        vm.aoEscanear(uris)
    }
    val escanear: (String?) -> Unit = escanear@{ anexarEm ->
        if (vm.scannerGoogleIndisponivel) {
            scanner.iniciar(anexarEm, Pedido.CAMERA)
            return@escanear
        }
        vm.prepararScan(anexarEm)
        val opcoes = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opcoes)
            .getStartScanIntent(context.findActivity())
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener {
                // Este celular não tem o scanner do Google: usa o scanner do próprio app.
                vm.prepararScan(null)
                vm.scannerGoogleIndisponivel = true
                scanner.iniciar(anexarEm, Pedido.CAMERA)
            }
    }
    val importar: (String?) -> Unit = { anexarEm -> scanner.iniciar(anexarEm, Pedido.GALERIA) }

    // ----- salvar PDF numa pasta escolhida (Downloads, Drive…) -----
    val salvarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val id = salvarId
        salvarId = null
        if (uri != null && id != null) vm.salvarPdf(id, uri)
    }
    val salvar: (ScanDoc) -> Unit = { doc ->
        salvarId = doc.id
        try {
            salvarLauncher.launch(DocStore.fileName(doc.name) + ".pdf")
        } catch (e: ActivityNotFoundException) {
            vm.avisar("Este celular não tem o seletor de arquivos")
        }
    }

    // ----- telas -----
    BackHandler(enabled = !scanner.ativo && vm.tela !is Tela.Inicio) { vm.voltar() }

    if (scanner.ativo) {
        TelaScanner(scanner, snackbar)
    } else when (val t = vm.tela) {
        Tela.Inicio -> TelaInicio(
            docs = vm.docs,
            carregado = vm.carregado,
            snackbar = snackbar,
            onEscanear = { escanear(null) },
            onImportar = { importar(null) },
            onAbrir = { vm.abrirDocumento(it.id) },
            onCompartilhar = { vm.compartilharPdf(it.id) },
            onSalvar = salvar,
            onRenomear = { renomeando = it },
            onExcluir = { excluindo = it },
        )

        is Tela.Documento -> vm.doc(t.id)?.let { doc ->
            TelaDocumento(
                doc = doc,
                snackbar = snackbar,
                onVoltar = vm::voltar,
                onAbrirPagina = { vm.abrirPagina(doc.id, it) },
                onAdicionar = { escanear(doc.id) },
                onAdicionarDaGaleria = { importar(doc.id) },
                onCompartilhar = { vm.compartilharPdf(doc.id) },
                onCompartilharImagens = { vm.compartilharImagens(doc.id) },
                onSalvar = { salvar(doc) },
                onTexto = { vm.extrairTexto(doc.id) },
                onRenomear = { renomeando = doc },
                onExcluir = { excluindo = doc },
            )
        }

        is Tela.Pagina -> vm.doc(t.id)?.let { doc ->
            TelaPagina(
                doc = doc,
                indice = t.indice,
                snackbar = snackbar,
                onVoltar = vm::voltar,
                onMudarPagina = vm::mudarPagina,
                onGirar = { vm.girarPagina(doc.id, it) },
                onTexto = { vm.extrairTexto(doc.id, it) },
                onMover = { de, para -> vm.moverPagina(doc.id, de, para) },
                onExcluir = { vm.excluirPagina(doc.id, it) },
            )
        }
    }

    // ----- diálogos -----
    renomeando?.let { doc ->
        DialogoRenomear(
            atual = doc.name,
            onConfirmar = { nome ->
                renomeando = null
                vm.renomear(doc.id, nome)
            },
            onCancelar = { renomeando = null },
        )
    }

    excluindo?.let { doc ->
        DialogoExcluir(
            doc = doc,
            onConfirmar = {
                excluindo = null
                vm.excluir(doc.id)
            },
            onCancelar = { excluindo = null },
        )
    }

    vm.textoExtraido?.let { t ->
        DialogoTexto(
            texto = t,
            onCopiar = {
                clipboard.setText(AnnotatedString(t.texto))
                vm.fecharTexto()
                vm.avisar("Texto copiado")
            },
            onCompartilhar = {
                val intent = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, t.texto)
                try {
                    context.startActivity(Intent.createChooser(intent, "Compartilhar texto"))
                } catch (e: ActivityNotFoundException) {
                    vm.avisar("Nenhum app encontrado para compartilhar")
                }
            },
            onFechar = vm::fecharTexto,
        )
    }

    vm.ocupado?.let { DialogoAguarde(it) }
}

/** Só precisa pedir a permissão da câmera se alguma biblioteca a declarou no manifesto. */
private fun precisaPermissaoCamera(context: Context): Boolean {
    val declarada = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.CAMERA) == true
    } catch (e: Exception) {
        false
    }
    return declarada &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
}
