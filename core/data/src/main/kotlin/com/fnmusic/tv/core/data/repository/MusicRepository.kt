package com.fnmusic.tv.core.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import com.fnmusic.tv.core.data.api.AlbumDto
import com.fnmusic.tv.core.data.api.ApiDecoder
import com.fnmusic.tv.core.data.api.ArtistDto
import com.fnmusic.tv.core.data.api.LyricListDto
import com.fnmusic.tv.core.data.api.PlaylistDetailDto
import com.fnmusic.tv.core.data.api.PlaylistDto
import com.fnmusic.tv.core.data.api.SharedLibraryDto
import com.fnmusic.tv.core.data.api.SortedPageListDto
import com.fnmusic.tv.core.data.api.TrackDto
import com.fnmusic.tv.core.data.api.TrackMetadataDto
import com.fnmusic.tv.core.data.api.isRetryableRequestFailure
import com.fnmusic.tv.core.data.local.CachedIndexEntity
import com.fnmusic.tv.core.data.local.CachedLyricEntity
import com.fnmusic.tv.core.data.local.CachedPageEntity
import com.fnmusic.tv.core.data.local.LocalStore
import com.fnmusic.tv.core.data.preferences.AppPreferences
import com.fnmusic.tv.core.model.Album
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import com.fnmusic.tv.core.model.Artist
import com.fnmusic.tv.core.model.CoverVariant
import com.fnmusic.tv.core.model.LyricDocument
import com.fnmusic.tv.core.model.Page
import com.fnmusic.tv.core.model.PlaybackTrack
import com.fnmusic.tv.core.model.Playlist
import com.fnmusic.tv.core.model.RoamWindow
import com.fnmusic.tv.core.model.SharedLibrary
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.playback.QueueSource
import com.fnmusic.tv.core.model.preferences.CacheUsage
import com.fnmusic.tv.core.lyrics.DefaultLyricsSources
import com.fnmusic.tv.core.lyrics.LyricsMatchCoordinator
import com.fnmusic.tv.core.lyrics.LyricsMatchRequest
import com.fnmusic.tv.core.lyrics.LyricsMatchResult
import com.fnmusic.tv.core.lyrics.hasUsableLines
import com.fnmusic.tv.core.lyrics.parseLyrics
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val MAX_ARTWORK_DOWNLOAD_BYTES = 20 * 1024 * 1024
private const val MAX_ARTWORK_EDGE = 8_192
private const val MAX_ARTWORK_PIXELS = 16_000_000L
private const val ARTWORK_VALIDATION_LONG_EDGE = 128

private const val SEARCH_PAGE_SIZE = 200

/** 搜索索引使用的分页大小：与浏览页相同（浏览页已验证该大小下服务端分页正常、可完整拉取）。 */
private const val SEARCH_INDEX_PAGE_SIZE = 50

/** 搜索单类目最大扫描页数（200 条/页，最多 10 万条），纯属防御性上限，防止异常服务端导致死循环。 */
private const val SEARCH_MAX_PAGES = 500

/** 客户端本地搜索结果，按歌单 / 歌手 / 专辑 / 歌曲分类。 */
data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val albums: List<Album> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && artists.isEmpty() && playlists.isEmpty() && albums.isEmpty()
}

/** 基于汉字拼音的搜索匹配器，支持中文子串、拼音全拼与拼音首字母。 */
private class SearchMatcher(private val query: String) {
    private val qLower = query.lowercase()
    private val qPinyin = PinyinIndex.of(query).pinyinCompact
    private val qInitials = PinyinIndex.of(query).initials

    fun matchesTrack(track: Track): Boolean =
        matches(track.title) || matches(track.artistName.orEmpty()) || matches(track.albumName.orEmpty())

    fun matchesArtist(artist: Artist): Boolean = matches(artist.name)

    fun matchesAlbum(album: Album): Boolean = matches(album.name) || matches(album.artistName.orEmpty())

    fun matchesName(name: String): Boolean = matches(name)

    private fun matches(text: String): Boolean {
        if (text.isEmpty()) return false
        val idx = PinyinIndex.of(text.lowercase())
        if (idx.lower.contains(qLower)) return true
        if (qPinyin.isNotEmpty() && idx.pinyinCompact.contains(qPinyin)) return true
        if (qInitials.isNotEmpty() && idx.initials.contains(qInitials)) return true
        return false
    }

    val trackRank: Comparator<Track> = rankBy(Track::title)
    val artistRank: Comparator<Artist> = rankBy(Artist::name)
    val albumRank: Comparator<Album> = rankBy(Album::name)
    val playlistRank: Comparator<Playlist> = rankBy(Playlist::name)

    private fun <T> rankBy(selector: (T) -> String): Comparator<T> = compareByDescending<T> { item ->
        val n = selector(item).lowercase()
        val idx = PinyinIndex.of(n)
        when {
            n.startsWith(qLower) -> 3
            idx.pinyinCompact.startsWith(qPinyin) && qPinyin.isNotEmpty() -> 2
            idx.initials.startsWith(qInitials) && qInitials.isNotEmpty() -> 2
            n.contains(qLower) -> 1
            else -> 0
        }
    }.thenBy { selector(it) }
}

/**
 * 名称的拼音索引：小写原文、全拼（无空格无音调）、首字母。
 * 使用纯 Java 的 pinyin4j 完成汉字转拼音（无原生代码、无原生崩溃风险），
 * 单字符转拼音失败/非汉字时跳过；拼音结果按名称缓存。
 */
private class PinyinIndex(
    val lower: String,
    val pinyinCompact: String,
    val initials: String,
) {
    companion object {
        // 拼音结果缓存，带容量上限（LRU），防止长时间使用内存无限增长
        private val cache = object : LinkedHashMap<String, PinyinIndex>(4_096, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PinyinIndex>): Boolean =
                size > 30_000
        }

        private val format: net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat by lazy {
            net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat().apply {
                toneType = net.sourceforge.pinyin4j.format.HanyuPinyinToneType.WITHOUT_TONE
                vCharType = net.sourceforge.pinyin4j.format.HanyuPinyinVCharType.WITH_V
                caseType = net.sourceforge.pinyin4j.format.HanyuPinyinCaseType.LOWERCASE
            }
        }

        fun of(text: String): PinyinIndex {
            val lower = text.lowercase()
            synchronized(cache) {
                return cache.getOrPut(lower) { build(lower) }
            }
        }

        private fun build(lower: String): PinyinIndex {
            val compact = StringBuilder()
            val initials = StringBuilder()
            for (ch in lower) {
                if (ch in 'a'..'z' || ch in '0'..'9') {
                    compact.append(ch)
                    initials.append(ch)
                    continue
                }
                if (ch == ' ') continue
                val py = runCatching {
                    net.sourceforge.pinyin4j.PinyinHelper.toHanyuPinyinStringArray(ch, format)
                }.getOrNull()?.firstOrNull()
                if (py != null && py.isNotEmpty()) {
                    compact.append(py)
                    initials.append(py[0])
                }
            }
            return PinyinIndex(lower, compact.toString(), initials.toString())
        }
    }
}

internal data class ArtworkBounds(val width: Int, val height: Int)

internal fun TrackDto.toFavoriteDomain(): Track = toDomain().copy(isFavorite = true)

data class FavoriteLibraryState(
    val namespace: String? = null,
    val statuses: Map<String, Boolean> = emptyMap(),
    val pending: Set<String> = emptySet(),
    val revision: Long = 0L,
    val error: AppError? = null,
)

internal data class FavoriteMutation(
    val namespace: String,
    val trackGuid: String,
    val confirmed: Boolean,
    val desired: Boolean,
)

internal fun FavoriteLibraryState.bindNamespace(namespace: String): FavoriteLibraryState =
    if (this.namespace == namespace) this else FavoriteLibraryState(namespace = namespace)

internal fun FavoriteLibraryState.observe(trackGuid: String, favorite: Boolean): FavoriteLibraryState =
    if (trackGuid in pending) this else copy(statuses = statuses + (trackGuid to favorite))

internal fun FavoriteLibraryState.beginMutation(
    trackGuid: String,
    fallbackFavorite: Boolean,
): Pair<FavoriteLibraryState, FavoriteMutation> {
    val boundNamespace = checkNotNull(namespace)
    val confirmed = statuses[trackGuid] ?: fallbackFavorite
    val mutation = FavoriteMutation(boundNamespace, trackGuid, confirmed, !confirmed)
    return copy(
        statuses = statuses + (trackGuid to mutation.desired),
        pending = pending + trackGuid,
        error = null,
    ) to mutation
}

internal fun FavoriteLibraryState.complete(mutation: FavoriteMutation): FavoriteLibraryState =
    if (namespace != mutation.namespace) this else copy(
        statuses = statuses + (mutation.trackGuid to mutation.desired),
        pending = pending - mutation.trackGuid,
        revision = revision + 1L,
        error = null,
    )

internal fun FavoriteLibraryState.rollback(
    mutation: FavoriteMutation,
    error: AppError? = this.error,
): FavoriteLibraryState = if (namespace != mutation.namespace) this else copy(
    statuses = statuses + (mutation.trackGuid to mutation.confirmed),
    pending = pending - mutation.trackGuid,
    error = error,
)

internal fun decodeLyrics(response: LyricListDto): Pair<LyricDocument?, SyncedLyrics?> {
    val selected = response.list.firstOrNull { it.guid == response.preferred }
        ?: response.list.firstOrNull { it.isLRC }
        ?: response.list.firstOrNull()
    val document = selected?.toDomain()
    val syncedLyrics = document?.let { parseLyrics(it.content) }?.takeIf(SyncedLyrics::hasUsableLines)
    return document to syncedLyrics
}

internal fun isValidArtworkBytes(bytes: ByteArray): Boolean = isValidArtworkBytes(
    bytes = bytes,
    readBounds = { encoded ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
        ArtworkBounds(options.outWidth, options.outHeight)
    },
    decodeSampled = { encoded, sample ->
        BitmapFactory.decodeByteArray(
            encoded,
            0,
            encoded.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )?.let { bitmap ->
            bitmap.recycle()
            true
        } ?: false
    },
)

internal fun isValidArtworkBytes(
    bytes: ByteArray,
    readBounds: (ByteArray) -> ArtworkBounds?,
    decodeSampled: (ByteArray, Int) -> Boolean,
): Boolean {
    if (bytes.isEmpty() || bytes.size > MAX_ARTWORK_DOWNLOAD_BYTES) return false
    val bounds = runCatching { readBounds(bytes) }.getOrNull() ?: return false
    if (
        bounds.width !in 1..MAX_ARTWORK_EDGE ||
        bounds.height !in 1..MAX_ARTWORK_EDGE ||
        bounds.width.toLong() * bounds.height > MAX_ARTWORK_PIXELS
    ) {
        return false
    }

    var sample = 1
    while (
        bounds.width / sample > ARTWORK_VALIDATION_LONG_EDGE ||
        bounds.height / sample > ARTWORK_VALIDATION_LONG_EDGE
    ) {
        sample *= 2
    }
    return runCatching { decodeSampled(bytes, sample) }.getOrDefault(false)
}

class MusicRepository internal constructor(
    context: Context,
    private val session: SessionRepository,
    private val preferences: AppPreferences,
    private val localStore: LocalStore,
    metadataCapacityBytes: Int,
    repositoryScope: CoroutineScope,
    onlineLyricsMatcher: suspend (LyricsMatchRequest) -> LyricsMatchResult,
) {
    constructor(
        context: Context,
        session: SessionRepository,
        preferences: AppPreferences,
        localStore: LocalStore,
    ) : this(
        context = context,
        session = session,
        preferences = preferences,
        localStore = localStore,
        metadataCapacityBytes = METADATA_CAPACITY_BYTES,
        repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        onlineLyricsMatcher = defaultOnlineLyricsMatcher(),
    )

    private val responses = SerializedResponseCache(metadataCapacityBytes, repositoryScope)
    private val onlineLyricsResolver = OnlineLyricsResolver(
        localStore = localStore,
        responses = responses,
        namespace = session::cacheNamespace,
        matcher = onlineLyricsMatcher,
    )
    private val favoriteMutationMutex = Mutex()
    private val searchIndexMutex = Mutex()
    /** 全量歌手/专辑搜索索引，按登录账号缓存；登录后由 warmSearchIndex() 后台预热，搜索时按需补齐。 */
    private var searchArtists: Pair<String, List<Artist>>? = null
    private var searchAlbums: Pair<String, List<Album>>? = null
    /** 最近一次搜索的诊断信息（查询、索引规模、命中数、近似候选），用于排查“搜不到”；由 App 层写入崩溃日志文件。 */
    @Volatile
    private var lastSearchDiagnostics: String? = null
    private val _favoriteState = MutableStateFlow(FavoriteLibraryState())
    val favoriteState: StateFlow<FavoriteLibraryState> = _favoriteState.asStateFlow()
    private val artworkCache = ArtworkCache(
        root = context.cacheDir.resolve("artwork"),
        memoryCapacityBytes = ARTWORK_MEMORY_CAPACITY_BYTES,
        diskBudgetBytes = { preferences.state.value.cacheBudget.artworkBytes },
        isValid = ::isValidArtworkBytes,
        scope = repositoryScope,
    )

    init {
        repositoryScope.launch { artworkCache.initialize() }
    }

    /**
     * 返回服务器上的全部歌单。
     *
     * 默认走本地缓存（内存 + 磁盘兜底）；当 [forceRefresh] 为 true 时绕过已缓存数据，
     * 强制从服务器重新拉取并把结果重新落盘，用于“打开 App / 回到前台时同步歌单”。
     * 若此时网络不可用，仍会回退到磁盘缓存，保证离线时也能展示最近一次同步的歌单。
     */
    suspend fun playlists(forceRefresh: Boolean = false): List<Playlist> = cachedIndex<List<PlaylistDto>, List<Playlist>>(
        key = "playlists",
        forceRefresh = forceRefresh,
        fetch = { enrichPlaylistCounts(session.authenticated { it.playlists() }) },
    ) { list -> list.map(PlaylistDto::toDomain) }

    /** 歌单列表接口不返回歌曲数，逐歌单并发拉取详情补齐 trackCount；失败则保留 null（界面显示 0）。 */
    private suspend fun enrichPlaylistCounts(dtos: List<PlaylistDto>): List<PlaylistDto> {
        if (dtos.isEmpty()) return dtos
        val enriched = mutableListOf<PlaylistDto>()
        coroutineScope {
            for (chunk in dtos.chunked(6)) {
                enriched += chunk.map { dto ->
                    async {
                        runCatching { session.authenticated { it.playlist(dto.guid).trackCount } }
                            .getOrNull()?.let { count -> dto.copy(trackCount = count) } ?: dto
                    }
                }.awaitAll()
            }
        }
        return enriched
    }

    suspend fun playlist(guid: String): Playlist = cachedIndex<PlaylistDetailDto, Playlist>(
        key = "playlist:$guid",
        fetch = { session.authenticated { it.playlist(guid) } },
    ) { it.toDomain() }

    suspend fun playlistTracks(guid: String, page: Int) = cachedPage<TrackDto, Track>(
        sourceKey = "playlist:$guid",
        page = page,
        fetch = { session.authenticated { it.playlistTracks(guid, page) } },
    ) { it.toDomain() }.also(::observeFavoriteTracks)

    suspend fun artists(page: Int, size: Int = 50) = cachedPage<ArtistDto, Artist>(
        sourceKey = sizedPageSourceKey("artists", size),
        page = page,
        pageSize = size,
        fetch = { session.authenticated { it.artists(page, size) } },
    ) { it.toDomain() }

    suspend fun artist(guid: String): Artist = cachedIndex<ArtistDto, Artist>(
        key = "artist:$guid",
        fetch = { session.authenticated { it.artist(guid) } },
    ) { it.toDomain() }

    suspend fun artistTracks(guid: String, page: Int) = cachedPage<TrackDto, Track>(
        sourceKey = "artist-tracks:$guid",
        page = page,
        fetch = { session.authenticated { it.artistTracks(guid, page) } },
    ) { it.toDomain() }.also(::observeFavoriteTracks)

    suspend fun artistAlbums(guid: String, page: Int) = cachedPage<AlbumDto, Album>(
        sourceKey = "artist-albums:$guid",
        page = page,
        fetch = { session.authenticated { it.artistAlbums(guid, page) } },
    ) { it.toDomain() }

    suspend fun albums(page: Int, size: Int = 50) = cachedPage<AlbumDto, Album>(
        sourceKey = sizedPageSourceKey("albums", size),
        page = page,
        pageSize = size,
        fetch = { session.authenticated { it.albums(page, size) } },
    ) { it.toDomain() }

    suspend fun album(guid: String): Album = cachedIndex<AlbumDto, Album>(
        key = "album:$guid",
        fetch = { session.authenticated { it.album(guid) } },
    ) { it.toDomain() }

    suspend fun albumTracks(guid: String, page: Int) = cachedPage<TrackDto, Track>(
        sourceKey = "album-tracks:$guid",
        page = page,
        fetch = { session.authenticated { it.albumTracks(guid, page) } },
    ) { it.toDomain() }.also(::observeFavoriteTracks)

    suspend fun allTracks(page: Int) = cachedPage<TrackDto, Track>(
        sourceKey = "all-tracks",
        page = page,
        fetch = { session.authenticated { it.allTracks(page) } },
    ) { it.toDomain() }.also(::observeFavoriteTracks)

    suspend fun favoriteTracks(page: Int): Page<Track> {
        val namespace = session.cacheNamespace()
        val response = session.authenticated { it.favoriteTracks(page, FAVORITE_PAGE_SIZE) }
        val result = Page(
            items = response.list.map(TrackDto::toFavoriteDomain),
            page = page,
            pageSize = FAVORITE_PAGE_SIZE,
            total = response.total,
            sort = FAVORITE_SORT,
        )
        observeFavoriteTracks(result, namespace)
        return result
    }

    suspend fun toggleFavorite(trackGuid: String, fallbackFavorite: Boolean): Result<Boolean> =
        favoriteMutationMutex.withLock {
            val namespace = session.cacheNamespace()
            bindFavoriteNamespace(namespace)
            val (optimisticState, mutation) = _favoriteState.value.beginMutation(trackGuid, fallbackFavorite)
            _favoriteState.value = optimisticState
            try {
                session.authenticated { api ->
                    if (mutation.desired) api.createFavorite(trackGuid) else api.deleteFavorite(trackGuid)
                }
                _favoriteState.update { it.complete(mutation) }
                Result.success(mutation.desired)
            } catch (cause: CancellationException) {
                _favoriteState.update { it.rollback(mutation) }
                throw cause
            } catch (cause: Exception) {
                val error = (cause as? AppException)?.error ?: AppError.Unknown(cause.message)
                _favoriteState.update { it.rollback(mutation, error) }
                Result.failure(cause)
            }
        }

    fun clearFavoriteState() {
        _favoriteState.value = FavoriteLibraryState()
    }

    suspend fun queuePage(source: QueueSource, page: Int): Page<Track> = when (source) {
        is QueueSource.Playlist -> playlistTracks(source.guid, page)
        is QueueSource.Artist -> artistTracks(source.guid, page)
        is QueueSource.Album -> albumTracks(source.guid, page)
        is QueueSource.LibraryAllTracks -> allTracks(page)
        is QueueSource.Favorites -> favoriteTracks(page)
    }

    suspend fun sharedLibraries(): List<SharedLibrary> = cachedIndex<List<SharedLibraryDto>, List<SharedLibrary>>(
        key = "shared-libraries",
        fetch = { session.authenticated { it.sharedLibraries() } },
    ) { list -> list.map(SharedLibraryDto::toDomain) }

    suspend fun trackMetadata(trackGuid: String): Track = cachedIndex<TrackMetadataDto, Track>(
        key = "track-metadata:$trackGuid",
        fetch = { session.authenticated { it.metadata(trackGuid) } },
    ) { it.toDomain() }.also(::observeFavoriteTrack)

    suspend fun currentTrackMetadata(trackGuid: String): CurrentResourceResult<Track> = currentResource {
        withCurrentResourceRetry { trackMetadata(trackGuid) }
    }

    suspend fun prepare(track: Track): PlaybackTrack {
        if (track.accessStatus != null && track.accessStatus != 0) throw AppException(AppError.UnavailableTrack)
        val refreshed = trackMetadata(track.guid.value)
        if (refreshed.isCue) throw AppException(AppError.TranscodeUnavailable)
        val api = session.requireApi()
        return PlaybackTrack(
            refreshed,
            api.streamUrl(refreshed.guid.value).toString(),
            refreshed.coverId?.let { api.coverUrl(it, CoverVariant.Player.width).toString() },
        )
    }

    fun prepareQueue(tracks: List<Track>): List<PlaybackTrack> {
        val api = session.requireApi()
        return tracks.asSequence()
            .filter { it.accessStatus == null || it.accessStatus == 0 }
            .filterNot(Track::isCue)
            .take(250)
            .map { track ->
                PlaybackTrack(
                    track,
                    api.streamUrl(track.guid.value).toString(),
                    track.coverId?.let { api.coverUrl(it, CoverVariant.Player.width).toString() },
                )
            }
            .toList()
    }

    suspend fun lyrics(trackGuid: String): Pair<LyricDocument?, SyncedLyrics?> =
        decodeLyrics(lyricResponse(trackGuid))

    private suspend fun firstPartyCurrentLyrics(trackGuid: String): CurrentResourceResult<CurrentLyrics> = currentResource {
        val (document, syncedLyrics) = withCurrentResourceRetry { lyrics(trackGuid) }
        document?.takeIf { it.content.isNotBlank() }?.let { CurrentLyrics(it, syncedLyrics) }
    }

    suspend fun currentLyrics(
        track: Track,
        onlineMatchingEnabled: Boolean = preferences.state.value.onlineLyricsMatchingEnabled,
    ): CurrentResourceResult<CurrentLyrics> = resolveLyricsWithFallback(
        onlineMatchingEnabled = onlineMatchingEnabled,
        online = { onlineLyricsResolver.resolve(track) },
        firstParty = { firstPartyCurrentLyrics(track.guid.value) },
    )

    suspend fun startRoam(): RoamWindow? {
        val namespace = session.cacheNamespace()
        return session.authenticated { it.roamStart(session.deviceId) }?.let {
            RoamWindow(null, it.current.toDomain(), it.next?.toDomain())
        }.also { observeFavoriteWindow(it, namespace) }
    }

    suspend fun nextRoam(roamId: String): RoamWindow {
        val namespace = session.cacheNamespace()
        return session.authenticated { it.roamNext(session.deviceId, roamId).toDomain() }
            .also { observeFavoriteWindow(it, namespace) }
    }

    suspend fun previousRoam(roamId: String): RoamWindow {
        val namespace = session.cacheNamespace()
        return session.authenticated { it.roamPrevious(session.deviceId, roamId).toDomain() }
            .also { observeFavoriteWindow(it, namespace) }
    }

    suspend fun artwork(coverId: String, variant: CoverVariant): ByteArray? = try {
        loadArtwork(coverId, variant)
    } catch (cause: CancellationException) {
        throw cause
    } catch (_: AppException) {
        null
    }

    suspend fun currentArtwork(
        coverId: String,
        variant: CoverVariant,
    ): CurrentResourceResult<ByteArray> = currentResource {
        withCurrentResourceRetry { loadArtwork(coverId, variant) }
    }

    suspend fun invalidateNamespace(namespace: String, includeEssential: Boolean = false) {
        responses.invalidateNamespace(namespace)
        artworkCache.clearNamespace(namespace)
        localStore.clearNamespace(namespace, includeEssential)
    }

    suspend fun clearLocalNamespace(includeEssential: Boolean) {
        val namespace = runCatching(session::cacheNamespace).getOrNull() ?: return
        invalidateNamespace(namespace, includeEssential)
    }

    suspend fun clearArtwork() = artworkCache.clearAll()

    suspend fun clearAllEvictableCaches() {
        responses.invalidateAll()
        artworkCache.clearAll()
        localStore.clearAllEvictable()
    }

    suspend fun cacheUsage(): CacheUsage = CacheUsage(
        artworkBytes = artworkCache.usageBytes(),
        indexBytes = localStore.physicalBytes(),
    )

    suspend fun applyArtworkBudget() = artworkCache.applyBudget()

    /**
     * 客户端本地搜索：服务端 API 未提供搜索端点。
     * 歌手、专辑、歌单数量少，登录后后台建立全量内存索引（warmSearchIndex），搜索时直接过滤，保证完整覆盖；
     * 歌曲数量大，采用“流式分页匹配”——逐页拉取并即时匹配、只保留命中项，内存占用只与命中数量有关。
     * 歌曲扫描带自适应回退：先用大分页（200 条/页）快速扫描；若服务端在该分页大小下分页异常（整页重复），
     * 自动改用与浏览页相同的小分页（50 条/页）重扫，保证覆盖完整。
     * 匹配规则（不区分大小写）：
     *  1) 名称/标题直接包含关键词（支持中文、英文）；
     *  2) 关键词为中文时，也按其中文对应的拼音匹配（如“小虎”匹配“小虎队”的拼音 xiaohudui）；
     *  3) 关键词为拼音全拼（如 xiaohu）或拼音首字母（如 xhd）时，匹配中文名称的全拼/首字母。
     * 结果按“前缀优先、其次包含”排序。
     */
    suspend fun search(query: String): SearchResults {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return SearchResults()
        return try {
            withContext(Dispatchers.Default) {
                ensureSearchIndex()
                val matcher = SearchMatcher(q)
                val artistIndex = searchArtists ?: ("" to emptyList())
                val albumIndex = searchAlbums ?: ("" to emptyList())
                val trackHits = scanTracks(matcher)
                val artistHits = artistIndex.second.filter { matcher.matchesArtist(it) }
                val albumHits = albumIndex.second.filter { matcher.matchesAlbum(it) }
                val playlistHits = playlists().filter { matcher.matchesName(it.name) }
                lastSearchDiagnostics = searchDiagnostics(
                    q = q,
                    artistIndex = artistIndex,
                    albumIndex = albumIndex,
                    trackHits = trackHits,
                    artistHits = artistHits,
                    albumHits = albumHits,
                    playlistHits = playlistHits,
                )
                SearchResults(
                    tracks = trackHits.sortedWith(matcher.trackRank),
                    artists = artistHits.sortedWith(matcher.artistRank),
                    albums = albumHits.sortedWith(matcher.albumRank),
                    playlists = playlistHits.sortedWith(matcher.playlistRank),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            SearchResults()
        }
    }

    /** 最近一次搜索的诊断信息；供 App 层写入崩溃日志文件，方便反馈“为什么搜不到”。 */
    fun lastSearchDiagnostics(): String? = lastSearchDiagnostics

    /** 登录后后台预热搜索索引（歌手、专辑全量载入内存），幂等；账号切换时自动重建。 */
    suspend fun warmSearchIndex() {
        withContext(Dispatchers.Default) { ensureSearchIndex() }
    }

    private suspend fun ensureSearchIndex() {
        val namespace = session.cacheNamespace()
        searchIndexMutex.withLock {
            if (searchArtists?.first == namespace && searchAlbums?.first == namespace) return
            searchArtists = namespace to streamCatalog(
                pages = { page -> artists(page, SEARCH_INDEX_PAGE_SIZE) },
                idOf = { it.guid.value },
                matches = { true },
            ).items
            searchAlbums = namespace to streamCatalog(
                pages = { page -> albums(page, SEARCH_INDEX_PAGE_SIZE) },
                idOf = { it.guid.value },
                matches = { true },
            ).items
        }
    }

    /** 歌曲搜索：先用大分页快速扫描，若服务端在该分页大小下分页异常，回退到浏览页验证过的 50 条/页重扫。 */
    private suspend fun scanTracks(matcher: SearchMatcher): List<Track> {
        val fast = streamCatalog(
            pages = { page -> searchTracksPage(page, SEARCH_PAGE_SIZE) },
            idOf = { it.guid.value },
            matches = matcher::matchesTrack,
        )
        if (fast.complete) return fast.items
        return streamCatalog(
            pages = { page -> searchTracksPage(page, SEARCH_INDEX_PAGE_SIZE) },
            idOf = { it.guid.value },
            matches = matcher::matchesTrack,
        ).items
    }

    /** 搜索专用歌曲分页；缓存键含分页大小，避免不同分页大小的缓存互相污染。 */
    private suspend fun searchTracksPage(page: Int, size: Int): Page<Track> = cachedPage<TrackDto, Track>(
        sourceKey = sizedPageSourceKey("search-tracks", size),
        page = page,
        pageSize = size,
        fetch = { session.authenticated { it.allTracks(page, size) } },
    ) { it.toDomain() }.also(::observeFavoriteTracks)

    /** 流式扫描结果：items 为命中项；complete=false 表示因“整页重复（分页未前进）”提前结束，可回退重扫。 */
    private data class CatalogScanResult<T>(val items: List<T>, val complete: Boolean)

    /**
     * 流式扫描分页目录：逐页拉取、即时匹配、只保留命中项，内存占用只与命中数量有关。
     * 终止条件：拉取量达到服务端 total、遇到空页、整页均为已见重复项（服务端分页未前进）或超过安全上限。
     * 注意：不能用“相邻页首元素相同即停”这类启发式，正常分页服务在特定排序下也会命中该情况，导致只扫第一页。
     */
    private suspend fun <T> streamCatalog(
        pages: suspend (Int) -> Page<T>,
        idOf: (T) -> String,
        matches: (T) -> Boolean,
    ): CatalogScanResult<T> {
        val out = ArrayList<T>()
        val seen = HashSet<String>()
        var page = 0
        var fetched = 0
        var total = Int.MAX_VALUE
        var stuck = false
        while (page < SEARCH_MAX_PAGES && fetched < total) {
            val result = pages(page)
            val items = result.items
            if (items.isEmpty()) break
            total = result.total
            fetched += items.size
            var newlySeen = 0
            for (item in items) {
                if (seen.add(idOf(item))) {
                    newlySeen += 1
                    if (matches(item)) out.add(item)
                }
            }
            // 整页都是重复项：服务端分页没有前进，停止扫描，避免无限循环
            if (newlySeen == 0) {
                stuck = true
                break
            }
            page += 1
        }
        return CatalogScanResult(out, complete = !stuck)
    }

    /** 生成搜索诊断文本：查询、索引规模、命中数，以及“可能想找”的近似歌手（首字母前缀接近）。 */
    private fun searchDiagnostics(
        q: String,
        artistIndex: Pair<String, List<Artist>>,
        albumIndex: Pair<String, List<Album>>,
        trackHits: List<Track>,
        artistHits: List<Artist>,
        albumHits: List<Album>,
        playlistHits: List<Playlist>,
    ): String = buildString {
        appendLine("查询：\"$q\"")
        appendLine("歌手索引 ${artistIndex.second.size} 条；专辑索引 ${albumIndex.second.size} 条")
        appendLine("命中：歌曲 ${trackHits.size}、歌手 ${artistHits.size}、专辑 ${albumHits.size}、歌单 ${playlistHits.size}")
        if (q.isNotBlank() && artistHits.isEmpty()) {
            val qInit = PinyinIndex.of(q).initials
            val prefix = qInit.take(2)
            val near = artistIndex.second.asSequence()
                .filter { it.name.isNotBlank() }
                .filter { PinyinIndex.of(it.name.lowercase()).initials.startsWith(prefix) }
                .take(10)
                .map { "${it.name}(${PinyinIndex.of(it.name.lowercase()).initials})" }
                .toList()
            if (near.isNotEmpty()) {
                appendLine("首字母前缀为\"$prefix\"的歌手（最多 10 条）：${near.joinToString("、")}")
            } else {
                appendLine("索引中没有首字母前缀为\"$prefix\"的歌手；请确认该歌手是否在曲库中")
            }
        }
    }

    private fun observeFavoriteTracks(page: Page<Track>) {
        page.items.forEach(::observeFavoriteTrack)
    }

    private fun observeFavoriteTracks(page: Page<Track>, namespace: String) {
        page.items.forEach { observeFavoriteTrack(it, namespace) }
    }

    private fun observeFavoriteWindow(window: RoamWindow?, namespace: String) {
        window ?: return
        listOfNotNull(window.previous, window.current, window.next)
            .forEach { observeFavoriteTrack(it.track, namespace) }
    }

    private fun observeFavoriteTrack(track: Track) {
        val namespace = runCatching(session::cacheNamespace).getOrNull() ?: return
        observeFavoriteTrack(track, namespace)
    }

    private fun observeFavoriteTrack(track: Track, namespace: String) {
        if (runCatching(session::cacheNamespace).getOrNull() != namespace) return
        bindFavoriteNamespace(namespace)
        _favoriteState.update { it.observe(track.guid.value, track.isFavorite) }
    }

    private fun bindFavoriteNamespace(namespace: String) {
        if (_favoriteState.value.namespace != namespace) {
            _favoriteState.update { it.bindNamespace(namespace) }
        }
    }

    private suspend fun loadArtwork(coverId: String, variant: CoverVariant): ByteArray? {
        val namespace = session.cacheNamespace()
        return artworkCache.get(namespace, coverId, variant) {
            session.authenticated { it.cover(coverId, variant.width) }
        }
    }

    private suspend fun lyricResponse(trackGuid: String): LyricListDto {
        val namespace = session.cacheNamespace()
        val key = ResponseCacheKey(namespace, "lyric", trackGuid)
        var decodedResponse: LyricListDto? = null
        val payload = responses.getOrFetch(
            key = key,
            persist = { encoded ->
                bestEffort {
                    localStore.saveLyric(CachedLyricEntity(namespace, trackGuid, encoded, now()))
                }
            },
        ) {
            try {
                session.authenticated { it.lyrics(trackGuid) }
                    .also { decodedResponse = it }
                    .let(ApiDecoder.json::encodeToString)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: AppException) {
                if (cause.error != AppError.NetworkUnavailable) throw cause
                val cached = fallback { localStore.lyric(namespace, trackGuid) } ?: throw cause
                decodedResponse = validatePayload<LyricListDto>(cached.payload) ?: throw cause
                cached.payload
            }
        }
        return decodedResponse ?: ApiDecoder.json.decodeFromString(payload)
    }

    private suspend inline fun <reified Dto, Domain> cachedPage(
        sourceKey: String,
        page: Int,
        pageSize: Int = PAGE_SIZE,
        crossinline fetch: suspend () -> SortedPageListDto<Dto>,
        noinline transform: (Dto) -> Domain,
    ): Page<Domain> {
        val namespace = session.cacheNamespace()
        val key = ResponseCacheKey(namespace, "page", sourceKey, page)
        var decodedResponse: SortedPageListDto<Dto>? = null
        val payload = responses.getOrFetch(
            key = key,
            persist = { encoded ->
                bestEffort {
                    val response = decodedResponse
                        ?: ApiDecoder.json.decodeFromString<SortedPageListDto<Dto>>(encoded)
                    localStore.savePage(
                        CachedPageEntity(
                            namespace = namespace,
                            sourceKey = sourceKey,
                            page = page,
                            payload = encoded,
                            total = response.total,
                            sort = response.sort,
                            accessedAt = now(),
                        ),
                    )
                }
            },
        ) {
            try {
                fetch().also { decodedResponse = it }.let(ApiDecoder.json::encodeToString)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: AppException) {
                if (cause.error != AppError.NetworkUnavailable) throw cause
                val cached = fallback { localStore.page(namespace, sourceKey, page) } ?: throw cause
                decodedResponse = validatePayload<SortedPageListDto<Dto>>(cached.payload) ?: throw cause
                cached.payload
            }
        }
        return (decodedResponse ?: ApiDecoder.json.decodeFromString<SortedPageListDto<Dto>>(payload))
            .toDomainPage(page, pageSize, transform)
    }

    private suspend inline fun <reified Dto, Domain> cachedIndex(
        key: String,
        forceRefresh: Boolean = false,
        crossinline fetch: suspend () -> Dto,
        transform: (Dto) -> Domain,
    ): Domain {
        val namespace = session.cacheNamespace()
        val cacheKey = ResponseCacheKey(namespace, "index", key)
        var decodedResponse: Dto? = null
        val payload = responses.getOrFetch(
            key = cacheKey,
            isRetainedValid = { !forceRefresh },
            persist = { encoded ->
                bestEffort {
                    localStore.saveIndex(CachedIndexEntity(namespace, key, encoded, now()))
                }
            },
        ) {
            try {
                fetch().also { decodedResponse = it }.let(ApiDecoder.json::encodeToString)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: AppException) {
                if (cause.error != AppError.NetworkUnavailable) throw cause
                val cached = fallback { localStore.index(namespace, key) } ?: throw cause
                decodedResponse = validatePayload<Dto>(cached.payload) ?: throw cause
                cached.payload
            }
        }
        return transform(decodedResponse ?: ApiDecoder.json.decodeFromString(payload))
    }

    private suspend fun <T> currentResource(block: suspend () -> T?): CurrentResourceResult<T> = try {
        block()?.let { CurrentResourceResult.Ready(it) } ?: CurrentResourceResult.Absent
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: AppException) {
        when (cause.error) {
            AppError.NotFound, AppError.Empty -> CurrentResourceResult.Absent
            else -> CurrentResourceResult.Failure(cause.error, cause.isRetryableRequestFailure)
        }
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            // Room remains a fallback tier; a disk write failure must not fail a valid network response.
        }
    }

    private suspend fun <T> fallback(block: suspend () -> T): T? = try {
        block()
    } catch (cause: CancellationException) {
        throw cause
    } catch (_: Exception) {
        null
    }

    private inline fun <reified T> validatePayload(payload: String): T? = try {
        ApiDecoder.json.decodeFromString(payload)
    } catch (_: Exception) {
        null
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val METADATA_CAPACITY_BYTES = 8 * 1024 * 1024
        const val ARTWORK_MEMORY_CAPACITY_BYTES = 24 * 1024 * 1024
        const val PAGE_SIZE = 50
        const val FAVORITE_PAGE_SIZE = 50
        const val FAVORITE_SORT = "favoriteAt,desc"

        fun defaultOnlineLyricsMatcher(): suspend (LyricsMatchRequest) -> LyricsMatchResult {
            val client = okhttp3.OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .connectTimeout(1_200L, TimeUnit.MILLISECONDS)
                .readTimeout(1_800L, TimeUnit.MILLISECONDS)
                .writeTimeout(1_800L, TimeUnit.MILLISECONDS)
                .build()
            val coordinator = LyricsMatchCoordinator(DefaultLyricsSources.create(client))
            return coordinator::match
        }
    }
}

internal fun sizedPageSourceKey(sourceKey: String, size: Int): String {
    require(size > 0)
    return "$sourceKey:size=$size"
}

internal fun <Dto, Domain> SortedPageListDto<Dto>.toDomainPage(
    page: Int,
    pageSize: Int,
    transform: (Dto) -> Domain,
): Page<Domain> {
    require(pageSize > 0)
    return Page(list.map(transform), page, pageSize, total, sort)
}

internal suspend fun resolveLyricsWithFallback(
    onlineMatchingEnabled: Boolean,
    online: suspend () -> CurrentLyrics?,
    firstParty: suspend () -> CurrentResourceResult<CurrentLyrics>,
): CurrentResourceResult<CurrentLyrics> {
    if (!onlineMatchingEnabled) return firstParty()
    return try {
        online()?.let { CurrentResourceResult.Ready(it) } ?: firstParty()
    } catch (cause: CancellationException) {
        throw cause
    } catch (_: Exception) {
        firstParty()
    }
}
