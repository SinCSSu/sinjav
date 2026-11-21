package top.sincs.sinjav.scraper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/** 封面竖版化：横版全封套扫描裁取左侧前封面区域 */
class CoverCropperTest {

    /** 生成纯色 JPEG（w×h，左半 leftColor 右半 rightColor） */
    private fun makeJpeg(w: Int, h: Int, leftColor: Int, rightColor: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until w) {
            for (y in 0 until h) {
                img.setRGB(x, y, if (x < w / 2) leftColor else rightColor)
            }
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    @Test
    fun `横版封套右对齐裁取前封面（JavSP 规则）`() {
        val cropper = CoverCropper()
        // 600×200 横版：左=白底封底（条码），右=深色前封面；posterW = 200/1.42 ≈ 140，裁右对齐区域
        val cropped = cropper.cropFrontCover("ABP-123", makeJpeg(600, 200, 0xFFFFFF, 0x303030))
        val img = ImageIO.read(ByteArrayInputStream(cropped))
        assertTrue(img != null && img.width == 140 && img.height == 200, "应裁为右对齐 140×200，实际 ${img?.width}×${img?.height}")
        // JPEG 有色度压缩，用容差断言：中心像素应接近深色（前封面）而非白色（封底）
        val rgb = img!!.getRGB(70, 100)
        assertTrue((rgb shr 16) and 0xFF <= 0x60, "应裁取右侧前封面（深色），实际=$rgb")
    }

    @Test
    fun `FC2 横版封面不裁剪`() {
        val cropper = CoverCropper()
        val original = makeJpeg(600, 200, 0xFF0000, 0x0000FF)
        for (number in listOf("FC2-PPV-1234567", "fc2-ppv-1234567")) {
            assertEquals(
                original.toList(), cropper.cropFrontCover(number, original).toList(),
                "FC2 封面为单张产品图，不应被裁剪",
            )
        }
    }

    @Test
    fun `竖版封面原样返回`() {
        val cropper = CoverCropper()
        val original = makeJpeg(200, 400, 0x00FF00, 0x000000)
        assertEquals(original.toList(), cropper.cropFrontCover("ABP-123", original).toList(), "竖图不应被修改")
    }

    @Test
    fun `非图片字节原样返回（不抛异常）`() {
        val cropper = CoverCropper()
        val junk = byteArrayOf(0x01, 0x02, 0x03)
        assertEquals(junk.toList(), cropper.cropFrontCover("ABP-123", junk).toList())
    }

    @Test
    fun `归一化 PNG 转 JPEG 且 JPEG 原样`() {
        val cropper = CoverCropper()
        // PNG → JPEG
        val png = ByteArrayOutputStream().apply {
            val img = BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB)
            ImageIO.write(img, "png", this)
        }.toByteArray()
        assertEquals("image/png", ImageFormats.detect(png))
        val asJpeg = cropper.normalizeJpeg(png)
        assertEquals("image/jpeg", ImageFormats.detect(asJpeg))
        // JPEG 原样返回
        val jpeg = makeJpeg(300, 200, 0xFF0000, 0x0000FF)
        assertEquals("image/jpeg", ImageFormats.detect(jpeg))
        assertEquals(jpeg.toList(), cropper.normalizeJpeg(jpeg).toList())
    }

    @Test
    fun `WebP 与 AVIF 魔数被识别且归一化抛出可读错误`() {
        val cropper = CoverCropper()
        // WEBP：RIFF....WEBP
        val webp = ByteArray(32).also {
            it[0] = 'R'.code.toByte(); it[1] = 'I'.code.toByte(); it[2] = 'F'.code.toByte(); it[3] = 'F'.code.toByte()
            it[8] = 'W'.code.toByte(); it[9] = 'E'.code.toByte(); it[10] = 'B'.code.toByte(); it[11] = 'P'.code.toByte()
        }
        assertEquals("image/webp", ImageFormats.detect(webp))
        assertTrue(ImageFormats.isEmbyUnsupported(ImageFormats.detect(webp)))
        val ex = runCatching { cropper.normalizeJpeg(webp) }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException && (ex.message ?: "").contains("WebP"), "应抛出含 WebP 的可读错误，实际=${ex?.message}")
        // AVIF：....ftyp
        val avif = ByteArray(32).also {
            it[4] = 'f'.code.toByte(); it[5] = 't'.code.toByte(); it[6] = 'y'.code.toByte(); it[7] = 'p'.code.toByte()
        }
        assertEquals("image/avif", ImageFormats.detect(avif))
        assertTrue(ImageFormats.isEmbyUnsupported(ImageFormats.detect(avif)))
        assertTrue(runCatching { cropper.normalizeJpeg(avif) }.exceptionOrNull() != null)
    }
}
