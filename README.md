# Scanner

App Android para escanear documentos com a câmera do celular, no estilo dos apps de scanner de documentos:

- **Escanear** com detecção automática das bordas da folha, cantos ajustáveis (com lupa), correção de
  perspectiva e filtros (Original, Realçar, Cinza, Preto e branco). Também importa fotos da galeria.
  O app tem scanner próprio, que funciona em qualquer celular; se o celular tiver o scanner de documentos
  do Google (Play Services), ele é usado no lugar.
- **Vários documentos e várias páginas**: lista com miniaturas, busca pelo nome, renomear, excluir.
- **Páginas**: ver em tela cheia com zoom, girar, mudar a ordem, excluir, adicionar mais páginas.
- **PDF**: compartilhar (WhatsApp, e-mail…) ou salvar no celular / Google Drive.
  O PDF usa as imagens originais, sem perder qualidade.
- **Extrair texto (OCR)** de uma página ou do documento inteiro, sem internet; dá para copiar ou compartilhar.
- Compartilhar as páginas como imagens JPG.

Os documentos ficam guardados só no celular, dentro do app.

## Como baixar e instalar no celular

O GitHub monta o APK sozinho a cada alteração no código (aba **Actions**, fluxo *Gerar APK*).

1. No celular, abra a página **Releases** deste repositório (logado no GitHub) e baixe o arquivo
   **Scanner.apk** da versão mais recente.
2. Abra o arquivo baixado. Na primeira vez o Android pede para **permitir instalar apps desta fonte**
   (o navegador ou o app de arquivos): permita e volte.
3. Toque em **Instalar**.

Para atualizar, é só baixar e instalar o APK novo por cima: os documentos continuam lá.

### Requisitos

- Android 8.0 ou mais novo.

## Para quem for mexer no código

Projeto Kotlin + Jetpack Compose. Abra a pasta no Android Studio ou compile com:

```bash
./gradlew assembleRelease
```

| O quê | Onde |
|---|---|
| Telas (lista, documento, página com zoom) e diálogos | `app/src/main/java/br/scannerdocs/app/ui/` |
| Regras: OCR, compartilhar, salvar | `ui/MainViewModel.kt`, `ui/App.kt` |
| Scanner próprio: fluxo (câmera/galeria → cantos → filtro) e telas | `scanner/ScannerViewModel.kt`, `ui/TelaScanner.kt` |
| Detecção da folha e filtros (Kotlin puro, testável fora do Android) | `scanner/Detector.kt`, `scanner/Filtro.kt` |
| Armazenamento (pasta por documento + `doc.json`) | `data/DocStore.kt` |
| Geração do PDF (embute o JPEG direto, sem recomprimir) | `pdf/PdfWriter.kt` |
| Compilação automática do APK | `.github/workflows/gerar-apk.yml` |

### Assinatura do APK

O Android só aceita instalar uma versão nova por cima da antiga se as duas tiverem a mesma assinatura.
Por isso o APK é assinado com uma chave fixa que está em `keystore/scanner.jks` (senha `scannerdocs`).
Como o app é instalado direto, sem passar pela Play Store, isso é suficiente.
Se quiser uma chave privada, gere outra e cadastre em *Settings → Secrets and variables → Actions*:
`KEYSTORE_BASE64` (o arquivo .jks em base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`.
Atenção: trocar a chave obriga a desinstalar o app antigo (e os documentos dele) antes de instalar o novo.
