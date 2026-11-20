package top.sincs.sinjav.config

import org.springframework.boot.context.properties.ConfigurationProperties

data class ScraperProps(
    var sourceOrder: String = "javbus, javdb, missav",
    var userAgent: String = "Mozilla/5.0",
    var javdbCookie: String = "",
    var proxyHost: String = "",
    var proxyPort: Int = 0,
    var requestIntervalMs: Long = 1500,
    var concurrency: Int = 1,
    // 普通请求超时；过盾（cfProxy）请求单独用 cfTimeoutMs（过盾需等待 CF 挑战，更长）
    var timeoutMs: Long = 15000,
    var cfTimeoutMs: Long = 60000,
    var maxRetriesPerItem: Int = 1,
    var baseUrlJavbus: String = "https://www.javbus.com",
    var baseUrlJavdb: String = "https://javdb.com",
    var baseUrlMissav: String = "https://missav.ai",
    // CF 过盾代理（如 trawl MITM 正向代理 http://127.0.0.1:8192）。
    // 配置后：cfProxySources 中的数据源请求与图片走该代理，其余走全局 proxyHost/proxyPort（未配则直连）。
    var cfProxyUrl: String = "",
    // 需要 CF 过盾的数据源 id，逗号分隔（如 "javdb"）
    var cfProxySources: String = "",
    // trawl CA 证书路径（PEM）。非空且文件存在时，过盾代理 client 挂载含该 CA 的 SSLContext；
    // 仅限 application.yaml / 环境变量配置（运维级），不进设置界面
    var cfCaCertPath: String = "",
    // 翻译 API：DeepL > 百度 > Google 免费接口（未配置 key 时兜底）
    var deeplApiKey: String = "",
    var baiduTranslateAppId: String = "",
    var baiduTranslateSecret: String = "",
)

data class OrganizeProps(
    // 目标目录与整理方式不再走全局配置：每次整理请求必须显式指定
    // 默认子目录与文件基名均为番号（转移后的文件名 = 番号.原扩展名）
    var namingTemplate: String = "{number}/{number}",
    var conflictPolicy: String = "skip",
    var generateNfo: Boolean = true,
    var downloadImages: Boolean = true,
)

data class ScanProps(
    var videoExtensions: String = "mp4, mkv, avi, wmv, iso, rmvb, ts, m2ts, mov, flv",
    var minFileSizeMb: Long = 50,
    /** 文件名匹配正则（空 = 不过滤）；扫描/预览未显式指定时回退到此默认值 */
    var filenamePattern: String = "",
)

/** Emby 集成：服务地址（如 http://127.0.0.1:8096）+ API Key（控制台「API 密钥」生成）+ 独立代理（留空直连） */
data class EmbyProps(
    var baseUrl: String = "",
    var apiKey: String = "",
    var proxyHost: String = "",
    var proxyPort: Int = 0,
)

data class AsyncProps(
    var jobPoolSize: Int = 2,
    var jobPoolQueueCapacity: Int = 100,
)

data class ImageProps(
    var download: Boolean = true,
    var dir: String = "./data/images",
    /** 图片缓存过期天数：过期文件定时清理（每日 04:00）；0 = 永久保留 */
    var ttlDays: Int = 3,
)

@ConfigurationProperties(prefix = "sinjav")
data class AppProperties(
    val scraper: ScraperProps = ScraperProps(),
    val organize: OrganizeProps = OrganizeProps(),
    val scan: ScanProps = ScanProps(),
    val emby: EmbyProps = EmbyProps(),
    val async: AsyncProps = AsyncProps(),
    val images: ImageProps = ImageProps(),
) {
    fun videoExtensionSet(): Set<String> =
        scan.videoExtensions.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()

    fun sourceOrderList(): List<String> =
        scraper.sourceOrder.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }

    /** 需要 CF 过盾的数据源 id 集合 */
    fun cfProxySourceSet(): Set<String> =
        scraper.cfProxySources.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
}
