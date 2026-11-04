package app.attune.attunenative.attention

import java.nio.ByteBuffer

/** The buffer's current position is the uncropped plane origin; its limit bounds valid data. */
data class CameraYuvPlane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

/** Pure, in-memory camera preprocessing. The caller must keep the source image open during conversion. */
object CameraImageGeometry {
  /**
   * Converts Y, U (Cb), V (Cr) planes to opaque, row-major ARGB using the existing BT.601
   * limited-range transform. The crop is in full-resolution luma coordinates, before rotation.
   * Buffer positions, limits, marks and contents are preserved; direct/read-only buffers work.
   *
   * The full declared planes must be present, but padding after their final samples is optional.
   * Strides are independent, supporting planar and shared/interleaved chroma storage.
   * Layout/crop contracts: https://developer.android.com/reference/android/graphics/ImageFormat#YUV_420_888
   * and https://developer.android.com/reference/android/media/Image#getCropRect()
   *
   * @throws IllegalArgumentException for invalid dimensions, crops, strides or truncated planes.
   */
  fun argb(
    width: Int,
    height: Int,
    planes: List<CameraYuvPlane>,
    cropLeft: Int = 0,
    cropTop: Int = 0,
    cropWidth: Int = width,
    cropHeight: Int = height
  ): IntArray {
    require(width > 0 && height > 0) { "Image dimensions must be positive" }
    require(planes.size == 3) { "YUV_420_888 requires exactly three planes in Y, U, V order" }
    require(cropLeft >= 0 && cropTop >= 0 && cropWidth > 0 && cropHeight > 0) {
      "Crop origin must be nonnegative and dimensions must be positive"
    }
    require(cropLeft <= width - cropWidth && cropTop <= height - cropHeight) {
      "Crop must be inside the image"
    }
    val pixelCount = cropWidth.toLong() * cropHeight
    require(pixelCount <= Int.MAX_VALUE) { "Crop has too many pixels" }

    // Ceiling division also supports odd source dimensions without overflowing width + 1.
    val chromaWidth = (width - 1) / 2 + 1
    val chromaHeight = (height - 1) / 2 + 1
    val yPlane = checkedPlane(planes[0], width, height)
    val uPlane = checkedPlane(planes[1], chromaWidth, chromaHeight)
    val vPlane = checkedPlane(planes[2], chromaWidth, chromaHeight)

    // Validate every source plane before allocating output or reading any samples.
    val pixels = IntArray(pixelCount.toInt())
    var output = 0
    for (y in cropTop until cropTop + cropHeight) {
      val yRow = yPlane.buffer.position() + y * yPlane.rowStride
      val uRow = uPlane.buffer.position() + (y / 2) * uPlane.rowStride
      val vRow = vPlane.buffer.position() + (y / 2) * vPlane.rowStride
      for (x in cropLeft until cropLeft + cropWidth) {
        val yy = ((yPlane.buffer.get(yRow + x * yPlane.pixelStride).toInt() and 255) - 16).coerceAtLeast(0)
        // Subsample the source coordinates, not the crop-local coordinates (odd origins differ).
        val u = (uPlane.buffer.get(uRow + (x / 2) * uPlane.pixelStride).toInt() and 255) - 128
        val v = (vPlane.buffer.get(vRow + (x / 2) * vPlane.pixelStride).toInt() and 255) - 128
        val r = ((298 * yy + 409 * v + 128) shr 8).coerceIn(0, 255)
        val g = ((298 * yy - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
        val b = ((298 * yy + 516 * u + 128) shr 8).coerceIn(0, 255)
        pixels[output++] = (255 shl 24) or (r shl 16) or (g shl 8) or b
      }
    }
    return pixels
  }

  /** Front-camera ML input rotation, without mirroring. Both arguments must be 0, 90, 180 or 270. */
  fun frontRotation(sensorOrientation: Int, displayRotationDegrees: Int): Int {
    require(sensorOrientation in 0..270 && sensorOrientation % 90 == 0) {
      "Sensor orientation must be 0, 90, 180 or 270 degrees"
    }
    require(displayRotationDegrees in 0..270 && displayRotationDegrees % 90 == 0) {
      "Display rotation must be 0, 90, 180 or 270 degrees"
    }
    return (sensorOrientation + displayRotationDegrees) % 360
  }

  private fun checkedPlane(plane: CameraYuvPlane, width: Int, height: Int): CameraYuvPlane {
    require(plane.rowStride > 0 && plane.pixelStride > 0) { "Plane strides must be positive" }
    val rowBytes = (width - 1).toLong() * plane.pixelStride + 1
    require(rowBytes <= plane.rowStride.toLong()) { "Plane row stride is smaller than its samples" }
    val buffer = plane.buffer.duplicate()
    val lastSample = buffer.position().toLong() + (height - 1).toLong() * plane.rowStride + rowBytes - 1
    // Image.Plane.getBuffer() only guarantees bytes through the last pixel, not trailing padding.
    require(lastSample < buffer.limit().toLong()) { "Plane buffer is truncated at its current limit" }
    return plane.copy(buffer = buffer)
  }
}
