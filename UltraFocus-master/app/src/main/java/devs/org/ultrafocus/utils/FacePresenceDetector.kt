package devs.org.ultrafocus.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

/**
 * Runs the front camera at ~2fps and calls back when a face appears or disappears.
 *
 * Usage:
 *   val detector = FacePresenceDetector(ctx, 10_000L, onPresent = { … }, onAbsent = { … })
 *   detector.start()   // call from main thread; binds CameraX
 *   detector.stop()    // tears down camera and unbinds lifecycle
 *
 * The [absentGraceMs] window gives you time to look away briefly (scratch your
 * nose, blink slowly at the camera, etc.) before the absent callback fires.
 * Presence is immediate — the moment a face is detected again, [onFacePresent] fires.
 *
 * All callbacks arrive on the main thread.
 */
class FacePresenceDetector(
    private val context: Context,
    private val absentGraceMs: Long = 10_000L,
    private val onFacePresent: () -> Unit,
    private val onFaceAbsent: () -> Unit
) {
    private val lifecycleOwner = SimpleLifecycleOwner()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var started = false

    // Optimistic start: assume face present so we don't fire onFaceAbsent
    // before the first frame arrives.
    private var facePresent = true
    private var absentFired = false

    private val absentRunnable = Runnable {
        if (!facePresent) {
            absentFired = true
            onFaceAbsent()
        }
    }

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.15f)
            .build()
    )

    // Throttle to ~2fps — face detection doesn't need more than that,
    // and it saves significant battery over running at camera native fps.
    private var lastFrameMs = 0L
    private val frameIntervalMs = 500L

    fun start() {
        if (started) return
        started = true
        mainHandler.post {
            lifecycleOwner.markStarted()
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    cameraProvider = future.get()
                    bindCamera(cameraProvider!!)
                } catch (_: Exception) {}
            }, context.mainExecutor)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        mainHandler.post {
            mainHandler.removeCallbacks(absentRunnable)
            try { cameraProvider?.unbindAll() } catch (_: Exception) {}
            try { lifecycleOwner.markDestroyed() } catch (_: Exception) {}
        }
        analysisExecutor.shutdown()
        detector.close()
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        val selector = try {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } catch (_: Exception) {
            return // no front camera
        }

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(160, 120))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(analysisExecutor) { proxy -> analyzeFrame(proxy) }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, analysis)
        } catch (_: Exception) {}
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeFrame(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastFrameMs < frameIntervalMs) {
            proxy.close()
            return
        }
        lastFrameMs = now

        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener { faces -> onFrameResult(faces.isNotEmpty()) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun onFrameResult(detected: Boolean) {
        mainHandler.post {
            if (detected) {
                mainHandler.removeCallbacks(absentRunnable)
                if (!facePresent || absentFired) {
                    facePresent = true
                    absentFired = false
                    onFacePresent()
                } else {
                    facePresent = true
                }
            } else {
                if (facePresent) {
                    facePresent = false
                    mainHandler.removeCallbacks(absentRunnable)
                    mainHandler.postDelayed(absentRunnable, absentGraceMs)
                }
                // else: already absent, timer already running
            }
        }
    }
}
