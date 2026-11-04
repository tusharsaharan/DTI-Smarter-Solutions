package app.attune.attunenative.attention

/** Reduced ratios only, exposed via Android's permission-protected service dump, never app events.
 * No image, face mesh, identity features or history is retained here. */
fun GazeFrame.diagnostics(): Map<String, Float?> = mapOf(
  "leftX" to left?.x, "leftY" to left?.y, "leftOpen" to left?.openness,
  "rightX" to right?.x, "rightY" to right?.y, "rightOpen" to right?.openness,
  "blendX" to lookHorizontal, "blendY" to lookVertical,
  "leftBlendX" to leftDirection?.x, "leftBlendY" to leftDirection?.y,
  "rightBlendX" to rightDirection?.x, "rightBlendY" to rightDirection?.y,
  "span" to faceSpan, "centerX" to faceCenterX, "centerY" to faceCenterY, "roll" to roll,
  "yaw" to yaw, "pitch" to pitch, "blinkLeft" to blinkLeft, "blinkRight" to blinkRight
).mapValues { (_, value) -> value?.takeIf { it.isFinite() } }
