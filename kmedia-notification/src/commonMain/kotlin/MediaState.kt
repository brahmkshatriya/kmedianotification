package dev.brahmkshatriya.kmedianotification

import kotlin.time.Duration
import kotlin.time.TimeSource

public enum class MediaKind { Audio, Video }
public enum class RepeatMode { Off, All, One }
public enum class PlaybackPhase { Idle, Preparing, Buffering, Ready, Ended, Failed }
public enum class MediaAction {
    Play, Pause, TogglePlayPause, Stop, Next, Previous, SeekTo, SeekBy,
    SetVolume, SetRate, SetShuffle, SetRepeatMode, SkipToEntry, Raise, Quit, Custom, SetLiked,
}
public enum class MediaFeature { Artwork, Timeline, Queue, CustomButtons, LiveTimeline }

/** Content identity and queue-occurrence identity are deliberately independent. */
public data class NowPlayingItem(
    val mediaId: String,
    val entryId: String = mediaId,
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val artworkUri: String? = null,
    val kind: MediaKind = MediaKind.Audio,
    val artwork: MediaArtwork? = null,
) {
    init { require(mediaId.isNotBlank()); require(entryId.isNotBlank()) }
}

/** Copy this sample unchanged when only metadata changes. The clock is monotonic. */
public data class PositionSample(
    val position: Duration,
    val sampledAt: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow(),
    val discontinuity: Long = 0,
) {
    init { require(position.isFinite() && position >= Duration.ZERO) }
}

public data class SeekableRange(val start: Duration, val end: Duration) {
    init { require(start.isFinite() && end.isFinite() && start >= Duration.ZERO && end >= start) }
}

public data class PlaybackState(
    val playWhenReady: Boolean = false,
    val phase: PlaybackPhase = PlaybackPhase.Idle,
    val position: PositionSample = PositionSample(Duration.ZERO),
    val duration: Duration? = null,
    val speed: Double = 1.0,
    /** False during suppression, even if playWhenReady and phase remain unchanged. */
    val isAdvancing: Boolean = playWhenReady && phase == PlaybackPhase.Ready,
    val bufferedPosition: Duration? = null,
    val seekableRange: SeekableRange? = duration?.let { SeekableRange(Duration.ZERO, it) },
    val isLive: Boolean = false,
) {
    init {
        require(duration == null || (duration.isFinite() && duration >= Duration.ZERO))
        require(speed.isFinite() && speed > 0)
        require(!isAdvancing || (playWhenReady && phase == PlaybackPhase.Ready))
        require(bufferedPosition == null || (bufferedPosition.isFinite() && bufferedPosition >= Duration.ZERO))
        require(duration == null || seekableRange == null || seekableRange.end <= duration)
    }
    public fun positionNow(): Duration {
        val value = position.position + if (isAdvancing) position.sampledAt.elapsedNow() * speed else Duration.ZERO
        return value.coerceAtLeast(Duration.ZERO).let { if (duration != null) it.coerceAtMost(duration) else it }
    }
    public companion object {
        public fun playing(position: Duration, duration: Duration? = null, speed: Double = 1.0): PlaybackState =
            PlaybackState(true, PlaybackPhase.Ready, PositionSample(position), duration, speed)
        public fun paused(position: Duration, duration: Duration? = null, speed: Double = 1.0): PlaybackState =
            PlaybackState(false, PlaybackPhase.Ready, PositionSample(position), duration, speed)
    }
}

public data class MediaSessionState(
    val item: NowPlayingItem? = null,
    val playback: PlaybackState = PlaybackState(),
    val queue: List<NowPlayingItem> = emptyList(),
    /** Null means all registered handlers; an explicit set narrows availability. */
    val availableActions: Set<MediaAction>? = null,
    /** Ordered preferences. Unsupported buttons are removed; unavailable ones can opt to stay disabled. */
    val buttons: List<MediaButton> = listOf(MediaButton.Previous, MediaButton.PlayPause, MediaButton.Next),
    val volume: Double = 1.0,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.Off,
    /** Null means liking is unavailable for this item. */
    val liked: Boolean? = null,
) {
    init {
        require(volume.isFinite() && volume in 0.0..1.0)
        if (queue !is QueueSnapshot) {
            val ids = HashSet<String>(queue.size)
            require(queue.all { ids.add(it.entryId) }) { "Queue entry IDs must be unique" }
        }
        val customIds = buttons.filterIsInstance<MediaButton.Custom>().map { it.id }
        require(customIds.distinct().size == customIds.size) { "Custom button IDs must be unique" }
        if (item != null && queue.isNotEmpty()) {
            require(if (queue is QueueSnapshot) item.entryId in queue.entryIds else queue.any { it.entryId == item.entryId }) {
                "The current occurrence must be present in the published queue"
            }
        }
    }
}

/** Reuse validated, detached queues across position-only snapshots without exposing a second queue API. */
internal class QueueSnapshot(source: List<NowPlayingItem>) : AbstractList<NowPlayingItem>() {
    private val items = source.map { it.copy(artists = it.artists.toList()) }
    val entryIds: Set<String> = items.mapTo(HashSet(items.size)) { it.entryId }
    init { require(entryIds.size == items.size) { "Queue entry IDs must be unique" } }
    override val size: Int get() = items.size
    override fun get(index: Int): NowPlayingItem = items[index]
}
