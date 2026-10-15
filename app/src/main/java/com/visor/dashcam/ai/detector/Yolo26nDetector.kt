package com.visor.dashcam.ai.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import ai.onnxruntime.*
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
    val inputSize: Int = 320, // 320x320 for ultra-lightweight mobile inference (~8-15ms)
    private val confidenceThreshold: Float = 0.35f
) {
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var inputName: String = "images"
    private var isInitialized = false

    init {
        initOrt()
    }

    @Synchronized
    private fun initOrt() {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // Attempt NNAPI acceleration (NPU/DSP) on modern Android
                try {
                    addNnapi()
                } catch (e: Exception) {
                    // Fallback to CPU execution
                }
            }

            val modelFile = getOrCopyModelFile(modelFileName)
            if (modelFile.exists()) {
                ortSession = ortEnv?.createSession(modelFile.absolutePath, sessionOptions)
                inputName = ortSession?.inputNames?.iterator()?.next() ?: "images"
                isInitialized = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isInitialized = false
        }
    }

    private fun getOrCopyModelFile(filename: String): File {
        val file = File(context.filesDir, filename)
        if (!file.exists()) {
            try {
                context.assets.open(filename).use { inputStream ->
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            } catch (e: Exception) {
                // If model asset is not yet placed, we will handle gracefully
            }
        }
        return file
    }

    /**
     * Runs offline inference on the provided Bitmap.
     * Expects ARGB_8888 Bitmap.
     */
    fun detect(bitmap: Bitmap): List<DetectionResult> {
        val session = ortSession ?: return emptyList()
        val env = ortEnv ?: return emptyList()

        // 1. Resize Bitmap to model input size (e.g. 320x320)
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        // 2. Preprocess to FloatBuffer: shape [1, 3, inputSize, inputSize], RGB normalized [0..1]
        val floatBuffer = ByteBuffer.allocateDirect(1 * 3 * inputSize * inputSize * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        // Separate color planes (CHW format)
        val rOffset = 0
        val gOffset = inputSize * inputSize
        val bOffset = 2 * inputSize * inputSize

        for (i in pixels.indices) {
            val color = pixels[i]
            val r = ((color shr 16) and 0xFF) / 255.0f
            val g = ((color shr 8) and 0xFF) / 255.0f
            val b = (color and 0xFF) / 255.0f

            floatBuffer.put(rOffset + i, r)
            floatBuffer.put(gOffset + i, g)
            floatBuffer.put(bOffset + i, b)
        }

        // 3. Create ONNX Tensor
        val tensor = OnnxTensor.createTensor(
            env,
            floatBuffer,
            longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        )

        val results = mutableListOf<DetectionResult>()

        try {
            // 4. Run inference
            val output = session.run(Collections.singletonMap(inputName, tensor))
            val outputTensor = output[0] as? OnnxTensor ?: return emptyList()
            val shape = outputTensor.info.shape // e.g. [1, 300, 6] for YOLO26 NMS-free
            val rawOutput = outputTensor.floatBuffer

            // Parse YOLO26 NMS-Free native end-to-end output:
            // Output shape is typically [1, num_predictions, 6] where 6 values = [x1, y1, x2, y2, score, class_id]
            if (shape.size == 3 && shape[2] == 6L) {
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

                            val rect = RectF(x1, y1, x2, y2)
                            results.add(
                                DetectionResult(
                                    boundingBox = rect,
                                    obstacleClass = obstacle,
                                    confidence = score
                                )
                            )
                        }
                    }
                }
            } else if (shape.size == 3 && shape[1] >= 84L) {
                // Standard YOLOv8/YOLO11 format: [1, 84, num_predictions]
                val numRows = shape[1].toInt()
                val numBoxes = shape[2].toInt()
                val candidateBoxes = mutableListOf<DetectionResult>()

                for (i in 0 until numBoxes) {
                    // Find max score among relevant vehicle & person classes:
                    // 0: Person, 1: Bicycle, 2: Car, 3: Motorbike, 5: Bus, 7: Truck
                    var maxScore = 0f
                    var maxClassId = -1
                    val relevantClassIds = intArrayOf(0, 1, 2, 3, 5, 7)

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

                        candidateBoxes.add(
                            DetectionResult(
                                boundingBox = RectF(x1, y1, x2, y2),
                                obstacleClass = ObstacleClass.fromClassId(maxClassId),
                                confidence = maxScore
                            )
                        )
                    }
                }

                // Fast NMS (IoU threshold = 0.45)
                candidateBoxes.sortByDescending { it.confidence }
                val selected = mutableListOf<DetectionResult>()
                for (cand in candidateBoxes) {
                    var shouldAdd = true
                    for (sel in selected) {
                        if (cand.obstacleClass == sel.obstacleClass && calculateIoU(cand.boundingBox, sel.boundingBox) > 0.45f) {
                            shouldAdd = false
                            break
                        }
                    }
                    if (shouldAdd) {
                        selected.add(cand)
                        if (selected.size >= 50) break // Keep top 50 objects max
                    }
                }
                results.addAll(selected)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            tensor.close()
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

    fun close() {
        try {
            ortSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
