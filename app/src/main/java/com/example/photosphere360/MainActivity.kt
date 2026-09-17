package com.example.photosphere360

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var capture: Button
    private lateinit var make: Button

    private var imageCapture: ImageCapture? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val shots = mutableListOf<File>()
    private val totalShots = 24

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else toast("카메라 권한이 필요합니다.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        count = findViewById(R.id.count)
        capture = findViewById(R.id.captureButton)
        make = findViewById(R.id.makeButton)

        if (!OpenCVLoader.initLocal()) {
            toast("OpenCV 초기화 실패")
        }

        capture.setOnClickListener { takePhoto() }
        make.setOnClickListener { makePanorama() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val previewUseCase = Preview.Builder().build().also {
                it.setSurfaceProvider(preview.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                previewUseCase,
                imageCapture
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val captureUseCase = imageCapture ?: return
        if (shots.size >= totalShots) {
            status.text = "촬영 완료 — 360° 만들기를 눌러주세요"
            make.visibility = View.VISIBLE
            return
        }

        val dir = File(cacheDir, "sphere_shots").apply { mkdirs() }
        val file = File(dir, "shot_${System.currentTimeMillis()}.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(file).build()

        captureUseCase.takePicture(
            output,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    shots.add(file)
                    runOnUiThread {
                        count.text = "${shots.size} / $totalShots"
                        if (shots.size == totalShots) {
                            status.text = "촬영 완료 — 360° 만들기를 눌러주세요"
                            make.visibility = View.VISIBLE
                            capture.isEnabled = false
                        } else {
                            status.text = "다음 방향으로 천천히 돌린 뒤 촬영"
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread { toast("촬영 실패: ${exception.message}") }
                }
            }
        )
    }

    private fun makePanorama() {
        if (shots.size < 4) {
            toast("사진을 최소 4장 이상 촬영하세요.")
            return
        }

        capture.isEnabled = false
        make.isEnabled = false
        status.text = "360° 이미지 만드는 중..."

        executor.execute {
            try {
                val output = PanoramaStitcher.stitch(shots)
                runOnUiThread {
                    saveToGallery(output)
                    status.text = "완료! 갤러리에 360° 사진 저장됨"
                    make.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "합치기 실패"
                    make.isEnabled = true
                    toast("스티칭 실패: ${e.message}")
                }
            }
        }
    }

    private fun saveToGallery(file: File) {
        val name = "PhotoSphere360_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(System.currentTimeMillis()) + ".jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/PhotoSphere360"
            )
        }

        contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        )?.let { uri ->
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
