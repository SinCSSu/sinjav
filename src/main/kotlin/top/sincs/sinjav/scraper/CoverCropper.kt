package top.sincs.sinjav.scraper

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 封面竖版化：JavBus/JavDb 等数据源的封面是 DVD 全封套扫描（左=前封面，右=封底，横向拼接），
 * 用于竖版海报场景（影片库卡片 2:3、Emby poster）时需要裁取左侧前封面区域。
 * 排除项：FC2（封面为单张产品图，横版可能是完整构图，裁剪会切坏内容）；
 * 已是竖图/方图（width <= height）或解析失败时原样返回，不影响下载流程。
 */
@Component
class CoverCropper {
    private val log = LoggerFactory.getLogger(CoverCropper::class.java)

    fun cropFrontCover(number: String?, bytes: ByteArray): ByteArray {
        // FC2 封面不是双联封套扫描，不裁剪
        if (number?.uppercase()?.startsWith("FC2") == true) return bytes
        return runCatching {
            val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: return bytes
            // 竖图/方图无需处理（MissAV 等源本身就是竖版封面）
            if (img.height >= img.width) return bytes
            val w = img.width / 2
            val h = img.height
            val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            g.drawImage(img.getSubimage(0, 0, w, h), 0, 0, null)
            g.dispose()
            val buf = ByteArrayOutputStream()
            ImageIO.write(out, "jpg", buf)
            buf.toByteArray()
        }.getOrElse {
            log.debug("封面裁剪失败，使用原图: {}", it.message)
            bytes
        }
    }
}
