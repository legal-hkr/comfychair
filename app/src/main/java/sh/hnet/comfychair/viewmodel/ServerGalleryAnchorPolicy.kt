package sh.hnet.comfychair.viewmodel

import sh.hnet.comfychair.model.ServerImage

/** Descriptor anchor, not an old numeric index: only confirmed successes move the viewport. */
object ServerGalleryAnchorPolicy {
    data class Anchor(val candidates: List<String>, val fallbackIndex: Int, val offset: Int)
    data class Position(val index: Int, val offset: Int)
    fun capture(before: List<ServerImage>, deleted: Set<ServerImage>, first: Int, offset: Int, visibleKeys: List<String> = emptyList()): Anchor {
        val index = first.coerceIn(0, (before.size - 1).coerceAtLeast(0))
        // First visible surviving descriptor, then nearest survivor (forward wins ties).
        val candidates = before.indices.filter { before[it] !in deleted }
            .sortedWith(compareBy<Int> { kotlin.math.abs(it - index) }.thenBy { if (it >= index) 0 else 1 })
            .map { before[it].stableKey }
        val fallback = index - before.take(index).count { it in deleted }
        val survivingKeys = candidates.toSet()
        return Anchor((visibleKeys.filter { it in survivingKeys } + candidates).distinct(), fallback.coerceAtLeast(0), offset.coerceAtLeast(0))
    }
    fun resolve(anchor: Anchor, after: List<ServerImage>): Position {
        if (after.isEmpty()) return Position(0, 0)
        val positions = after.mapIndexed { index, image -> image.stableKey to index }.toMap()
        val index = anchor.candidates.firstNotNullOfOrNull { positions[it] }
            ?: anchor.fallbackIndex.coerceIn(after.indices)
        return Position(index, anchor.offset)
    }
}
