package com.example.plamoscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    private var scannedId by mutableStateOf("未スキャン")
    private var resultTitle by mutableStateOf("")
    private var statusMessage by mutableStateOf("タグをかざすか、QRを映してください")
    
    // エフェクト用ステート
    private var isFlashing by mutableStateOf(false)
    private var isLocked by mutableStateOf(false)

    // 「箱にしまう」の一時保持用
    private var selectedHakoPageId: String? = null
    private var selectedHakoUid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // シャッター音の準備
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
                    MainScreen(
                        mode = currentMode,
                        id = scannedId,
                        title = resultTitle,
                        status = statusMessage,
                        hasCameraPermission = hasCameraPermission,
                        isFlashing = isFlashing,
                        onIdDetected = { id -> onIdDetected(id) },
                        onModeChange = { 
                            currentMode = it
                            scannedId = "未スキャン"
                            resultTitle = ""
                            statusMessage = when(it) {
                                ScanMode.HUKURO_SCAN -> "袋をスキャンしてください"
                                ScanMode.HAKO_SCAN -> "箱をスキャンしてください"
                                ScanMode.SHIMAU_STEP1_HAKO -> "【1/2】箱をスキャンしてください"
                                ScanMode.SHIMAU_STEP2_HUKURO -> "【2/2】袋をスキャンしてください"
                            }
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

    // NFC検出時
    override fun onTagDiscovered(tag: Tag?) {
        val idBytes = tag?.id ?: return
        val uid = idBytes.joinToString(":") { "%02X".format(it) }
        onIdDetected(uid)
    }

    // ID検出時の共通エントリ（NFC/QR共通）
    private fun onIdDetected(id: String) {
        if (isLocked) return // インターバル中は無視

        lifecycleScope.launch(Dispatchers.Main) {
            // 1. 手応え演出（音と視覚）
            isLocked = true
            isFlashing = true
            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
            
            scannedId = id
            statusMessage = "処理中..."
            
            // 2. フラッシュを少し見せてから消す
            delay(100)
            isFlashing = false
            
            // 3. メイン処理を実行
            withContext(Dispatchers.IO) {
                when (currentMode) {
                    ScanMode.HUKURO_SCAN -> processHukuro(id)
                    ScanMode.HAKO_SCAN -> processHako(id)
                    ScanMode.SHIMAU_STEP1_HAKO -> processShimauStep1(id)
                    ScanMode.SHIMAU_STEP2_HUKURO -> processShimauStep2(id)
                }
            }

            // 4. インターバル
            delay(400)
            isLocked = false
        }
    }

    private suspend fun getOrCreateHukuro(id: String): NotionPage {
        return repository.findOrCreatePage(
            databaseId = SecretConfig.DATABASE_ID_HUKURO,
            pkColumnName = "袋ID",
            uid = id,
            defaultNameColumn = "商品名",
            defaultNameValue = "新規登録パーツ"
        )
    }

    private suspend fun getOrCreateHako(id: String): NotionPage {
        return repository.findOrCreatePage(
            databaseId = SecretConfig.DATABASE_ID_HAKO,
            pkColumnName = "箱ID",
            uid = id,
            defaultNameColumn = "箱名",
            defaultNameValue = "新しい箱"
        )
    }

    private suspend fun processHukuro(id: String) {
        try {
            val page = getOrCreateHukuro(id)
            openNotionPage(page.url)
            updateUI(page.properties["商品名"]?.rich_text?.firstOrNull()?.plain_text ?: id, "袋を開きました")
        } catch (e: Exception) {
            updateUI("エラー", e.localizedMessage ?: "不明なエラー")
        }
    }

    private suspend fun processHako(id: String) {
        try {
            val page = getOrCreateHako(id)
            openNotionPage(page.url)
            updateUI(page.properties["箱名"]?.rich_text?.firstOrNull()?.plain_text ?: id, "箱を開きました")
        } catch (e: Exception) {
            updateUI("エラー", e.localizedMessage ?: "不明なエラー")
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
    isFlashing: Boolean,
    onIdDetected: (String) -> Unit,
    onModeChange: (ScanMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("PlamoScanner", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // --- モード切替ボタン ---
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
                colors = ButtonDefaults.buttonColors(containerColor = if(mode == ScanMode.HUKURO_SCAN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
            ) { Text("袋スキャン") }
            
            Button(
                onClick = { onModeChange(ScanMode.HAKO_SCAN) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = if(mode == ScanMode.HAKO_SCAN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
            ) { Text("箱スキャン") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- スキャン結果表示 ---
        Card(
            modifier = Modifier.fillMaxWidth(), 
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ID: $id", style = MaterialTheme.typography.labelSmall)
                Text(title.ifEmpty { "---" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(status, style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // --- 下半分: QRコードスキャンエリア ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (hasCameraPermission) {
                QrScannerView(onQrCodeDetected = { qrValue ->
                    onIdDetected(qrValue)
                })
                
                // スキャンエリアのガイド枠
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .border(2.dp, Color.White.copy(alpha = 0.5f), shape = MaterialTheme.shapes.medium)
                )

                // 【修正箇所】明示的にスコープを指定しないように修正
                androidx.compose.animation.AnimatedVisibility(
                    visible = isFlashing,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.6f)))
                }

            } else {
                Text("カメラ権限が必要です", color = Color.White)
            }
        }
        
        Text("背面NFCまたはカメラQRでスキャン", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
    }
}
