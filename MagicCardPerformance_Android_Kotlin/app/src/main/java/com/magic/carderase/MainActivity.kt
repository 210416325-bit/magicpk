package com.magic.carderase

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.InputStream
import java.util.Locale

/**
 * 扑克魔术表演主界面
 *
 * 核心流程：
 * 1. 全屏沉浸黑屏，隐藏状态栏与导航栏，防误触防熄屏。
 * 2. 点击【上传扑克图片】从相册选中任意一张牌的照片，保存在内存 pendingCardBitmap 中。
 * 3. 点击【开启语音】启动语音监听（中文普通话 zh-CN）。
 * 4. 表演时任意聊天，只要说出关键词【确定】：
 *    - 底层立即载入该扑克牌；
 *    - 顶层覆盖全黑蒙版；
 *    - 手机提供隐蔽掌心微震动反馈；
 *    - 观众或表演者可手指在全黑屏幕上滑动涂抹，平滑擦出底下的扑克牌！
 * 5. 支持随时更换新牌，再次说【确定】即重置全黑并载入新牌。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var eraseView: EraseView
    private lateinit var btnVoice: TextView
    private lateinit var btnUpload: TextView
    private lateinit var indicatorReady: View

    // 内存中保存的待展示扑克牌 Bitmap
    private var pendingCardBitmap: Bitmap? = null

    // 语音识别组件
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningVoice = false

    // 震动服务：用于暗号触发时向表演者掌心传递隐蔽触觉反馈
    private var vibrator: Vibrator? = null

    // 系统相册选择图片回调
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            handleImageSelected(uri)
        }
    }

    // 麦克风录音权限请求回调
    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startSpeechListening()
        } else {
            Toast.makeText(this, R.string.msg_permission_mic_needed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 设置极简沉浸式全屏与防息屏
        setupFullScreen()

        setContentView(R.layout.activity_main)

        eraseView = findViewById(R.id.eraseView)
        btnVoice = findViewById(R.id.btnVoice)
        btnUpload = findViewById(R.id.btnUpload)
        indicatorReady = findViewById(R.id.indicatorReady)

        initVibrator()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    /**
     * 配置全屏沉浸、异形屏全面覆盖与防熄屏
     */
    private fun setupFullScreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Android P+ 刘海屏/挖孔屏延伸到全黑边缘
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
    }

    private fun hideSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun setupListeners() {
        // 【上传扑克图片】按钮事件
        btnUpload.setOnClickListener {
            // 打开系统相册选择图片
            pickImageLauncher.launch("image/*")
        }

        // 【开启语音】按钮事件
        btnVoice.setOnClickListener {
            if (isListeningVoice) {
                stopSpeechListening()
            } else {
                checkAndStartSpeech()
            }
        }
    }

    /**
     * 处理从系统相册选择后的扑克牌图片
     */
    private fun handleImageSelected(uri: Uri) {
        try {
            val bitmap = decodeSampledBitmapFromUri(uri, 1200, 1800)
            if (bitmap != null) {
                pendingCardBitmap = bitmap
                Toast.makeText(this, R.string.msg_card_ready, Toast.LENGTH_SHORT).show()

                // 魔术师端微弱提示：左上角极低透明度微绿闪烁一次
                flashReadyIndicator()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "图片解析失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 将图片按屏幕适合的分辨率采样解码，防止大图 OOM
     */
    private fun decodeSampledBitmapFromUri(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                if (width > reqWidth || height > reqHeight) {
                    val sample = calculateInSampleSize(width, height, reqWidth, reqHeight)
                    decoder.setTargetSampleSize(sample)
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            var stream: InputStream? = contentResolver.openInputStream(uri)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(stream, null, options)
            stream?.close()

            options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, reqWidth, reqHeight)
            options.inJustDecodeBounds = false

            stream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(stream, null, options)
            stream?.close()
            bitmap
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * 检查录音权限并启动语音识别
     */
    private fun checkAndStartSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, R.string.msg_speech_not_available, Toast.LENGTH_LONG).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startSpeechListening()
        }
    }

    /**
     * 启动系统 SpeechRecognizer，设定中文普通话识别
     */
    private fun startSpeechListening() {
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(createSpeechListener())
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListeningVoice = true
            btnVoice.setText(R.string.btn_voice_listening)
            btnVoice.setBackgroundResource(R.drawable.bg_magic_button_active)
            btnVoice.setTextColor(ContextCompat.getColor(this, R.color.magic_text_highlight))
        } catch (e: Exception) {
            e.printStackTrace()
            isListeningVoice = false
            updateVoiceBtnIdle()
        }
    }

    /**
     * 停止语音识别
     */
    private fun stopSpeechListening() {
        isListeningVoice = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        updateVoiceBtnIdle()
    }

    private fun updateVoiceBtnIdle() {
        btnVoice.setText(R.string.btn_voice_start)
        btnVoice.setBackgroundResource(R.drawable.bg_magic_button)
        btnVoice.setTextColor(ContextCompat.getColor(this, R.color.magic_text_light))
    }

    /**
     * 构建语音识别监听器
     */
    private fun createSpeechListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                // 如果是静音超时且仍在开启状态，自动平滑重启监听，避免表演中断
                if (isListeningVoice) {
                    runOnUiThread {
                        if (isListeningVoice) {
                            try {
                                speechRecognizer?.cancel()
                                startSpeechListening()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                handleSpeechMatches(results)
                // 结果回调后，若仍处于开启语音状态，继续持续保持监听
                if (isListeningVoice) {
                    runOnUiThread {
                        if (isListeningVoice) {
                            startSpeechListening()
                        }
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // 部分实时识别结果中直接检测【确定】，响应更神速
                handleSpeechMatches(partialResults)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    /**
     * 解析语音识别文字，检测魔术触发暗语【确定】
     */
    private fun handleSpeechMatches(bundle: Bundle?) {
        if (bundle == null) return
        val matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return

        for (text in matches) {
            if (text.contains("确定")) {
                runOnUiThread {
                    triggerMagicReveal()
                }
                break
            }
        }
    }

    /**
     * 核心魔术触发逻辑：
     * 1. 将刚刚上传的扑克牌加载到底层；
     * 2. 顶层黑色蒙版重置为全黑；
     * 3. 给掌心隐蔽震动反馈，通知魔术师“牌已就位，可以开始擦拭”；
     * 4. 观众或表演者可手指在屏幕上涂抹，平滑擦出底牌。
     */
    private fun triggerMagicReveal() {
        val targetCard = pendingCardBitmap
        if (targetCard != null) {
            // 装载到底层，并将蒙版全黑覆盖
            eraseView.setCardBitmap(targetCard)

            // 隐蔽轻微触觉反馈（80ms轻震）
            triggerSecretHaptic()

            // 隐蔽视觉提示
            flashReadyIndicator()

            Toast.makeText(this, R.string.msg_magic_activated, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.msg_please_upload_first, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 掌心隐蔽轻微触觉反馈
     */
    private fun triggerSecretHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(80)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 屏幕边缘微弱指示闪烁
     */
    private fun flashReadyIndicator() {
        indicatorReady.alpha = 1f
        indicatorReady.animate().alpha(0f).setDuration(1200).start()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSpeechListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        pendingCardBitmap?.recycle()
        pendingCardBitmap = null
    }
}