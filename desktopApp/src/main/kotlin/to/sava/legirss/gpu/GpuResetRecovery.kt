package to.sava.legirss.gpu

import to.sava.legirss.AppLog
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Skiko が描画 API を受け取るシステムプロパティ。 */
private const val SKIKO_RENDER_API_PROPERTY = "skiko.renderApi"

/** Skiko が描画 API を受け取る環境変数。 */
private const val SKIKO_RENDER_API_ENVIRONMENT = "SKIKO_RENDER_API"

/** GPU のリセットから立て直せないときに使う描画 API。GPU を使わないため、リセットの影響を受けない。 */
private const val SOFTWARE_RENDER_API = "SOFTWARE"

/** 見張りを動かすスレッドの名前。 */
private const val RECOVERY_THREAD_NAME = "gpu-reset-recovery"

/** 見張りのデバイスが失われていないかを見に行く間隔。 */
internal val GPU_PROBE_POLL_INTERVAL = 1.seconds

/** デバイスが失われたと分かってから、Skiko に作り直させるまでの間。リセットの途中で作り直さないよう待つ。 */
internal val RENDERER_RECREATION_DELAY = 1.seconds

/**
 * 新しい見張りのデバイスを作ってから、失われた見張りのデバイスを解放するまでの間。
 *
 * 解放が落ちずに済んだのは、どれもこの間を空けた回だった。間を空けない回は試していない。
 */
internal val PROBE_RELEASE_DELAY = 2.seconds

/** 1 つの GPU の上に作った、そのデバイスが失われたかを見張るためのもの。 */
internal interface GpuProbe {
    val adapterName: String

    /** デバイスが失われていれば、その理由の `HRESULT`。失われていなければ 0。 */
    fun removedReason(): Int

    fun release()
}

/**
 * GPU のリセット（TDR）から描画を立て直す。
 *
 * Skiko は TDR でデバイスを失っても例外にせず、ネイティブ側で `Failed Direct3D call` を標準出力へ書くだけで
 * 描くのをやめる。ウィンドウは応答したまま中身が真っ白になる。そのため Skiko の外で GPU ごとに見張りの
 * デバイスを持ち、それが失われたら Skiko に作り直させる。
 *
 * TDR はその GPU の上のデバイスを全て失わせるため、見張りのデバイスも Skiko のものと同時に失われる。
 *
 * **失われた見張りのデバイスは、新しい見張りのデバイスを作ってから解放する。** 先に解放すると、プロセスごと
 * 落ちる（`EXCEPTION_ACCESS_VIOLATION`）。Skiko に作り直させる前でも後でも、待つ時間を変えても落ち、
 * 新しいデバイスがあるときだけ落ちなかった。
 */
internal class GpuResetRecovery(
    private val openProbes: () -> List<GpuProbe>,
    private val recreateRenderers: () -> Unit,
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.inWholeMilliseconds) },
) {
    /** TDR を 1 回待ち、描画を作り直し、失われた [probes] を新しい見張りのデバイスと入れ替えて返す。 */
    fun recoverFromNextReset(probes: List<GpuProbe>): List<GpuProbe> {
        val removed = awaitRemoval(probes)
        AppLog.info(
            "GPU がリセットされました: " + removed.joinToString { (probe, reason) -> "${probe.adapterName} ${hresult(reason)}" }
        )
        sleep(RENDERER_RECREATION_DELAY)
        recreateRenderers()
        val replacements = openProbesWhenAvailable()
        sleep(PROBE_RELEASE_DELAY)
        probes.forEach(GpuProbe::release)
        AppLog.info("GPU のリセットで失われた見張りのデバイスを解放しました")
        return replacements
    }

    /** 見張りのデバイスを作れるまで試し続ける。リセットの直後は作れないことがある。 */
    fun openProbesWhenAvailable(): List<GpuProbe> {
        while (true) {
            try {
                return openProbes()
            } catch (unavailable: GpuProbeUnavailable) {
                AppLog.warn("GPU のリセットを見張れません", unavailable)
                sleep(GPU_PROBE_POLL_INTERVAL)
            }
        }
    }

    /** [probes] のどれかが失われるまで待ち、失われたものと理由を返す。 */
    private fun awaitRemoval(probes: List<GpuProbe>): List<Pair<GpuProbe, Int>> {
        while (true) {
            val removed = probes.map { it to it.removedReason() }.filter { (_, reason) -> reason != 0 }
            if (removed.isNotEmpty()) return removed
            sleep(GPU_PROBE_POLL_INTERVAL)
        }
    }

    /** [first] から見張り始め、以後 TDR のたびに立て直しては見張り直す。 */
    fun run(first: List<GpuProbe>): Nothing {
        var probes = first
        while (true) {
            probes = recoverFromNextReset(probes)
        }
    }
}

/**
 * Windows で、GPU のリセットから描画を立て直す見張りを始める。**ウィンドウを作るより前に呼ぶ。**
 *
 * 立て直せないとき（Skiko の作り直す入口に手が届かない、見張りのデバイスを作れない）は、描画 API を
 * [SOFTWARE_RENDER_API] にする。リセットのたびに真っ白で止まるより、GPU を使わずに描く方を選ぶ。
 *
 * 描画 API を [SKIKO_RENDER_API_ENVIRONMENT] か [SKIKO_RENDER_API_PROPERTY] で指定されていれば、それに従い何もしない。
 */
fun installGpuResetRecovery() {
    if (!System.getProperty("os.name").startsWith("Windows")) return
    val specified = System.getenv(SKIKO_RENDER_API_ENVIRONMENT) ?: System.getProperty(SKIKO_RENDER_API_PROPERTY)
    if (specified != null) {
        AppLog.info("描画 API が $specified と指定されているので、GPU のリセットを見張りません")
        return
    }
    if (!SkikoRendererReset.isAvailable()) return renderWithoutGpu("Skiko の描画のデバイスを作り直せません")

    val probes = try {
        openD3D11Probes()
    } catch (unavailable: GpuProbeUnavailable) {
        return renderWithoutGpu("GPU のリセットを見張れません: ${unavailable.message}")
    }
    val recovery = GpuResetRecovery(::openD3D11Probes, SkikoRendererReset::recreateAll)
    thread(isDaemon = true, name = RECOVERY_THREAD_NAME) { recovery.run(probes) }
}

private fun renderWithoutGpu(reason: String) {
    AppLog.info("GPU を使わずに描きます: $reason")
    System.setProperty(SKIKO_RENDER_API_PROPERTY, SOFTWARE_RENDER_API)
}
