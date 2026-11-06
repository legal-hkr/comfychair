package sh.hnet.comfychair.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import sh.hnet.comfychair.model.ServerImageQuery
import sh.hnet.comfychair.model.ServerImageSort
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.hnet.comfychair.model.ServerImage
import sh.hnet.comfychair.model.ServerImagePage
import sh.hnet.comfychair.model.ServerImageSource

/** Identity includes the actual authenticated client, not just a reusable server ID. */
data class ServerImageIdentity(val serverId: String, val client: Any, val epoch: Any = client)
data class ServerImagePickerState(
    val slot: Int? = null,
    val source: ServerImageSource = ServerImageSource.INPUT,
    val images: List<ServerImage> = emptyList(),
    val loading: Boolean = false,
    val downloading: Boolean = false,
    val error: Boolean = false,
    val loadingMore: Boolean = false,
    val moreError: Boolean = false,
    val nextOffset: Int? = null,
    val nextCursor: String? = null,
    val assets: Boolean? = null,
    val totalAssets: Long? = null,
    val query: String = "",
    val sort: ServerImageSort = ServerImageSort.NEWEST,
    val selected: ServerImage? = null,
    val downloadError: Boolean = false,
    val sparsePages: Int = 0,

    val session: Long = 0
)

/** All state transitions and acceptance run on the owning (UI) scope. IO work is injected. */
class ServerImagePicker<T>(
    private val scope: CoroutineScope,
    private val identity: () -> ServerImageIdentity?,
    private val validSlot: (Int) -> Boolean,
    private val list: suspend (ServerImageIdentity, ServerImageSource, Int?) -> ServerImagePage,
    private val download: suspend (ServerImageIdentity, ServerImage) -> T,
    private val accept: (ServerImageIdentity, Int, T) -> Unit,
    private val dispose: (T) -> Unit = {},
    private val browse: (suspend (ServerImageIdentity, ServerImageSource, ServerImageQuery) -> ServerImagePage)? = null,
    private val cached: (suspend (ServerImageIdentity, ServerImageSource, ServerImageQuery) -> ServerImagePage?)? = null,
    private val store: (suspend (ServerImageIdentity, ServerImageSource, ServerImageQuery, ServerImagePage, Long) -> Unit)? = null,
    private val cacheGeneration: () -> Long = { 0L }
) {
    private val mutableState = MutableStateFlow(ServerImagePickerState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var revision = 0L
    private var listRevision = 0L
    private var origin: ServerImageIdentity? = null
    private var previews: Job? = null
    private val previewPermits = Semaphore(3)
    private var inputChoices: List<ServerImage> = emptyList()
    private val seenCursors = mutableSetOf<String>()
    private val deletedImages = mutableSetOf<ServerImage>()
    private var refreshingFrom: ServerImagePickerState? = null

    /** Child of popup lifetime AND caller tile lifetime; never leaves detached downloads. */
    suspend fun <V> preview(image: ServerImage, block: suspend (ServerImageIdentity) -> V): V {
        val owner = origin ?: throw CancellationException("Closed")
        val token = revision
        val slot = mutableState.value.slot ?: throw CancellationException("Closed")
        val parent = previews ?: throw CancellationException("Closed")
        if (!current(token, owner, slot) || image !in mutableState.value.images) throw CancellationException("Stale")
        val task = CoroutineScope(scope.coroutineContext + parent).async {
            previewPermits.withPermit {
                // Parent cancellation can release permits before queued child cancellation propagates.
                if (!parent.isActive || mutableState.value.downloading || !current(token, owner, slot)) throw CancellationException("Closed")
                block(owner)
            }
        }
        try {
            val value = task.await()
            if (!current(token, owner, slot)) throw CancellationException("Stale")
            return value
        } finally { task.cancel() }
    }

    fun close() {
        revision++
        job?.cancel()
        job = null
        previews?.cancel()
        previews = null
        inputChoices = emptyList()
        seenCursors.clear()
        deletedImages.clear()
        refreshingFrom = null
        origin = null
        mutableState.value = ServerImagePickerState()
    }

    fun connectionChanged() {
        if (origin != null && (origin != identity() || mutableState.value.slot?.let { !validSlot(it) } == true)) close()
    }

    private fun current(token: Long, owner: ServerImageIdentity, slot: Int) =
        token == revision && owner == identity() && validSlot(slot)

    fun open(slot: Int, source: ServerImageSource) {
        close()
        if (!validSlot(slot)) return
        val owner = identity()
        mutableState.value = ServerImagePickerState(slot = slot, source = source, loading = owner != null, error = owner == null, session = revision)
        if (owner == null) return
        origin = owner
        previews = SupervisorJob(scope.coroutineContext[Job])
        load(owner, slot, source, null)
    }

    fun changeQuery(name: String, sort: ServerImageSort) {
        val old = mutableState.value
        val slot = old.slot ?: return
        if (old.downloading) return
        val owner = origin ?: return
        revision++
        refreshingFrom = null
        job?.cancel(); previews?.cancel()
        previews = SupervisorJob(scope.coroutineContext[Job])
        inputChoices = emptyList()
        seenCursors.clear()
        mutableState.value = ServerImagePickerState(slot = slot, source = old.source, query = name,
            sort = sort, assets = old.assets, loading = true, session = revision)
        load(owner, slot, old.source, null, debounce = true)
    }

    fun refresh() {
        val s = mutableState.value
        val owner = origin ?: return
        val slot = s.slot ?: return
        if (s.downloading || s.loading) return
        job?.cancel()
        refreshingFrom = s
        seenCursors.clear()
        mutableState.value = s.copy(loading = true, loadingMore = false, error = false, moreError = false,
            sparsePages = 0, nextOffset = null, nextCursor = null)
        load(owner, slot, s.source, null, useCache = false)
    }

    /** Keep loaded pages/cursors/session. In-flight refresh/append responses are filtered too. */
    fun removeDeleted(images: List<ServerImage>, session: Long) {
        val state = mutableState.value
        val owner = origin ?: return
        val slot = state.slot ?: return
        if (state.session != session || !current(revision, owner, slot)) return
        val success = images.filter { it.type == state.source.type }.toSet()
        if (success.isEmpty()) return
        val newlyDeleted = success - deletedImages
        deletedImages.addAll(success)
        // Invalidate non-cooperative refresh/pagination callbacks without changing UI session.
        listRevision++; job?.cancel(); job = null
        val retained = refreshingFrom ?: state
        refreshingFrom = null
        inputChoices = inputChoices.filterNot { it in success }
        mutableState.value = state.copy(images = state.images.filterNot { it in success },
            loading = false, loadingMore = false, nextCursor = retained.nextCursor, nextOffset = if (state.source == ServerImageSource.INPUT && state.assets != true)
                (retained.nextOffset?.minus(retained.images.count { it in success }))?.coerceAtLeast(0) else retained.nextOffset,
            totalAssets = state.totalAssets?.let { (it - newlyDeleted.size).coerceAtLeast(0) },
            selected = state.selected?.takeUnless { it in success })
    }

    fun cancelDownload() {
        if (!mutableState.value.downloading) return
        revision++; job?.cancel()
        previews = SupervisorJob(scope.coroutineContext[Job])
        mutableState.value = mutableState.value.copy(downloading = false, selected = null, downloadError = false)
    }

    fun retryDownload() { mutableState.value.selected?.let { select(it) } }

    fun loadMore(retry: Boolean = false) {
        val state = mutableState.value
        val slot = state.slot ?: return
        val owner = origin ?: return
        val offset = state.nextOffset
        if (offset == null && state.nextCursor == null) return
        if (state.loading || state.loadingMore || state.downloading ||
            (state.moreError && !retry)) return
        if (!current(revision, owner, slot)) { close(); return }
        if (state.source == ServerImageSource.INPUT && state.assets != true) {
            val end = ((offset ?: 0) + 50).coerceAtMost(inputChoices.size)
            mutableState.value = state.copy(images = inputChoices.take(end), nextOffset = end.takeIf { it < inputChoices.size })
            return
        }
        mutableState.value = state.copy(loadingMore = true, moreError = false,
            sparsePages = if (retry) 0 else state.sparsePages)
        load(owner, slot, state.source, offset)
    }

    private fun load(owner: ServerImageIdentity, slot: Int, source: ServerImageSource, offset: Int?, debounce: Boolean = false, useCache: Boolean = true) {
        val token = revision
        val listToken = ++listRevision
        fun active() = listToken == listRevision && current(token, owner, slot)
        val generation = cacheGeneration()
        job = scope.launch {
            try {
                if (debounce) delay(300)
                val request = mutableState.value
                val query = ServerImageQuery(request.query, request.sort, request.nextCursor, offset)
                val append = offset != null || request.nextCursor != null
                if (!append && useCache) {
                    val saved = cached?.invoke(owner, source, query)
                    if (saved != null && active()) {
                        if (source == ServerImageSource.INPUT && !saved.assets) inputChoices = saved.images
                        // Retain grid throughout revalidation, never restore stale pagination cursors.
                        mutableState.value = mutableState.value.copy(images = if (source == ServerImageSource.INPUT && !saved.assets)
                            saved.images.filterNot { it in deletedImages }.take(50) else saved.images.filterNot { it in deletedImages }, assets = saved.assets, totalAssets = saved.totalAssets)
                    }
                }
                val page = browse?.invoke(owner, source, ServerImageQuery(request.query, request.sort, request.nextCursor, offset))
                    ?: list(owner, source, offset)
                if (page.assets) require(page.nextCursor == null || page.nextCursor !in seenCursors)
                if (request.assets == true && append) require(page.assets)
                // A malformed/non-progressing cursor is an error, not an endless scroll loop.
                require(page.nextOffset == null || (page.nextOffset > 0 && (offset == null || page.nextOffset < offset)))
                if (active()) {
                    if (!append && generation == cacheGeneration()) store?.invoke(owner, source, query, page, generation)
                    if (!active()) return@launch
                    val state = mutableState.value
                    val images = if (source == ServerImageSource.INPUT && !page.assets) page.images.filterNot { it in deletedImages }.distinct().take(50)
                        else (if (!append) page.images else state.images + page.images).filterNot { it in deletedImages }.distinct()
                    refreshingFrom = null
                    page.nextCursor?.let { seenCursors.add(it) }
                    if (source == ServerImageSource.INPUT && !page.assets) inputChoices = page.images.filterNot { it in deletedImages }.distinct()
                    mutableState.value = state.copy(
                        images = images,
                        sparsePages = if (append && images.size == state.images.size) state.sparsePages + 1 else 0,
                        loading = false, loadingMore = false, error = false, moreError = false,
                        nextOffset = if (source == ServerImageSource.INPUT && !page.assets) 50.takeIf { inputChoices.size > 50 } else page.nextOffset,
                        nextCursor = page.nextCursor, assets = page.assets, totalAssets = page.totalAssets
                    )
                } else if (token == revision && listToken == listRevision) close()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                sh.hnet.comfychair.util.DebugLogger.w("ServerImagePicker", "picker_error " + e.javaClass.simpleName)
                if (active()) mutableState.value = mutableState.value.copy(
                    loading = false, loadingMore = false, error = offset == null && mutableState.value.nextCursor == null, moreError = offset != null || mutableState.value.nextCursor != null)
                else if (token == revision && listToken == listRevision) close()
            }
        }
    }

    fun select(image: ServerImage) {
        val state = mutableState.value
        val slot = state.slot ?: return
        val owner = origin ?: return
        if (state.loading || state.downloading || image !in state.images) return
        if (!current(revision, owner, slot)) { close(); return }
        // Selection supersedes in-flight pagination, including non-cooperative callbacks.
        revision++
        job?.cancel()
        previews?.cancel()
        val token = revision
        mutableState.value = state.copy(downloading = true, selected = image, downloadError = false, loadingMore = false, error = false)
        job = scope.launch {
            try {
                val value = download(owner, image)
                // No suspension between this final identity check and source/cache acceptance.
                try { if (current(token, owner, slot)) accept(owner, slot, value) }
                finally { dispose(value) }
                if (token == revision) close()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                sh.hnet.comfychair.util.DebugLogger.w("ServerImagePicker", "picker_error " + e.javaClass.simpleName)
                if (current(token, owner, slot)) {
                    previews = SupervisorJob(scope.coroutineContext[Job])
                    mutableState.value = mutableState.value.copy(downloading = false, error = true, downloadError = true)
                }
                else if (token == revision) close()
            }
        }
    }
}
