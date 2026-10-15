package com.fnmusic.tv.core.model.preferences

import com.fnmusic.tv.core.model.PlayerStyle

enum class CacheBudget(val megabytes: Int) {
    Small(32), Medium(64), Default(128), Large(256);

    val artworkBytes: Long get() = megabytes * 1024L * 1024L
}

data class AppPreferencesState(
    val playerStyle: PlayerStyle = PlayerStyle.Cover,
    val cacheBudget: CacheBudget = CacheBudget.Default,
    val onlineLyricsMatchingEnabled: Boolean = true,
    /** 用户自定义歌单排序（歌单 guid 列表，按展示先后排列），空表示沿用服务器默认顺序。 */
    val playlistOrder: List<String> = emptyList(),
    /** 按返回键退出时是否在后台继续播放。 */
    val backgroundContinueOnExit: Boolean = false,
)

data class CacheUsage(
    val artworkBytes: Long,
    val indexBytes: Long = 0,
) {
    val totalBytes: Long get() = artworkBytes + indexBytes
}
