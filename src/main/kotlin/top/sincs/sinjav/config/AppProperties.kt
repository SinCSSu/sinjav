package top.sincs.sinjav.config

import org.springframework.boot.context.properties.ConfigurationProperties

data class ScraperProps(
    var sourceOrder: String = "javbus, javdb",
    var strategy: String = "fallback",
    var userAgent: String = "Mozilla/5.0",
    var javdbCookie: String = "",
    var proxyHost: String = "",
    var proxyPort: Int = 0,
    var requestIntervalMs: Long = 1500,
    var concurrency: Int = 1,
    var timeoutMs: Long = 15000,
    var maxRetriesPerItem: Int = 1,
    var baseUrlJavbus: String = "https://www.javbus.com",
    var baseUrlJavdb: String = "https://javdb.com",
    // 翻译 API：DeepL > 百度 > Google 免费接口（未配置 key 时兜底）
    var deeplApiKey: String = "",
    var baiduTranslateAppId: String = "",
    var baiduTranslateSecret: String = "",
)

data class OrganizeProps(
    var targetDir: String = "",
    var mode: String = "hardlink",
    var namingTemplate: String = "{number}/{number} - {title}",
    var conflictPolicy: String = "skip",
    var generateNfo: Boolean = true,
    var downloadImages: Boolean = true,
)

data class ScanProps(
    var videoExtensions: String = "mp4, mkv, avi, wmv, iso, rmvb, ts, m2ts, mov, flv",
    var minFileSizeMb: Long = 50,
)

data class AsyncProps(
    var jobPoolSize: Int = 2,
    var jobPoolQueueCapacity: Int = 100,
)

data class ImageProps(
    var download: Boolean = true,
    var dir: String = "./data/images",
)

@ConfigurationProperties(prefix = "sinjav")
data class AppProperties(
    val scraper: ScraperProps = ScraperProps(),
    val organize: OrganizeProps = OrganizeProps(),
    val scan: ScanProps = ScanProps(),
    val async: AsyncProps = AsyncProps(),
    val images: ImageProps = ImageProps(),
) {
    fun videoExtensionSet(): Set<String> =
        scan.videoExtensions.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()

    fun sourceOrderList(): List<String> =
        scraper.sourceOrder.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }
}
