package top.sincs.sinjav.scraper

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 封面竖版化：JavBus/JavDb 等数据源的封面是 DMM 全封套高清扫描，横向拼接为 [封底|书脊|前封面]，
 * 前封面固定在**右侧**，宽高比约 1:1.42（w:h）。与 JavSP（同类工具事实标准）一致：
 * 按高度换算前封面宽度（h/1.42）后右对齐裁取，确定性规则、无需识别。
 * 注：DMM 的 ps.jpg 虽是原生竖版前封面但分辨率太低（约 245×350），故仍下载横版 pl 后裁剪。
 * 排除项：FC2（封面为单张产品图，横版可能是完整构图，裁剪会切坏内容）；
 * 已是竖图/方图（width <= height）或解析失败时原样返回，不影响下载流程。
 */
@Component
class CoverCropper {
    private val log = LoggerFactory.getLogger(CoverCropper::class.java)

    companion object {
        /** 海报宽高比 w:h = 1:1.42（DVD 前封面标准比例，与 JavSP 默认值一致） */
        const val POSTER_RATIO = 1.42
    }

    fun cropFrontCover(number: String?, bytes: ByteArray): ByteArray {
        // FC2 封面不是双联封套扫描，不裁剪
        if (number?.uppercase()?.startsWith("FC2") == true) return bytes
        return runCatching {
            val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: return bytes
            // 竖图/方图无需处理（MissAV 等源本身就是竖版封面）
            if (img.height >= img.width) return bytes
            // 前封面宽高比约 1:1.42，按高度换算宽度后右对齐裁取（JavSP 同款规则）
            val posterW = (img.height / POSTER_RATIO).toInt().coerceAtMost(img.width)
            val h = img.height
            val out = java.awt.image.BufferedImage(posterW, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            g.drawImage(img.getSubimage(img.width - posterW, 0, posterW, h), 0, 0, null)
            g.dispose()
            val buf = ByteArrayOutputStream()
            ImageIO.write(out, "jpg", buf)
            buf.toByteArray()
        }.getOrElse {
            log.debug("封面裁剪失败，使用原图: {}", it.message)
            bytes
        }
    }

    /**
     * 归一化为 JPEG（Emby 图片上传对 WebP/AVIF 及格式不匹配的 Content-Type 会返回 500）：
     * JPEG 原样返回；PNG/GIF/BMP 等可解码格式重编码为 JPEG；
     * WebP/AVIF 等 ImageIO 无法解码的格式抛出异常（消息可直接展示给用户）。
     */
    fun normalizeJpeg(bytes: ByteArray): ByteArray {
        val type = ImageFormats.detect(bytes)
        if (type == "image/jpeg") return bytes
        val img = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
            ?: throw IllegalArgumentException(
                "图片为 ${type ?: "未知"} 格式，Emby 不支持上传且无法转码（常见于 WebP/AVIF 封面），请更换封面来源或先重新刮削",
            )
        val out = java.awt.image.BufferedImage(img.width, img.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.drawImage(img, 0, 0, java.awt.Color.WHITE, null)
        g.dispose()
        val buf = ByteArrayOutputStream()
        ImageIO.write(out, "jpg", buf)
        return buf.toByteArray()
    }
}
