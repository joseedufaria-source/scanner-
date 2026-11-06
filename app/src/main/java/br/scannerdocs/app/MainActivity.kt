package br.scannerdocs.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import br.scannerdocs.app.ui.App
import br.scannerdocs.app.ui.MainViewModel
import br.scannerdocs.app.ui.theme.ScannerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScannerTheme {
                val vm: MainViewModel = viewModel()
                App(vm)
            }
        }
    }
}
