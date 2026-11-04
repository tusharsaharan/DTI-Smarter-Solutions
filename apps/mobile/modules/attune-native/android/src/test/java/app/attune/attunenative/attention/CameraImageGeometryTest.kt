package app.attune.attunenative.attention

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CameraImageGeometryTest {
  private val black = 0xff000000.toInt()
  private val white = 0xffffffff.toInt()
  private val red = 0xffff0000.toInt()
  // Quantized BT.601 YUV (145, 54, 34) reconstructs green with one blue level.
  private val green = 0xff00ff01.toInt()
  private val blue = 0xff0000ff.toInt()
  private val yellow = 0xffffff00.toInt()
  private val luma = intArrayOf(81, 81, 145, 145, 81, 81, 145, 145, 41, 41, 210, 210, 41, 41, 210, 210)
  private val chromaU = intArrayOf(90, 54, 240, 16)
  private val chromaV = intArrayOf(240, 34, 110, 146)
  private val quadrants = intArrayOf(
    red, red, green, green,
    red, red, green, green,
    blue, blue, yellow, yellow,
    blue, blue, yellow, yellow
  )

  @Test fun blackWhiteAndOutOfRangeLumaAreOpaqueAndClamped() {
    val planes = listOf(plane(intArrayOf(16, 235, 0, 255), 2), plane(intArrayOf(128), 1), plane(intArrayOf(128), 1))
    assertArrayEquals(intArrayOf(black, white, black, white), CameraImageGeometry.argb(2, 2, planes))
  }

  @Test fun planarColorsPreserveUvOrderAndRowMajorPixelOrder() {
    assertArrayEquals(quadrants, CameraImageGeometry.argb(4, 4, planar()))
  }

  @Test fun paddedRowsDoNotRequirePaddingAfterTheFinalSample() {
    val planes = listOf(plane(luma, 4, rowStride = 7), plane(chromaU, 2, rowStride = 5), plane(chromaV, 2, rowStride = 5))
    assertArrayEquals(quadrants, CameraImageGeometry.argb(4, 4, planes))
  }

  @Test fun sharedUvAndVuBuffersMatchPlanarIncludingPixelAndRowPadding() {
    for (uvFirst in listOf(true, false)) {
      assertArrayEquals(quadrants, CameraImageGeometry.argb(4, 4, interleaved(uvFirst)))
    }
  }

  @Test fun nonzeroPositionsAndLimitsInReadOnlyDirectBuffersArePreservedAcrossCalls() {
    val planes = interleaved(uvFirst = true)
    val positions = planes.map { it.buffer.position() }
    val limits = planes.map { it.buffer.limit() }
    planes.forEach { it.buffer.mark() }

    repeat(2) {
      assertArrayEquals(quadrants, CameraImageGeometry.argb(4, 4, planes))
      assertEquals(positions, planes.map { it.buffer.position() })
      assertEquals(limits, planes.map { it.buffer.limit() })
      planes.forEach { it.buffer.reset() }
    }
  }

  @Test fun heapBufferSlicesUseTheirOwnPositionAsThePlaneOrigin() {
    val planes = planar().map { original ->
      val source = original.buffer.duplicate()
      val container = ByteBuffer.allocate(source.remaining() + 8)
      container.position(3)
      container.put(source)
      container.limit(container.position())
      container.position(1)
      val slice = container.slice()
      slice.position(2)
      original.copy(buffer = slice)
    }
    assertArrayEquals(quadrants, CameraImageGeometry.argb(4, 4, planes))
    assertEquals(listOf(2, 2, 2), planes.map { it.buffer.position() })
  }

  @Test fun oddCropOriginsUseFullImageChromaCoordinates() {
    val expected = intArrayOf(red, green, green, blue, yellow, yellow, blue, yellow, yellow)
    for (planes in listOf(planar(), interleaved(true), interleaved(false))) {
      assertArrayEquals(expected, CameraImageGeometry.argb(4, 4, planes, cropLeft = 1, cropTop = 1, cropWidth = 3, cropHeight = 3))
    }
  }

  @Test fun singlePixelCropAtBottomRightIncludesTheLastChromaSample() {
    assertArrayEquals(intArrayOf(yellow), CameraImageGeometry.argb(4, 4, interleaved(false), 3, 3, 1, 1))
  }

  @Test fun oddImageDimensionsRoundChromaSizeUp() {
    val planes = listOf(
      plane(intArrayOf(81, 81, 145, 81, 81, 145, 41, 41, 210), 3, rowStride = 5),
      plane(chromaU, 2, rowStride = 5, pixelStride = 2),
      plane(chromaV, 2, rowStride = 5, pixelStride = 2)
    )
    assertArrayEquals(intArrayOf(red, red, green, red, red, green, blue, blue, yellow), CameraImageGeometry.argb(3, 3, planes))
    assertArrayEquals(intArrayOf(red, green, blue, yellow), CameraImageGeometry.argb(3, 3, planes, 1, 1, 2, 2))
  }

  @Test fun onePixelImageNeedsOnlyOneSamplePerPlane() {
    val planes = listOf(plane(intArrayOf(81), 1), plane(intArrayOf(90), 1), plane(intArrayOf(240), 1))
    assertArrayEquals(intArrayOf(red), CameraImageGeometry.argb(1, 1, planes))
  }

  @Test fun eachTruncatedPlaneIsRejectedUsingLimitRatherThanCapacity() {
    for (index in 0..2) {
      val planes = interleaved(true)
      val buffer = planes[index].buffer
      buffer.limit(buffer.limit() - 1)
      val positions = planes.map { it.buffer.position() }
      val limits = planes.map { it.buffer.limit() }
      planes.forEach { it.buffer.mark() }
      rejects { CameraImageGeometry.argb(4, 4, planes) }
      // Declaring a crop cannot hide a malformed full-image plane.
      rejects { CameraImageGeometry.argb(4, 4, planes, 0, 0, 1, 1) }
      assertEquals(positions, planes.map { it.buffer.position() })
      assertEquals(limits, planes.map { it.buffer.limit() })
      planes.forEach { it.buffer.reset() }
    }
  }

  @Test fun emptyRemainingBufferIsRejectedEvenWithSufficientCapacity() {
    val planes = planar()
    planes[2].buffer.position(planes[2].buffer.limit())
    rejects { CameraImageGeometry.argb(4, 4, planes) }
  }

  @Test fun invalidDimensionsAndOverflowingPixelCountsFailBeforeAllocation() {
    val planes = planar()
    for ((width, height) in listOf(0 to 4, 4 to 0, -1 to 4, 4 to -1, Int.MIN_VALUE to 4, 65_536 to 65_536, Int.MAX_VALUE to Int.MAX_VALUE)) {
      rejects { CameraImageGeometry.argb(width, height, planes) }
    }
  }

  @Test fun missingAndExtraPlanesAreRejected() {
    val planes = planar()
    for (invalid in listOf(emptyList(), planes.take(1), planes.take(2), planes + planes[0])) {
      rejects { CameraImageGeometry.argb(4, 4, invalid) }
    }
  }

  @Test fun invalidCropsAreRejectedWithoutOverflowOrClipping() {
    val planes = planar()
    val invalidCrops = listOf(
      intArrayOf(-1, 0, 1, 1), intArrayOf(0, -1, 1, 1),
      intArrayOf(0, 0, 0, 1), intArrayOf(0, 0, 1, 0),
      intArrayOf(0, 0, -1, 1), intArrayOf(0, 0, 1, -1),
      intArrayOf(4, 0, 1, 1), intArrayOf(0, 4, 1, 1),
      intArrayOf(3, 0, 2, 1), intArrayOf(0, 3, 1, 2),
      intArrayOf(Int.MAX_VALUE, 0, Int.MAX_VALUE, 1),
      intArrayOf(0, Int.MAX_VALUE, 1, Int.MAX_VALUE),
      intArrayOf(0, 0, Int.MAX_VALUE, Int.MAX_VALUE)
    )
    for ((left, top, width, height) in invalidCrops) {
      rejects { CameraImageGeometry.argb(4, 4, planes, left, top, width, height) }
    }
  }

  @Test fun nonpositiveOverlappingAndOverflowingStridesAreRejected() {
    for (index in 0..2) {
      val original = planar()[index]
      for (invalid in listOf(
        original.copy(rowStride = 0), original.copy(rowStride = -1),
        original.copy(pixelStride = 0), original.copy(pixelStride = -1),
        original.copy(rowStride = 1), original.copy(rowStride = Int.MAX_VALUE),
        original.copy(rowStride = Int.MAX_VALUE, pixelStride = Int.MAX_VALUE)
      )) {
        val planes = planar().toMutableList().apply { set(index, invalid) }
        rejects { CameraImageGeometry.argb(4, 4, planes) }
      }
    }
  }

  @Test fun frontRotationCoversEverySensorAndDisplayRightAngleWithoutMirroring() {
    val angles = intArrayOf(0, 90, 180, 270)
    val expected = arrayOf(intArrayOf(0, 90, 180, 270), intArrayOf(90, 180, 270, 0), intArrayOf(180, 270, 0, 90), intArrayOf(270, 0, 90, 180))
    for (sensor in angles.indices) for (display in angles.indices) {
      assertEquals(expected[sensor][display], CameraImageGeometry.frontRotation(angles[sensor], angles[display]))
    }
  }

  @Test fun frontRotationRejectsNoncanonicalAnglesAndSurfaceRotationEnumValues() {
    for (invalid in intArrayOf(-90, -1, 1, 2, 3, 45, 360, 450, Int.MIN_VALUE, Int.MAX_VALUE)) {
      rejects { CameraImageGeometry.frontRotation(invalid, 0) }
      rejects { CameraImageGeometry.frontRotation(0, invalid) }
    }
  }

  private fun planar() = listOf(plane(luma, 4), plane(chromaU, 2), plane(chromaV, 2))

  /** Synthetic samples with poisoned padding and a limit immediately after the final sample. */
  private fun plane(samples: IntArray, width: Int, rowStride: Int = width, pixelStride: Int = 1): CameraYuvPlane {
    val end = (samples.size / width - 1) * rowStride + (width - 1) * pixelStride + 1
    val buffer = ByteBuffer.wrap(ByteArray(end + 3) { 0x7f.toByte() })
    samples.forEachIndexed { index, value -> buffer.put((index / width) * rowStride + (index % width) * pixelStride, value.toByte()) }
    buffer.limit(end)
    return CameraYuvPlane(buffer, rowStride, pixelStride)
  }

  /** Actual aliased NV12/NV21-style views, with independent nonzero origins and shortened limits. */
  private fun interleaved(uvFirst: Boolean): List<CameraYuvPlane> {
    val prefix = 3
    val rowStride = 6
    val lastSample = rowStride + 2
    val shared = ByteBuffer.allocateDirect(prefix + lastSample + 5)
    for (index in 0 until shared.capacity()) shared.put(index, 0x7f.toByte())
    val uOffset = if (uvFirst) 0 else 1
    val vOffset = 1 - uOffset
    for (index in chromaU.indices) {
      val sample = prefix + (index / 2) * rowStride + (index % 2) * 2
      shared.put(sample + uOffset, chromaU[index].toByte())
      shared.put(sample + vOffset, chromaV[index].toByte())
    }
    fun chroma(offset: Int): CameraYuvPlane {
      val buffer = shared.asReadOnlyBuffer()
      buffer.position(prefix + offset)
      buffer.limit(prefix + offset + lastSample + 1)
      return CameraYuvPlane(buffer, rowStride, 2)
    }
    val ySource = plane(luma, 4, rowStride = 7).buffer
    val yBuffer = ByteBuffer.allocateDirect(ySource.remaining() + 7)
    yBuffer.position(5)
    yBuffer.put(ySource)
    yBuffer.limit(yBuffer.position())
    yBuffer.position(5)
    return listOf(CameraYuvPlane(yBuffer.asReadOnlyBuffer(), 7, 1), chroma(uOffset), chroma(vOffset))
  }

  private fun rejects(block: () -> Unit) {
    try {
      block()
      fail("Expected IllegalArgumentException")
    } catch (_: IllegalArgumentException) {
      // IndexOutOfBoundsException and allocation failures must not substitute for validation.
    }
  }
}
