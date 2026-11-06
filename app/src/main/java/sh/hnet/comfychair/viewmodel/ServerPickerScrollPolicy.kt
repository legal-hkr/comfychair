package sh.hnet.comfychair.viewmodel

/** Pagination depends on descriptors/layout, never thumbnail completion or preview errors. */
object ServerPickerScrollPolicy {
    const val MAX_SPARSE_PAGES = 5

    fun shouldLoad(state: ServerImagePickerState, lastVisible: Int, laidOutItems: Int): Boolean {
        if (state.slot == null || state.loading || state.loadingMore || state.downloading ||
            state.error || state.moreError || state.sparsePages >= MAX_SPARSE_PAGES ||
            (state.nextOffset == null && state.nextCursor == null)) return false
        // A response can arrive before LazyGrid measures appended items. Wait for that layout
        // instead of draining the library using the previous page's near-bottom=true value.
        if (laidOutItems != state.images.size) return false
        return state.images.isEmpty() || (lastVisible >= 0 && lastVisible >= state.images.size - 9)
    }
}
