package sh.hnet.comfychair.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/** The file's own size read from its header, shown in the details next to the size generated at. */
class MediaSizeTest {

    private val mediaDir = File("src/test/resources/media-size")

    @Test
    fun pngSize_comesFromItsHeader() {
        val png = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            write(ByteBuffer.allocate(4).putInt(13).array())
            write("IHDR".toByteArray())
            write(ByteBuffer.allocate(8).putInt(4096).putInt(3072).array())
        }.toByteArray()
        assertEquals(4096 to 3072, PngMetadataExtractor.imageSize(png))
    }

    @Test
    fun notAPng_hasNoSize() {
        assertNull(PngMetadataExtractor.imageSize(ByteArray(32)))
    }

    @Test
    fun mp4Size_withTheIndexAtTheEnd() {
        // ffmpeg's default, as VideoHelperSuite writes: mdat first, moov last
        assertEquals(96 to 64, Mp4MetadataExtractor.videoSize(File(mediaDir, "moov-at-end.mp4").readBytes()))
    }

    @Test
    fun mp4Size_skipsAnAudioTrackBeforeTheVideo() {
        // moov first (faststart), the audio track's header (no size) before the video's
        assertEquals(96 to 64, Mp4MetadataExtractor.videoSize(File(mediaDir, "faststart-audio-first.mp4").readBytes()))
    }

    @Test
    fun mp4Size_pastABoxWithA64BitSize_andFromAVersion1Header() {
        val tkhd = box("tkhd", ByteArray(104 - 8).also { body ->
            body[0] = 1
            ByteBuffer.wrap(body, 88, 8).putInt(3328 shl 16).putInt(4864 shl 16)
        })
        val mdat = ByteBuffer.allocate(24).putInt(1).put("mdat".toByteArray()).putLong(24).putLong(0).array()
        val mp4 = box("ftyp", ByteArray(8)) + mdat + box("moov", box("trak", tkhd))
        assertEquals(3328 to 4864, Mp4MetadataExtractor.videoSize(mp4))
    }

    @Test
    fun mp4WithAnEmptyTrackHeaderAtTheEnd_hasNoSize() {
        // A malformed file: tkhd's content is empty and the file ends there (reading its version byte would overflow)
        assertNull(Mp4MetadataExtractor.videoSize(box("moov", box("trak", box("tkhd", ByteArray(0))))))
    }

    @Test
    fun mp4WithoutAVideoTrack_hasNoSize() {
        assertNull(Mp4MetadataExtractor.videoSize(box("moov", box("trak", box("tkhd", ByteArray(84))))))
    }

    private fun box(type: String, body: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()
}
