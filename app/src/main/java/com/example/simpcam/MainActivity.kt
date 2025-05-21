package com.example.simpcam

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class MainActivity : AppCompatActivity() {
    private lateinit var textureView: TextureView
    private lateinit var btnTakePhoto: Button
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private lateinit var cameraId: String
    private val cameraManager by lazy { getSystemService(CAMERA_SERVICE) as CameraManager }
    private val REQUEST_PERMISSIONS = 10
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA
    )
    private val captureResultQueue: ConcurrentLinkedQueue<CaptureResult> = ConcurrentLinkedQueue()
    private val rawImageSaveDispatcher = Dispatchers.IO.limitedParallelism(4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        textureView = findViewById(R.id.textureView)
        btnTakePhoto = findViewById(R.id.btnTakePhoto)

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS)
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }

        btnTakePhoto.setOnClickListener {
            takeRawPhoto()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && allPermissionsGranted()) {
            textureView.surfaceTextureListener = surfaceTextureListener
        } else {
            Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
    }

    private fun openCamera() {
        try {
            cameraId = cameraManager.cameraIdList.first { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
            }
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
            val rawSize = rawSizes?.firstOrNull() ?: Size(640, 480)
            imageReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage()
                val result = captureResultQueue.poll()
                if (result != null) {
                    saveRawImage(image, result)
                } else {
                    image.close()
                }
            }, null)
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraPreviewSession()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, null)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to open camera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createCameraPreviewSession() {
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(textureView.width, textureView.height)
        val surface = Surface(texture)
        val previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewRequestBuilder.addTarget(surface)
        cameraDevice?.createCaptureSession(listOf(surface, imageReader!!.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                session.setRepeatingRequest(previewRequestBuilder.build(), null, null)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Toast.makeText(this@MainActivity, "Preview failed", Toast.LENGTH_SHORT).show()
            }
        }, null)
    }

    private fun takeRawPhoto() {
        val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureRequestBuilder.addTarget(imageReader!!.surface)
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        captureSession?.capture(captureRequestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                captureResultQueue.add(result)
            }
        }, null)
    }

    private fun saveRawImage(image: Image, result: CaptureResult) {
        lifecycleScope.launch(rawImageSaveDispatcher) {
            try {
                val dngCreator = DngCreator(cameraManager.getCameraCharacteristics(cameraId), result)
                // Always rotate 90 degrees clockwise
                val filename = "RAW_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.dng"
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Simpcam")
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it)?.use { outputStream ->
                        dngCreator.writeImage(outputStream, image)
                    }
                    // Notify media scanner
                    MediaScannerConnection.scanFile(this@MainActivity, arrayOf(uri.toString()), null, null)
                }
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "RAW photo saved", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to save RAW: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                image.close()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraDevice?.close()
        imageReader?.close()
        captureSession?.close()
    }
}
