package to.sava.legirss

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread

/**
 * 同じデータの置き場所を使うアプリが 1 つだけ動くようにする。
 *
 * 先に起動した側はロックファイルを握り、後から起動した側からの合図をローカルの接続で待つ。
 */
class SingleInstance private constructor(
    private val lock: FileLock,
    private val server: ServerSocket,
) : AutoCloseable {
    override fun close() {
        server.close()
        lock.release()
        lock.channel().close()
    }

    companion object {
        private const val LOCK_FILE_NAME = "legirss.lock"
        private const val PORT_FILE_NAME = "legirss.port"
        private const val CONNECT_TIMEOUT_MILLIS = 2_000

        /**
         * 最初の 1 つとして起動できたら、それを表すものを返す。
         *
         * すでに動いているものがあれば、そちらへ合図を送って null を返す。
         *
         * @param onActivate 後から起動したものから合図が届いたときに呼ぶ。待ち受け用のスレッドから呼ばれる。
         */
        fun acquire(dataDir: Path, onActivate: () -> Unit): SingleInstance? {
            Files.createDirectories(dataDir)
            val portFile = dataDir.resolve(PORT_FILE_NAME)
            val lock = tryLock(dataDir.resolve(LOCK_FILE_NAME))
            if (lock == null) {
                signalRunningInstance(portFile)
                return null
            }
            val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            Files.writeString(portFile, server.localPort.toString())
            thread(isDaemon = true, name = "single-instance") { acceptSignals(server, onActivate) }
            return SingleInstance(lock, server)
        }

        private fun tryLock(lockFile: Path): FileLock? {
            val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // 同じプロセスの中ですでに握っている。
                null
            }
            if (lock == null) channel.close()
            return lock
        }

        private fun acceptSignals(server: ServerSocket, onActivate: () -> Unit) {
            while (!server.isClosed) {
                try {
                    server.accept().close()
                    onActivate()
                } catch (_: IOException) {
                    // 待ち受けを閉じたときもここへ来る。閉じていなければ次の接続を待つ。
                }
            }
        }

        /** 動いている側が待ち受けているポートへつなぐ。つながらなくても、起動を諦めることに変わりはない。 */
        private fun signalRunningInstance(portFile: Path) {
            val port = try {
                Files.readString(portFile).trim().toIntOrNull()
            } catch (_: IOException) {
                null
            } ?: return
            try {
                Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MILLIS) }
            } catch (exception: IOException) {
                System.err.println("起動済みのアプリへ合図を送れませんでした: ${exception.message}")
            }
        }
    }
}
