package com.visor.dashcam.ai.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.visor.dashcam.ai.model.DetectionResult
import com.visor.dashcam.ai.model.ObstacleClass
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections

class Yolo26nDetector(
    private val context: Context,
    private val modelFileName: String = "yolo26n.onnx",
    val inputSize: Int = 320
) {
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var inputName: String = "images"
    private var isInitialized = false
    private var currentNnapiState = false

    // Pre-allocated buffers to avoid per-frame allocations & OOM crashes
    private val floatBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(1 * 3 * inputSize * inputSize * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val floatArray = FloatArray(3 * inputSize * inputSize)
    private val pixelsBuffer = IntArray(inputSize * inputSize)

    // Reusable scaled Bitmap & Canvas to achieve 0-allocation per frame
    private var reusableScaledBitmap: Bitmap? = null
    private var reusableCanvas: Canvas? = null
    private val reusablePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * Lazy background initialization so the Main Thread is never blocked
     * and ForegroundService starts immediately without timeout crashes.
     */
    @Synchronized
    fun ensureInitialized(useNnapi: Boolean = false): Boolean {
        // x86 / x86_64 emulators have broken NNAPI HAL implementations that hang or crash
        val isEmulator = Build.SUPPORTED_ABIS.any { it.contains("x86") }
        val effectiveNnapi = useNnapi && !isEmulator

        if (isInitialized && currentNnapiState == effectiveNnapi && ortSession != null) {
            return true
        }

        // Close previous session if re-initializing (e.g. toggling NNAPI)
        try {
            ortSession?.close()
            ortSession = null
        } catch (_: Throwable) {
        }

        return try {
            val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }
            val modelFile = getOrCopyModelFile(modelFileName)
            if (!modelFile.exists() || modelFile.length() == 0L) {
                isInitialized = false
                return false
            }

            // Try creating session with requested hardware setting, fallback to pure CPU if NNAPI fails
            val session = if (effectiveNnapi) {
                try {
                    val nnapiOpts = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(2)
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                        addNnapi()
                    }
                    env.createSession(modelFile.absolutePath, nnapiOpts)
                } catch (t: Throwable) {
                    // Fallback to safe CPU session if device NNAPI driver rejects ops
                    val cpuOpts = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(2)
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    }
                    env.createSession(modelFile.absolutePath, cpuOpts)
                }
            } else {
                val cpuOpts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                env.createSession(modelFile.absolutePath, cpuOpts)
            }

            ortSession = session
            inputName = session.inputNames.iterator().next()
            currentNnapiState = effectiveNnapi
            isInitialized = true
            true
        } catch (t: Throwable) {
            t.printStackTrace()
            isInitialized = false
            false
        }
    }

    private fun getOrCopyModelFile(filename: String): File {
        val file = File(context.filesDir, filename)
        if (!file.exists() || file.length() == 0L) {
            try {
                context.assets.open(filename).use { inputStream ->
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return file
    }

    /**
     * Runs offline inference on the provided Bitmap.
     */
    @Synchronized
    fun detect(
        bitmap: Bitmap,
        confidenceThreshold: Float = 0.35f,
        useNnapi: Boolean = false
    ): List<DetectionResult> {
        if (!ensureInitialized(useNnapi)) return emptyList()
        val session = ortSession ?: return emptyList()
        val env = ortEnv ?: return emptyList()

        var scaled = reusableScaledBitmap
        if (scaled == null || scaled.isRecycled) {
            scaled = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            reusableScaledBitmap = scaled
            reusableCanvas = Canvas(scaled)
        }
        val canvas = reusableCanvas
        if (canvas != null) {
            val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
            val dstRect = Rect(0, 0, inputSize, inputSize)
            canvas.drawBitmap(bitmap, srcRect, dstRect, reusablePaint)
        }

        val results = mutableListOf<DetectionResult>()

        try {
            scaled.getPixels(pixelsBuffer, 0, inputSize, 0, 0, inputSize, inputSize)

            val planeSize = inputSize * inputSize
            val rOffset = 0
            val gOffset = planeSize
            val bOffset = 2 * planeSize

            val inv255 = 1.0f / 255.0f
            for (i in 0 until planeSize) {
                val color = pixelsBuffer[i]
                floatArray[rOffset + i] = ((color shr 16) and 0xFF) * inv255
                floatArray[gOffset + i] = ((color shr 8) and 0xFF) * inv255
                floatArray[bOffset + i] = (color and 0xFF) * inv255
            }
            floatBuffer.clear()
            floatBuffer.put(floatArray)
            floatBuffer.flip()

            OnnxTensor.createTensor(
                env,
                floatBuffer,
                longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
            ).use { inputTensor ->
                session.run(Collections.singletonMap(inputName, inputTensor)).use { output ->
                    val outputValue = output.get(0)
                    val outputTensor = outputValue as? OnnxTensor ?: return emptyList()
                    val shape = outputTensor.info.shape
                    val rawOutput = outputTensor.floatBuffer ?: return emptyList()

                    if (shape.size == 3 && shape[2] == 6L) {
                        // End-to-End NMS-Free format: [1, num_predictions, 6]
                        val numBoxes = shape[1].toInt()
                        for (i in 0 until numBoxes) {
                            val base = i * 6
                            val score = rawOutput.get(base + 4)
                            if (score >= confidenceThreshold) {
                                val classId = rawOutput.get(base + 5).toInt()
                                val obstacle = ObstacleClass.fromClassId(classId)
                                if (obstacle != ObstacleClass.UNKNOWN) {
                                    val x1 = (rawOutput.get(base + 0) / inputSize).coerceIn(0f, 1f)
                                    val y1 = (rawOutput.get(base + 1) / inputSize).coerceIn(0f, 1f)
                                    val x2 = (rawOutput.get(base + 2) / inputSize).coerceIn(0f, 1f)
                                    val y2 = (rawOutput.get(base + 3) / inputSize).coerceIn(0f, 1f)

                                    if (x2 > x1 && y2 > y1) {
                                        results.add(
                                            DetectionResult(
                                                boundingBox = RectF(x1, y1, x2, y2),
                                                obstacleClass = obstacle,
                                                confidence = score
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    } else if (shape.size == 3 && shape[1] >= 84L) {
                        // Standard YOLO format: [1, 84, num_predictions]
                        val numBoxes = shape[2].toInt()
                        val candidateBoxes = mutableListOf<DetectionResult>()
                        val relevantClassIds = intArrayOf(0, 1, 2, 3, 5, 7)

                        for (i in 0 until numBoxes) {
                            var maxScore = 0f
                            var maxClassId = -1

                            for (cId in relevantClassIds) {
                                val score = rawOutput.get((4 + cId) * numBoxes + i)
                                if (score > maxScore) {
                                    maxScore = score
                                    maxClassId = cId
                                }
                            }

                            if (maxScore >= confidenceThreshold) {
                                val cx = rawOutput.get(0 * numBoxes + i)
                                val cy = rawOutput.get(1 * numBoxes + i)
                                val w = rawOutput.get(2 * numBoxes + i)
                                val h = rawOutput.get(3 * numBoxes + i)

                                val x1 = ((cx - w / 2f) / inputSize).coerceIn(0f, 1f)
                                val y1 = ((cy - h / 2f) / inputSize).coerceIn(0f, 1f)
                                val x2 = ((cx + w / 2f) / inputSize).coerceIn(0f, 1f)
                                val y2 = ((cy + h / 2f) / inputSize).coerceIn(0f, 1f)

                                if (x2 > x1 && y2 > y1) {
                                    candidateBoxes.add(
                                        DetectionResult(
                                            boundingBox = RectF(x1, y1, x2, y2),
                                            obstacleClass = ObstacleClass.fromClassId(maxClassId),
                                            confidence = maxScore
                                        )
                                    )
                                }
                            }
                        }

                        candidateBoxes.sortByDescending { it.confidence }
                        val selected = mutableListOf<DetectionResult>()
                        for (cand in candidateBoxes) {
                            var shouldAdd = true
                            for (sel in selected) {
                                if (cand.obstacleClass == sel.obstacleClass &&
                                    calculateIoU(cand.boundingBox, sel.boundingBox) > 0.45f
                                ) {
                                    shouldAdd = false
                                    break
                                }
                            }
                            if (shouldAdd) {
                                selected.add(cand)
                                if (selected.size >= 30) break
                            }
                        }
                        results.addAll(selected)
                    }
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        return results
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        val interArea = maxOf(0f, interRight - interLeft) * maxOf(0f, interBottom - interTop)
        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()
        val unionArea = areaA + areaB - interArea

        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    fun isReady(): Boolean = isInitialized

    @Synchronized
    fun close() {
        try {
            reusableScaledBitmap?.recycle()
            reusableScaledBitmap = null
            reusableCanvas = null
            ortSession?.close()
            ortSession = null
            isInitialized = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
