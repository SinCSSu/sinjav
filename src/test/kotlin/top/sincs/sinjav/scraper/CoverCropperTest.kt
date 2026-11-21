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
    fun `横版封面裁取左侧竖版区域`() {
        val cropper = CoverCropper()
        // 600×200 横版：左红右蓝
        val cropped = cropper.cropFrontCover("ABP-123", makeJpeg(600, 200, 0xFF0000, 0x0000FF))
        val img = ImageIO.read(ByteArrayInputStream(cropped))
        assertTrue(img != null && img.width == 300 && img.height == 200, "应裁为左半 300×200")
        // JPEG 有色度压缩，用容差断言：中心像素应接近红色而非蓝色
        val rgb = img!!.getRGB(150, 100)
        assertTrue((rgb shr 16) and 0xFF >= 0xF0 && (rgb and 0xFF) <= 0x10, "裁剪区域应为左侧前封面（红色），实际=$rgb")
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
}
