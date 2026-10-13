package to.sava.legirss.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import to.sava.legirss.data.LegirssStore
import to.sava.legirss.feed.FeedFetcher
import to.sava.legirss.feed.FeedParser
import to.sava.legirss.feed.FeedText
import to.sava.legirss.feed.FetchFailure
import to.sava.legirss.feed.FetchRequest
import to.sava.legirss.feed.FetchResult
import to.sava.legirss.model.ChannelError
import to.sava.legirss.model.Retention
import to.sava.legirss.model.UpdateTarget

/** 実行中の更新の進み具合。 */
data class UpdateProgress(
    val total: Int,
    val finished: Int = 0,
    /** 未読として新しく登録した記事の数。 */
    val newArticles: Int = 0,
    val failed: Int = 0,
)

/** チャンネルのフィードを取得して取り込む。 */
class FeedUpdater(
    private val store: LegirssStore,
    private val fetcher: FeedFetcher,
    /** フィードの解釈に使う。 */
    private val parseDispatcher: CoroutineDispatcher,
    private val now: () -> Long,
    /** 想定していない失敗を、説明と原因を添えて記録する。 */
    private val reportError: (String, Throwable) -> Unit,
    private val parallelism: Int = DEFAULT_PARALLELISM,
) {
    private val mutableProgress = MutableStateFlow<UpdateProgress?>(null)

    /** 実行中の更新の進み具合。実行していなければ null。 */
    val progress: StateFlow<UpdateProgress?> = mutableProgress.asStateFlow()

    private val mutableUpdatingChannelIds = MutableStateFlow<Set<Long>>(emptySet())

    /** いま取得や取り込みをしている最中のチャンネル。 */
    val updatingChannelIds: StateFlow<Set<Long>> = mutableUpdatingChannelIds.asStateFlow()

    /**
     * 購読中のチャンネルを更新する。[channelIds] が null でなければ、その中のものに限る。
     *
     * @return 終わったときの進み具合。
     */
    suspend fun update(channelIds: Collection<Long>?): UpdateProgress {
        val targets = store.loadUpdateTargets(channelIds)
        val retention = store.loadRetention()
        mutableProgress.value = UpdateProgress(total = targets.size)
        try {
            val permits = Semaphore(parallelism)
            coroutineScope {
                targets.map { target ->
                    async {
                        val outcome = permits.withPermit { updateChannelOrReport(target, retention) }
                        mutableProgress.update { it?.plus(outcome) }
                    }
                }.awaitAll()
            }
            return mutableProgress.value ?: UpdateProgress(total = targets.size)
        } finally {
            mutableProgress.value = null
        }
    }

    /** チャンネルを更新する。想定していない失敗は記録して、そのチャンネルの失敗として扱う。 */
    private suspend fun updateChannelOrReport(target: UpdateTarget, retention: Retention): Outcome = try {
        mutableUpdatingChannelIds.update { it + target.channelId }
        updateChannel(target, retention)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (exception: Exception) {
        // 1 つのチャンネルで何が起きても、ほかのチャンネルの更新は続ける。そのため例外の種類を絞らない。
        reportError("チャンネルを更新できません: ${target.feedUrl}", exception)
        Outcome.Failed
    } finally {
        mutableUpdatingChannelIds.update { it - target.channelId }
    }

    private suspend fun updateChannel(target: UpdateTarget, retention: Retention): Outcome {
        val request = FetchRequest(target.feedUrl, target.etag, target.lastModified)
        return when (val result = fetcher.fetch(request)) {
            is FetchResult.NotModified -> {
                store.recordNotModified(target, result.permanentUrl ?: target.feedUrl, retention, now())
                Outcome.Succeeded(newArticles = 0)
            }

            is FetchResult.Fetched -> applyFetched(target, result, retention)

            is FetchResult.Failed -> {
                val error = when (result.kind) {
                    FetchFailure.NO_RESPONSE -> ChannelError.NO_RESPONSE
                    FetchFailure.HTTP_ERROR -> ChannelError.HTTP_ERROR
                }
                recordFailure(target, error, result.detail, result.gone)
            }
        }
    }

    private suspend fun applyFetched(target: UpdateTarget, result: FetchResult.Fetched, retention: Retention): Outcome {
        val feedUrl = result.permanentUrl ?: target.feedUrl
        val feed = withContext(parseDispatcher) { FeedParser.parse(FeedText.decode(result.body, result.charset), feedUrl) }
            ?: return recordFailure(target, ChannelError.NOT_A_FEED, detail = "フィードとして読めません", gone = false)
        val added = store.applyFetchedFeed(target, feedUrl, feed, result.etag, result.lastModified, retention, now())
        return Outcome.Succeeded(added)
    }

    private suspend fun recordFailure(target: UpdateTarget, error: ChannelError, detail: String, gone: Boolean): Outcome {
        val time = now()
        val gonePolicy = GonePolicy.next(target, gone, time)
        store.recordFetchFailure(target, error, detail, gonePolicy.count, gonePolicy.at, gonePolicy.pause, time)
        return Outcome.Failed
    }

    private sealed interface Outcome {
        data class Succeeded(val newArticles: Int) : Outcome

        data object Failed : Outcome
    }

    private operator fun UpdateProgress.plus(outcome: Outcome): UpdateProgress = when (outcome) {
        is Outcome.Succeeded -> copy(finished = finished + 1, newArticles = newArticles + outcome.newArticles)
        Outcome.Failed -> copy(finished = finished + 1, failed = failed + 1)
    }

    companion object {
        const val DEFAULT_PARALLELISM = 8
    }
}

/**
 * フィードが無くなったことを示す応答が続いたチャンネルを、購読中止にする判定。
 *
 * 短い間隔で繰り返し更新しても数えすぎないよう、数えるのは一定の間隔を空けた応答だけにする。
 */
data class GonePolicy(
    val count: Int,
    val at: Long?,
    val pause: Boolean,
) {
    companion object {
        const val PAUSE_THRESHOLD = 7
        const val COUNT_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L

        fun next(target: UpdateTarget, gone: Boolean, now: Long): GonePolicy {
            val countable = gone && (target.goneAt == null || now - target.goneAt >= COUNT_INTERVAL_MILLIS)
            if (!countable) return GonePolicy(target.goneCount, target.goneAt, pause = false)
            val count = target.goneCount + 1
            return GonePolicy(count, now, pause = count >= PAUSE_THRESHOLD)
        }
    }
}
