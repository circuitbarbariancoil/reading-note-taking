package com.readingnotes.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.readingnotes.app.capture.toJpegBytes
import com.readingnotes.app.model.Page
import com.readingnotes.app.repository.BookRepository
import com.readingnotes.app.repository.CaptureResult
import com.readingnotes.app.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
fun CaptureScreen(
    settings: AppSettings,
    repository: BookRepository,
    lastResult: CaptureResult?,
    onCaptureResult: (CaptureResult) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    if (!settings.hasGeminiKey || !settings.hasDropboxCredential) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader("拍照采集", onBack)
            Text("请先到设置中填写 Gemini API Key 并连接 Dropbox。")
            Button(onClick = onOpenSettings) {
                Text("去设置")
            }
        }
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var processing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("准备就绪") }
    var vertical by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        statusText = if (granted) "相机权限已授予" else "需要相机权限"
    }

    LaunchedEffect(permissionGranted, previewView) {
        val view = previewView
        if (!permissionGranted || view == null) return@LaunchedEffect
        val provider = context.awaitCameraProvider()
        provider.unbindAll()
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(view.surfaceProvider)
        }
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageCapture,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("拍照采集", onBack)
        if (!permissionGranted) {
            Text("需要相机权限才能开始采集。")
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("授予相机权限")
            }
        } else {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        previewView = this
                    }
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        vertical = !vertical
                    },
                    enabled = lastResult != null,
                ) {
                    Text(if (vertical) "竖排" else "横排")
                }
                Button(
                    onClick = {
                        if (processing) return@Button
                        statusText = "开始拍照..."
                        imageCapture.takePicture(
                            mainExecutor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    scope.launch {
                                        processing = true
                                        try {
                                            statusText = "正在读取图像..."
                                            val jpegBytes = withContext(Dispatchers.Default) {
                                                try {
                                                    image.toJpegBytes()
                                                } finally {
                                                    image.close()
                                                }
                                            }
                                            statusText = "正在 OCR 与同步..."
                                            val result = repository.captureAndSync(
                                                sourceBytes = jpegBytes,
                                                geminiApiKey = settings.geminiApiKey.orEmpty(),
                                                dropboxCredentialJson = settings.dropboxCredentialJson.orEmpty(),
                                                bookTitle = settings.bookTitle,
                                            )
                                            onCaptureResult(result)
                                            statusText = "已写入 ${result.dropboxArchivePath} 和 ${result.dropboxBookJsonPath}"
                                        } catch (t: Throwable) {
                                            statusText = "错误：${t.message ?: t::class.java.simpleName}"
                                        } finally {
                                            processing = false
                                        }
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    statusText = "拍照失败：${exception.message ?: exception.javaClass.simpleName}"
                                }
                            },
                        )
                    },
                    enabled = !processing,
                ) {
                    Text(if (processing) "处理中..." else "快门")
                }
            }

            if (processing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(statusText)

            lastResult?.let { result ->
                Text("OCR 结果")
                ResultWebView(
                    page = result.page,
                    vertical = vertical,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                )
                Text("本地：${result.localBookJsonPath}")
                Text("Dropbox：${result.dropboxBookJsonPath}")
                Text("页面：${result.dropboxArchivePath}")
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ResultWebView(
    page: Page,
    vertical: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
            }
        },
        update = { web ->
            web.loadDataWithBaseURL(
                null,
                PageHtml.render(page, emptyMap<String, String>(), vertical),
                "text/html",
                "utf-8",
                null,
            )
        },
    )
}

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    cont.resume(future.get())
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }
