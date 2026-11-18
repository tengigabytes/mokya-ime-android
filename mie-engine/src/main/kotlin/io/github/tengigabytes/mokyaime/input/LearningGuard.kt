// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

/**
 * Keeps editors that must not be learned from (passwords, incognito:
 * [EditorPolicy.forbidsLearning]) out of the personalised LRU.
 *
 * The engine learns from every candidate it commits, so entering such an
 * editor takes a snapshot of the LRU and leaving it puts the snapshot back,
 * which forgets what was learned there. Android does not always pair the
 * two: input can restart in an editor (InputMethodService calls
 * onStartInput with restarting = true, and no onFinishInput before it),
 * and the service can be destroyed while it still serves one. So [enter]
 * puts back a snapshot it still holds before taking a new one, and [leave]
 * must also run before the LRU is saved when the service ends.
 *
 * [snapshot] serialises the LRU, or returns null when there is none to
 * guard (no engine); [restore] loads a snapshot back. Main thread only.
 */
class LearningGuard(
    private val snapshot: () -> ByteArray?,
    private val restore: (ByteArray) -> Unit,
) {
    private var held: ByteArray? = null

    /** True while a snapshot is held: what is learned now will be forgotten. */
    val holding: Boolean get() = held != null

    /**
     * Input starts or restarts in an editor: forget what an earlier
     * no-learning editor taught, then start guarding this one if
     * [forbidsLearning].
     */
    fun enter(forbidsLearning: Boolean) {
        leave()
        if (forbidsLearning) held = snapshot()
    }

    /** The editor is left (or the service ends): puts back the snapshot, if one is held. */
    fun leave() {
        val saved = held ?: return
        held = null
        restore(saved)
    }
}
