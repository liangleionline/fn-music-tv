package com.fnmusic.tv.core.model

@JvmInline value class ServerGuid(val value: String)
@JvmInline value class UserGuid(val value: String)
@JvmInline value class TrackGuid(val value: String)
@JvmInline value class CollectionGuid(val value: String)

data class ServerIdentity(
    val guid: ServerGuid,
    val name: String,
    val serverVersion: String,
    val mediaServerVersion: String,
)

data class User(
    val guid: UserGuid,
    val username: String,
    val nickname: String?,
)

data class Playlist(
    val guid: CollectionGuid,
    val name: String,
    val coverId: String?,
    val trackCount: Int? = null,
)

data class Artist(
    val guid: CollectionGuid,
    val name: String,
    val coverId: String?,
    val trackCount: Int? = null,
    val albumCount: Int? = null,
)

data class Album(
    val guid: CollectionGuid,
    val name: String,
    val artistName: String?,
    val coverId: String?,
    val trackCount: Int? = null,
    val releaseDate: String? = null,
    /** 合并后的同名专辑在服务端仍可能是多条（每条一个艺术家）；这里保留全部 guid，详情页据此取回全部曲目。 */
    val guids: List<CollectionGuid> = emptyList(),
) {
    /** 详情页要拉取的服务端专辑 guid 集合（未合并时为自身 guid）。 */
    val sourceGuids: List<CollectionGuid> get() = guids.ifEmpty { listOf(guid) }
}

data class Track(
    val guid: TrackGuid,
    val title: String,
    val artistName: String?,
    val albumName: String?,
    val coverId: String?,
    val durationMs: Long?,
    val isCue: Boolean,
    val accessStatus: Int? = null,
    val audioFormat: String? = null,
    val isFavorite: Boolean = false,
    val folderPath: String? = null,
    /** 碟号（多碟专辑的 CD 序号）；0 表示没有碟号标签。 */
    val discNo: Int = 0,
    /** 碟内曲目号；0 表示没有曲目号标签。 */
    val trackNo: Int = 0,
)

data class SharedLibrary(
    val guid: CollectionGuid,
    val name: String,
    val accessStatus: Int,
    val updatedAt: Long,
)

data class Page<T>(
    val items: List<T>,
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val sort: String,
) {
    val hasNext: Boolean get() = page * pageSize < total
}

data class LyricDocument(
    val guid: String,
    val content: String,
    val isLrc: Boolean,
    val offsetMs: Long,
)

data class PlaybackCredentials(
    val apiBase: String,
    val rawAuthorization: String,
    val cacheNamespace: String,
    val accessCodeHeader: String? = null,
    val relayMode: Boolean = false,
)

data class PlaybackTrack(
    val track: Track,
    val streamUrl: String,
    val artworkUrl: String?,
)

data class RoamNode(val roamId: String, val track: Track)
data class RoamWindow(val previous: RoamNode?, val current: RoamNode, val next: RoamNode?)

enum class PlayerStyle { Cover, Poster }

enum class CoverVariant(val width: Int?) {
    Compact(200),
    Grid(400),
    Player(800),
    Poster(null),
}
