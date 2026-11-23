package top.sincs.sinjav.api

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import top.sincs.sinjav.config.AppProperties
import java.nio.file.Path

/**
 * 本地图片静态映射：
 * - `/local-images/{番号}/poster.jpg`：刮削下载的封面/剧照（`sinjav.images.dir`）；
 * - `/local-actors/{文件名}`：演员头像（`sinjav.actors.photo-dir`，photo_path 只存 `actors/{名}.jpg`
 *   相对路径，文件名即最后一段，与 `/local-images` 同机制）。
 */
@Configuration
class WebConfig(private val props: AppProperties) : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/local-images/**")
            .addResourceLocations("file:${asFileLocation(props.images.dir)}/")
        registry.addResourceHandler("/local-actors/**")
            .addResourceLocations("file:${asFileLocation(props.actors.photoDir)}/")
    }

    /** 目录 → Spring 静态资源 location（绝对路径 + 正斜杠，Windows 下反斜杠会导致解析失败） */
    private fun asFileLocation(dir: String): String =
        Path.of(dir).toAbsolutePath().normalize().toString().replace('\\', '/')
}
