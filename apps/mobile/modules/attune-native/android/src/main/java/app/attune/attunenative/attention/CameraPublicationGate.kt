package app.attune.attunenative.attention

/** Serializes session invalidation with every camera status mutation and publication. */
internal class CameraPublicationGate {
  private val lock = Any()
  private var generation = 0L

  fun advance(action: () -> Unit = {}): Long = synchronized(lock) {
    generation += 1
    action()
    generation
  }

  fun isCurrent(expected: Long): Boolean = synchronized(lock) { generation == expected }

  fun publish(expected: Long, action: () -> Unit): Boolean = synchronized(lock) {
    if (generation != expected) return false
    action()
    true
  }

  fun invalidate(expected: Long, action: () -> Unit): Boolean = synchronized(lock) {
    if (generation != expected) return false
    generation += 1
    action()
    true
  }
}
