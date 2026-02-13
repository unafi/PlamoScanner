package com.example.plamoscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaActionSound
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.plamoscanner.ui.theme.PlamoScannerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ScanMode {
    HUKURO_SCAN, // 袋スキャン・登録
    HAKO_SCAN,   // 箱スキャン・登録
    SHIMAU_STEP1_HAKO, // 箱にしまう（ステップ1：箱スキャン）
    SHIMAU_STEP2_HUKURO // 箱にしまう（ステップ2：袋スキャン）
}

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private val repository = NotionRepository()
    private val shutterSound = MediaActionSound()

    // UI表示用ステート
    private var currentMode by mutableStateOf(ScanMode.HUKURO_SCAN)
    private var scannedId by mutableStateOf("-")
    private var resultTitle by mutableStateOf("")
    private var statusMessage by mutableStateOf("ボタンを押してスキャン開始")
    
    // スキャン状態管理
    private var isScanningActive by mutableStateOf(false)
    private var isFlashing by mutableStateOf(false)
    private var isLocked by mutableStateOf(false)

    // 「箱にしまう」の一時保持用
    private var selectedHakoPageId: String? = null
    private var selectedHakoUid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC非対応端末です", Toast.LENGTH_LONG).show()
        }

        setContent {
            PlamoScannerTheme {
                val context = LocalContext.current
                var hasCameraPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    )
                }
                val launcher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { granted -> hasCameraPermission = granted }
                )

                LaunchedEffect(Unit) {
                    if (!hasCameraPermission) {
                        launcher.launch(Manifest.permission.CAMERA)
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // プレビュー用画像ステート
                    var capturedImage by remember { mutableStateOf<Bitmap?>(null) }

                    MainScreen(
                        mode = currentMode,
                        id = scannedId,
                        title = resultTitle,
                        status = statusMessage,
                        hasCameraPermission = hasCameraPermission,
                        isScanningActive = isScanningActive,
                        isFlashing = isFlashing,
                        capturedBitmap = capturedImage,
                        onIdDetected = { id -> onIdDetected(id) },
                        onModeChange = { 
                            currentMode = it
                            scannedId = "-"
                            resultTitle = ""
                            isScanningActive = true // ボタン押下でスキャン開始
                            capturedImage = null    // 画像クリア
                            statusMessage = when(it) {
                                ScanMode.HUKURO_SCAN -> "袋をスキャンしてください"
                                ScanMode.HAKO_SCAN -> "箱をスキャンしてください"
                                ScanMode.SHIMAU_STEP1_HAKO -> "【1/2】箱をスキャンしてください"
                                ScanMode.SHIMAU_STEP2_HUKURO -> "【2/2】袋をスキャンしてください"
                            }
                        },
                        onCapture = {
                            // Step 2で実装: 撮影処理
                            Toast.makeText(context, "撮影ボタン (未実装)", Toast.LENGTH_SHORT).show()
                        },
                        onCancel = {
                            // 中止処理: スキャン停止 & リセット
                            isScanningActive = false
                            capturedImage = null
                            statusMessage = "ボタンを押してスキャン開始"
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(this, this,
            NfcAdapter.FLAG_READER_NFC_A or 
            NfcAdapter.FLAG_READER_NFC_F or 
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        shutterSound.release()
    }

    override fun onTagDiscovered(tag: Tag?) {
        val idBytes = tag?.id ?: return
        val uid = idBytes.joinToString(":") { "%02X".format(it) }
        onIdDetected(uid)
    }

    private fun onIdDetected(id: String) {
        if (!isScanningActive || isLocked) return // 非アクティブ時、ロック中は無視

        lifecycleScope.launch(Dispatchers.Main) {
            isLocked = true
            isFlashing = true
            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
            
            scannedId = id
            statusMessage = "処理中..."
            
            delay(100)
            isFlashing = false
            
            withContext(Dispatchers.IO) {
                when (currentMode) {
                    ScanMode.HUKURO_SCAN -> processHukuro(id)
                    ScanMode.HAKO_SCAN -> processHako(id)
                    ScanMode.SHIMAU_STEP1_HAKO -> processShimauStep1(id)
                    ScanMode.SHIMAU_STEP2_HUKURO -> processShimauStep2(id)
                }
            }

            delay(400)
            isLocked = false
        }
    }

    private suspend fun getOrCreateHukuro(id: String): NotionPage {
        return repository.findOrCreatePage(SecretConfig.DATABASE_ID_HUKURO, "袋ID", id, "商品名", "新規登録パーツ")
    }

    private suspend fun getOrCreateHako(id: String): NotionPage {
        return repository.findOrCreatePage(SecretConfig.DATABASE_ID_HAKO, "箱ID", id, "箱名", "新しい箱")
    }

    private suspend fun processHukuro(id: String) {
        try {
            val page = getOrCreateHukuro(id)
            openNotionPage(page.url)
            updateUI(page.properties["商品名"]?.rich_text?.firstOrNull()?.plain_text ?: id, "袋を開きました")
        } catch (e: Exception) {
            updateUI("エラー", e.localizedMessage ?: "不明なエラー")
        } finally {
            withContext(Dispatchers.Main) { isScanningActive = false } // 処理完了でスキャン停止
        }
    }

    private suspend fun processHako(id: String) {
        try {
            val page = getOrCreateHako(id)
            openNotionPage(page.url)
            updateUI(page.properties["箱名"]?.rich_text?.firstOrNull()?.plain_text ?: id, "箱を開きました")
        } catch (e: Exception) {
            updateUI("エラー", e.localizedMessage ?: "不明なエラー")
        } finally {
            withContext(Dispatchers.Main) { isScanningActive = false } // 処理完了でスキャン停止
        }
    }

    private suspend fun processShimauStep1(id: String) {
        try {
            val hakoPage = getOrCreateHako(id)
            selectedHakoPageId = hakoPage.id
            selectedHakoUid = id
            withContext(Dispatchers.Main) {
                currentMode = ScanMode.SHIMAU_STEP2_HUKURO
                statusMessage = "箱「${hakoPage.properties["箱名"]?.rich_text?.firstOrNull()?.plain_text ?: id}」を選択中。\n次に袋をスキャンしてください。"
            }
        } catch (e: Exception) {
            updateUI("エラー", e.localizedMessage ?: "不明なエラー")
            withContext(Dispatchers.Main) { isScanningActive = false } // エラー時もスキャン停止
        }
    }

    private suspend fun processShimauStep2(id: String) {
        val hakoId = selectedHakoPageId ?: return
        try {
            val hukuroPage = getOrCreateHukuro(id)
            repository.updateHukuroLocation(hukuroPage.id, hakoId)
            updateUI("完了", "袋を箱に紐付けました！")
            val finalHakoPage = repository.getPage(SecretConfig.DATABASE_ID_HAKO, "箱ID", selectedHakoUid!!)
            finalHakoPage?.let { openNotionPage(it.url) }
            withContext(Dispatchers.Main) {
                currentMode = ScanMode.HUKURO_SCAN
            }
        } catch (e: Exception) {
            updateUI("エラー", e.localizedMessage ?: "不明なエラー")
        } finally {
             withContext(Dispatchers.Main) { isScanningActive = false } // 処理完了でスキャン停止
        }
    }

    private fun openNotionPage(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Notionアプリを開けませんでした", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun updateUI(title: String, status: String) {
        withContext(Dispatchers.Main) {
            resultTitle = title
            statusMessage = status
        }
    }
}

@Composable
fun MainScreen(
    mode: ScanMode,
    id: String,
    title: String,
    status: String,
    hasCameraPermission: Boolean,
    isScanningActive: Boolean,
    isFlashing: Boolean,
    capturedBitmap: Bitmap?, // プレビュー用画像
    onIdDetected: (String) -> Unit,
    onModeChange: (ScanMode) -> Unit,
    onCapture: () -> Unit, // 撮影ボタン動作
    onCancel: () -> Unit,  // 中止ボタン動作
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("PlamoScanner", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Button(
            onClick = { onModeChange(ScanMode.SHIMAU_STEP1_HAKO) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if(mode == ScanMode.SHIMAU_STEP1_HAKO || mode == ScanMode.SHIMAU_STEP2_HUKURO) 
                    MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
            )
        ) { Text("箱にしまう", fontSize = 18.sp) }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onModeChange(ScanMode.HUKURO_SCAN) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = if(mode == ScanMode.HUKURO_SCAN && isScanningActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
            ) { Text("袋スキャン") }
            
            Button(
                onClick = { onModeChange(ScanMode.HAKO_SCAN) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = if(mode == ScanMode.HAKO_SCAN && isScanningActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
            ) { Text("箱スキャン") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- スキャン結果表示 ---
        Card(
            modifier = Modifier.fillMaxWidth().height(120.dp), // 高さを固定
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp).fillMaxSize(), 
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceAround // 均等配置
            ) {
                Text(status, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text("ID: $id", style = MaterialTheme.typography.labelSmall)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // --- 下半分: QRコードスキャンエリア & カメラコントロール ---
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (hasCameraPermission) {
                if (isScanningActive) {
                    // カメラプレビュー
                    QrScannerView(onQrCodeDetected = { qrValue -> onIdDetected(qrValue) })
                    
                    // QR読み取りガイド枠
                    Box(modifier = Modifier.size(200.dp).border(2.dp, Color.White.copy(alpha = 0.5f), shape = MaterialTheme.shapes.medium))

                    // フラッシュエフェクト
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isFlashing,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.6f)))
                    }

                    // --- カメラコントロール UI (オーバーレイ) ---
                    Column(
                        modifier = Modifier.fillMaxSize().padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // プレビュー画像があれば表示
                        if (capturedBitmap != null) {
                            Text("保存候補:", color = Color.White, fontSize = 14.sp)
                            Image(
                                bitmap = capturedBitmap.asImageBitmap(),
                                contentDescription = "Preview",
                                modifier = Modifier.size(150.dp).padding(8.dp).border(2.dp, Color.White)
                            )
                        }

                        // ボタンエリア
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 撮影ボタン
                            Button(
                                onClick = onCapture,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                            ) {
                                Text("📷 撮影", color = Color.Black)
                            }
                            // 中止ボタン
                            Button(
                                onClick = onCancel,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                            ) {
                                Text("中止", color = Color.Black)
                            }
                        }
                    }

                } else {
                    Text("ボタンを押してスキャン開始", color = Color.White)
                }
            } else {
                Text("カメラ権限が必要です", color = Color.White)
            }
        }
    }
}
