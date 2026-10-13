package to.sava.legirss.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.Path

/** アプリの更新の状況。 */
internal sealed interface UpdateState {
    /** 入れられる新しい版は無く、出す通知も無い。まだ確かめていない場合も含む。 */
    data object Idle : UpdateState

    /** 新しい版がある。 */
    data class Available(val release: UpdateRelease) : UpdateState

    /** 新しい版の配布物を取得して、入れる準備をしている。 */
    data class Installing(val release: UpdateRelease) : UpdateState

    /** 新しい版を入れられなかった。もう一度試せる。 */
    data class Failed(val release: UpdateRelease) : UpdateState

    /** 利用者の求めで、新しい版があるかを確かめている。 */
    data object Checking : UpdateState

    /** 利用者の求めで確かめた結果、新しい版は無かった。しばらくすると [Idle] になる。 */
    data object UpToDate : UpdateState

    /** 利用者の求めで確かめようとして、確かめられなかった。しばらくすると、確かめる前の状況に戻る。 */
    data object CheckFailed : UpdateState
}

/**
 * 新しい版があるかを、定期的に、または利用者の求めで確かめ、求められたら入れる。
 *
 * @param canInstall 新しい版を入れられる動かし方をしているか。
 * @param download 新しい版の配布物を取得し、確かめたうえで、置いた場所を返す。できなければ [IOException] を投げる。
 * @param launch 配布物を入れる作業を、アプリの外へ引き渡す。できなければ [IOException] を投げる。
 * @param reportError 入れられなかった理由を記録する。
 * @param onReadyToExit 入れる作業を引き渡したあとに呼ぶ。ここでアプリを終了させる。
 */
internal class AppUpdater(
    private val checker: UpdateChecker,
    private val canInstall: Boolean,
    private val download: suspend (UpdateRelease) -> Path,
    private val launch: (Path) -> Unit,
    private val scope: CoroutineScope,
    private val reportError: (String, Throwable) -> Unit,
    private val onReadyToExit: () -> Unit,
) {
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    /** 新しい版があるかを 1 回確かめる。入れている最中と、利用者の求めで確かめている最中は、その状況を変えない。 */
    suspend fun check() {
        val check = checker.check()
        if (mutableState.value is UpdateState.Installing || mutableState.value is UpdateState.Checking) return
        mutableState.value = when (check) {
            is UpdateCheck.Available -> UpdateState.Available(check.release)
            // 確かめられなかったときは、分かっている状況をそのままにして次の機会を待つ。
            is UpdateCheck.Failed -> return
            UpdateCheck.UpToDate -> UpdateState.Idle
        }
    }

    /** [intervalMillis] ごとに確かめ続ける。呼び出したコルーチンが取り消されるまで戻らない。入れられない動かし方なら何もしない。 */
    suspend fun runPeriodicChecks(intervalMillis: Long) {
        if (!canInstall) return
        while (true) {
            check()
            delay(intervalMillis)
        }
    }

    /**
     * 利用者の求めで、新しい版があるかを確かめる。確かめている間と結果を、[state] に出す。
     *
     * 新しい版が無かった、確かめられなかった、という結果は [RESULT_NOTICE_MILLIS] の間だけ出す。
     * 入れられない動かし方のとき、入れている最中、確かめている最中は何もしない。
     */
    fun checkNow() {
        val before = mutableState.value
        if (!canInstall || before is UpdateState.Installing || before is UpdateState.Checking) return
        mutableState.value = UpdateState.Checking
        scope.launch {
            val (result, afterNotice) = when (val check = checker.check()) {
                is UpdateCheck.Available -> {
                    mutableState.value = UpdateState.Available(check.release)
                    return@launch
                }
                UpdateCheck.UpToDate -> UpdateState.UpToDate to UpdateState.Idle
                // 確かめる前に新しい版が分かっていたら、その状況へ戻す。
                is UpdateCheck.Failed -> UpdateState.CheckFailed to when (before) {
                    is UpdateState.Available, is UpdateState.Failed -> before
                    else -> UpdateState.Idle
                }
            }
            mutableState.value = result
            delay(RESULT_NOTICE_MILLIS)
            // 出している間に別の状況へ変わっていたら、そちらを残す。
            mutableState.compareAndSet(result, afterNotice)
        }
    }

    /** 見つけてある新しい版を取得して入れる。新しい版が無いか、入れている最中なら何もしない。 */
    fun install() {
        val release = when (val current = mutableState.value) {
            is UpdateState.Available -> current.release
            is UpdateState.Failed -> current.release
            else -> return
        }
        mutableState.value = UpdateState.Installing(release)
        scope.launch {
            try {
                launch(download(release))
            } catch (exception: IOException) {
                reportError("新しい版を入れられません", exception)
                mutableState.value = UpdateState.Failed(release)
                return@launch
            }
            onReadyToExit()
        }
    }

    internal companion object {
        /** 利用者の求めで確かめた結果を出しておく時間。 */
        const val RESULT_NOTICE_MILLIS = 5_000L
    }
}
