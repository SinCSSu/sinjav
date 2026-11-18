package top.sincs.sinjav.api

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import top.sincs.sinjav.config.AppProperties
import java.nio.file.Path

/** 把刮削时下载到本地的图片目录映射为静态资源：/local-images/{番号}/poster.jpg */
@Configuration
class WebConfig(private val props: AppProperties) : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val dir = Path.of(props.images.dir).toAbsolutePath().normalize()
        registry.addResourceHandler("/local-images/**")
            .addResourceLocations("file:${dir.toString().replace('\\', '/')}/")
    }
}
