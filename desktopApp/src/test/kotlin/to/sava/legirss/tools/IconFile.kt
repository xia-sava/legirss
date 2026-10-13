package to.sava.legirss.tools

import to.sava.legirss.TrayController
import to.sava.legirss.TrayState
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

// アイコンのファイルに入れる画像の大きさ。Windows が場面ごとに使い分ける。
private val ICON_SIZES = listOf(16, 24, 32, 48, 64, 256)

private const val ICO_HEADER_BYTES = 6
private const val ICO_ENTRY_BYTES = 16
private const val ICO_TYPE_ICON: Short = 1
private const val COLOR_PLANES: Short = 1
private const val BITS_PER_PIXEL: Short = 32

// 一辺が 256 の画像は、大きさを 1 バイトで表す欄に 0 と書く決まりになっている。
private const val SIZE_FIELD_MODULUS = 256

/**
 * アプリのアイコンのファイル（.ico）を、タスクトレイのアイコンと同じ絵から作る。
 *
 * 絵を変えたときに `./gradlew :desktopApp:generateIcon` で作り直す。引数は、書き出すファイルの場所。
 */
fun main(arguments: Array<String>) {
    val target = Path.of(arguments.single())
    val images = ICON_SIZES.map { size ->
        ByteArrayOutputStream().also { ImageIO.write(TrayController.drawIcon(TrayState.UNREAD, size), "png", it) }.toByteArray()
    }
    val headerBytes = ICO_HEADER_BYTES + ICO_ENTRY_BYTES * images.size
    val buffer = ByteBuffer.allocate(headerBytes + images.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putShort(0).putShort(ICO_TYPE_ICON).putShort(images.size.toShort())
    // 画像ごとの見出しを並べる。画像の本体は、全ての見出しのあとに同じ順で置く。
    images.zip(ICON_SIZES).fold(headerBytes) { offset, (png, size) ->
        val sizeField = (size % SIZE_FIELD_MODULUS).toByte()
        buffer.put(sizeField).put(sizeField).put(0).put(0)
        buffer.putShort(COLOR_PLANES).putShort(BITS_PER_PIXEL).putInt(png.size).putInt(offset)
        offset + png.size
    }
    images.forEach(buffer::put)
    Files.createDirectories(target.toAbsolutePath().parent)
    Files.write(target, buffer.array())
}
