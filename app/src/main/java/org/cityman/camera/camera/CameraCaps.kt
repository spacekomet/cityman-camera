package org.cityman.camera.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.media.MediaRecorder
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder
import kotlin.math.abs

enum class PhotoFormat { FULL, RATIO_4_3, RATIO_16_9 }

/** What one camera can do, read once from its characteristics. */
class CameraCaps(val id: String, val chars: CameraCharacteristics) {
    val front = chars[CameraCharacteristics.LENS_FACING] == CameraMetadata.LENS_FACING_FRONT
    val sensorOrientation: Int = chars[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90
    val activeArray: Rect = chars[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE] ?: Rect(0, 0, 1, 1)
    val isoRange: Range<Int>? = chars[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]
    val exposureRange: Range<Long>? = chars[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]
    val evRange: Range<Int> = chars[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE] ?: Range(0, 0)
    val evStep: Float = chars[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP]?.toFloat() ?: 0f
    val minFocusDiopters: Float = chars[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f
    val afModes: Set<Int> = chars[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]?.toSet() ?: emptySet()
    private val capabilities = chars[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.toSet() ?: emptySet()
    val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
    val manualPostProcessing = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities
    val maxZoom: Float = chars[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] ?: 1f
    val maxAfRegions: Int = chars[CameraCharacteristics.CONTROL_MAX_REGIONS_AF] ?: 0
    val maxAeRegions: Int = chars[CameraCharacteristics.CONTROL_MAX_REGIONS_AE] ?: 0
    val fpsRanges: List<Range<Int>> = chars[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.toList() ?: emptyList()
    val color: ColorCalibration? = ColorCalibration.from(chars)

    private val map = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
    private val jpegSizes: List<Size> = map.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
    private val previewSizes: List<Size> = map.getOutputSizes(SurfaceHolder::class.java)?.toList() ?: emptyList()
    private val videoSizes: List<Size> = map.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
    private val yuvSizes: List<Size> = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList()

    /** Small YUV size with the viewfinder's shape, for frame analysis. */
    fun analysisSize(preview: Size): Size? {
        val ratio = preview.width.toFloat() / preview.height
        return yuvSizes.filter { it.width in 320..640 }
            .minWithOrNull(compareBy<Size> { (abs(it.width.toFloat() / it.height - ratio) * 50).toInt() }.thenByDescending { it.area() })
    }

    val rawSize: Size? = if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities) {
        map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.area() }
    } else null

    val hasFocus: Boolean get() = minFocusDiopters > 0f && CameraMetadata.CONTROL_AF_MODE_OFF in afModes
    val hasAutoFocus: Boolean get() = CameraMetadata.CONTROL_AF_MODE_AUTO in afModes
    
    /** Fallback limits, tightened when a phone's camera HAL crashes with the default photo streams. */
    var jpegPixelLimit: Long = Long.MAX_VALUE
    var previewWidthLimit: Int = 1920
    
    fun jpegSize(format: PhotoFormat): Size {
        val sizes = jpegSizes.filter { it.area() <= jpegPixelLimit }.ifEmpty { jpegSizes }
        val largest = sizes.maxBy { it.area() }
        return when (format) {
            PhotoFormat.FULL -> largest
            PhotoFormat.RATIO_4_3 -> sizes.filter { it.ratioNear(4f / 3f) }.maxByOrNull { it.area() } ?: largest
            PhotoFormat.RATIO_16_9 -> sizes.filter { it.ratioNear(16f / 9f) }.maxByOrNull { it.area() } ?: largest
        }
    }


    /** Largest viewfinder size up to 1080p whose shape is closest to [target]. */
    fun previewSize(target: Size): Size {
        val ratio = target.width.toFloat() / target.height
        val candidates = previewSizes.filter { it.width <= previewWidthLimit && it.height <= 1080 }.ifEmpty { previewSizes }
        return candidates.minWith(
            compareBy<Size> { (abs(it.width.toFloat() / it.height - ratio) * 50).toInt() }
                .thenByDescending { it.area() },
        )
    }

    fun videoSize(): Size {
        val wanted = listOf(Size(1920, 1080), Size(1280, 720))
        return wanted.firstOrNull { it in videoSizes && it in previewSizes }
            ?: videoSizes.filter { it.ratioNear(16f / 9f) && it.width <= 1920 }.maxByOrNull { it.area() }
            ?: videoSizes.first()
    }

    /** Widest frame-rate range for photos (longer exposures in low light), fixed and fastest for video. */
    fun fpsRange(video: Boolean): Range<Int>? {
        if (fpsRanges.isEmpty()) return null
        return if (video) {
            fpsRanges.filter { it.lower == it.upper && it.upper <= 30 }.maxByOrNull { it.upper }
                ?: fpsRanges.maxBy { it.upper }
        } else {
            fpsRanges.maxWith(compareBy<Range<Int>> { it.upper - it.lower }.thenBy { it.upper })
        }
    }

    fun minFrameDuration(format: Int, size: Size): Long = map.getOutputMinFrameDuration(format, size)

    companion object {
        fun Size.area() = width.toLong() * height
        fun Size.ratioNear(r: Float) = abs(width.toFloat() / height - r) < 0.02f
    }
}

/**
 * Manual overrides; null means automatic. Exposure compensation is in Camera2 steps and only
 * applies while ISO or shutter (or both) are automatic.
 */
data class ManualControls(
    val wbKelvin: Float? = null,
    val focusDiopters: Float? = null,
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val evSteps: Int = 0,
)

/** Values reported by the camera for the latest viewfinder frame. */
data class LiveValues(
    val iso: Int = 0,
    val exposureNs: Long = 0,
    val focusDiopters: Float = 0f,
    val kelvin: Float? = null,
    val afState: Int = 0,
)
