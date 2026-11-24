package top.sincs.sinjav.config

import org.springframework.boot.context.properties.ConfigurationProperties
import top.sincs.sinjav.scraper.ScrapeChainType

/**
 * 单个数据源的独立配置（设置页「数据源配置」表）：
 * - baseUrl：覆盖内置站点地址（镜像域名切换）
 * - cookie：鉴权信息（会作为该源全部请求的 Cookie；留空用内置默认，如 mgstage adc=1）
 * - timeoutMs / intervalMs：超时与访问间隔（毫秒），0 = 用全局默认
 */
data class SourceConfig(
    var baseUrl: String = "",
    var cookie: String = "",
    var timeoutMs: Long = 0,
    var intervalMs: Long = 0,
) {
    /** 是否配置了任意覆盖项（全空视为未配置） */
    fun hasAnyOverride(): Boolean =
        baseUrl.isNotBlank() || cookie.isNotBlank() || timeoutMs > 0 || intervalMs > 0
}

data class ScraperProps(
    var sourceOrder: String = "javbus, javdb, missav",
    // ---- 刮削链（复刻 JavSP：按番号类型 5 条流水线，逗号分隔，顺序即字段优先级）----
    // normal：有码常规番号（ABP-123 等）；fc2：FC2-PPV 系；cid：纯数字内容 ID；getchu/gyutto：同人平台
    var chainNormal: String = "airav, avsox, javbus, javdb, javlib, jav321, mgstage, prestige",
    var chainFc2: String = "fc2, avsox, javdb, javmenu, fc2ppvdb",
    var chainCid: String = "fanza",
    var chainGetchu: String = "dl_getchu",
    var chainGyutto: String = "gyutto",
    // 完整浏览器 UA：裸 "Mozilla/5.0" 会被 mgstage/javlib/airav 等站点的风控直接 403
    var userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    var javdbCookie: String = "",
    var proxyHost: String = "",
    var proxyPort: Int = 0,
    /**
     * 不走代理的域名（逗号/换行分隔）：命中的请求直连，不经过全局代理与过盾代理。
     * 支持通配：`*.example.com`（仅子域）、`example.com`（本域 + 全部子域）。
     * 典型用途：内网 Emby/图床、本机服务、被代理节点绕远反而更慢的图床。
     */
    var noProxyHosts: String = "",
    var requestIntervalMs: Long = 1500,
    var concurrency: Int = 1,
    // 普通请求超时；过盾（cfProxy）请求单独用 cfTimeoutMs（过盾需等待 CF 挑战，更长）
    var timeoutMs: Long = 15000,
    var cfTimeoutMs: Long = 60000,
    var maxRetriesPerItem: Int = 1,
    var baseUrlJavbus: String = "https://www.javbus.com",
    var baseUrlJavdb: String = "https://javdb.com",
    var baseUrlMissav: String = "https://missav.ai",
    // ---- JavSP 移植数据源（默认 sourceOrder 不含它们，按需在 source-order 中启用） ----
    var baseUrlAirav: String = "https://www.airav.wiki",
    var baseUrlArzon: String = "https://www.arzon.jp",
    var baseUrlAvsox: String = "https://avsox.click",
    var baseUrlAvwiki: String = "https://av-wiki.net",
    var baseUrlFanza: String = "https://www.dmm.co.jp",
    var baseUrlFc2: String = "https://adult.contents.fc2.com",
    var baseUrlFc2ppvdb: String = "https://fc2ppvdb.com",
    var baseUrlGyutto: String = "http://gyutto.com",
    var baseUrlJav321: String = "https://www.jav321.com",
    var baseUrlJavlib: String = "https://www.javlibrary.com",
    var baseUrlJavmenu: String = "https://mrzyx.xyz",
    var baseUrlMgstage: String = "https://www.mgstage.com",
    var baseUrlPrestige: String = "https://www.prestige-av.com",
    var baseUrlDlGetchu: String = "https://dl.getchu.com",
    var baseUrlFc2fan: String = "https://fc2club.top",
    /** fc2fan 本地 HTML 镜像目录（存在即启用本地模式，目录结构 {番号}.html）；空 = 走远程 */
    var fc2fanLocalPath: String = "",
    // ---- 每数据源独立配置（key=数据源 id；设置页「数据源配置」，settings 表以 JSON 持久化）----
    var sources: MutableMap<String, SourceConfig> = mutableMapOf(),
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
    // 自动翻译：批量刮削时翻译标题/简介/类别标签；关闭则直接落源站内容
    var autoTranslate: Boolean = true,
)

data class OrganizeProps(
    // 目标目录与整理方式不再走全局配置：每次整理请求必须显式指定
    // 默认子目录与文件基名均为番号（转移后的文件名 = 番号.原扩展名）
    var namingTemplate: String = "{number}/{number}",
    var conflictPolicy: String = "skip",
    var generateNfo: Boolean = true,
    var downloadImages: Boolean = true,
    /**
     * 目标文件名/子目录名的最大字节数（UTF-8）。
     * Linux/NAS 文件系统（ext4/btrfs/xfs）单个文件名上限 255 字节，日文每字 3 字节，
     * 长标题很容易超限报 File name too long；超限时按字节边界截断（保留扩展名）。
     */
    var maxFilenameBytes: Int = 240,
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

/**
 * 图片下载管线配置：
 * - concurrency：全局图片并发上限（封面/剧照共用，跨影片共享），0/负数按 1 处理
 * - intervalMs：图片请求独立间隔，与页面请求的 requestIntervalMs 分开计
 *   图片走的是独立 CDN（dmm/jdbstatic 等），不受页面风控约束，间隔可远小于页面
 *   （<=0 表示不限速，仅靠 concurrency 控速）
 */
data class ImageProps(
    var download: Boolean = true,
    var dir: String = "./data/images",
    /** 图片缓存过期天数：过期文件定时清理（每日 04:00）；0 = 永久保留 */
    var ttlDays: Int = 3,
    var concurrency: Int = 6,
    var intervalMs: Long = 200,
)

/**
 * 演员头像抓取配置：
 * - photoDir：本地落盘目录（默认 ./data/actors，Web 端以 /local-actors 暴露）
 * - scrapeIntervalMs：逐个演员之间的抓取间隔，对齐 MDCx 的 scrap_interval（1.5s），防风控
 * - pushToEmby：头像下载成功后是否推送到 Emby Person 主图（演员已缓存 emby_person_id 时）
 * - autoScrapeAfterBatch：批次刮削 / Emby 重刮收尾是否自动补全「本次新演员」的头像
 *   （关闭后两个入口都不触发，新演员留待演员列表页手动补全）
 */
data class ActorProps(
    var photoDir: String = "./data/actors",
    var scrapeIntervalMs: Long = 1500,
    var pushToEmby: Boolean = true,
    var autoScrapeAfterBatch: Boolean = true,
)

/**
 * Gfriends 头像库（github.com/gfriends/gfriends）索引配置：
 * - indexUrl：Filetree.json 地址；其所在目录同时作为图片 raw 基址（索引内的相对路径按此拼接）
 * - syncThresholdDays：索引超过该天数自动重新同步；<= 0 = 每次调用都重新同步
 * - enableProxy：下载是否走全局代理（scraper.proxyHost/proxyPort，可被设置页覆盖）；
 *   GitHub raw 在部分网络环境需代理才能访问，关闭则直连
 */
data class GfriendsConfig(
    var indexUrl: String = "https://raw.githubusercontent.com/gfriends/gfriends/master/Filetree.json",
    var syncThresholdDays: Int = 7,
    var enableProxy: Boolean = true,
)

@ConfigurationProperties(prefix = "sinjav")
data class AppProperties(
    val scraper: ScraperProps = ScraperProps(),
    val organize: OrganizeProps = OrganizeProps(),
    val scan: ScanProps = ScanProps(),
    val emby: EmbyProps = EmbyProps(),
    val async: AsyncProps = AsyncProps(),
    val images: ImageProps = ImageProps(),
    val gfriends: GfriendsConfig = GfriendsConfig(),
    val actors: ActorProps = ActorProps(),
) {
    fun videoExtensionSet(): Set<String> =
        scan.videoExtensions.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()

    fun sourceOrderList(): List<String> =
        scraper.sourceOrder.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }

    /** 按番号类型解析刮削链；该链未配置（空）时回退 sourceOrder */
    fun chainFor(type: ScrapeChainType): List<String> {
        val raw = when (type) {
            ScrapeChainType.NORMAL -> scraper.chainNormal
            ScrapeChainType.FC2 -> scraper.chainFc2
            ScrapeChainType.CID -> scraper.chainCid
            ScrapeChainType.GETCHU -> scraper.chainGetchu
            ScrapeChainType.GYUTTO -> scraper.chainGyutto
        }
        val list = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }
        return list.ifEmpty { sourceOrderList() }
    }

    /** 需要 CF 过盾的数据源 id 集合 */
    fun cfProxySourceSet(): Set<String> =
        scraper.cfProxySources.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()

    /**
     * 不走代理的域名模式（小写、去协议前缀与末尾点）。
     * 支持 `*.a.com`（仅子域）与 `a.com`（本域 + 子域）两种写法。
     */
    fun noProxyPatternSet(): List<String> =
        scraper.noProxyHosts.split(',', '\n', ';')
            .map { it.trim().lowercase().removePrefix("http://").removePrefix("https://").trimEnd('.') }
            .filter { it.isNotBlank() }
            .distinct()

    /** 数据源独立配置（未配置或全空覆盖项时返回 null） */
    fun sourceConfig(sourceId: String): SourceConfig? =
        scraper.sources[sourceId.trim().lowercase()]?.takeIf { it.hasAnyOverride() }

    /** 数据源站点地址：独立配置（镜像域名）覆盖优先，否则用内置默认 */
    fun baseUrlFor(sourceId: String): String {
        val id = sourceId.trim().lowercase()
        sourceConfig(id)?.baseUrl?.takeIf { it.isNotBlank() }?.let { return it }
        return when (id) {
            "javbus" -> scraper.baseUrlJavbus
            "javdb" -> scraper.baseUrlJavdb
            "missav" -> scraper.baseUrlMissav
            "airav" -> scraper.baseUrlAirav
            "arzon", "arzon_iv" -> scraper.baseUrlArzon
            "avsox" -> scraper.baseUrlAvsox
            "avwiki" -> scraper.baseUrlAvwiki
            "fanza" -> scraper.baseUrlFanza
            "fc2" -> scraper.baseUrlFc2
            "fc2ppvdb" -> scraper.baseUrlFc2ppvdb
            "gyutto" -> scraper.baseUrlGyutto
            "jav321" -> scraper.baseUrlJav321
            "javlib" -> scraper.baseUrlJavlib
            "javmenu" -> scraper.baseUrlJavmenu
            "mgstage" -> scraper.baseUrlMgstage
            "prestige" -> scraper.baseUrlPrestige
            "dl_getchu" -> scraper.baseUrlDlGetchu
            "fc2fan" -> scraper.baseUrlFc2fan
            else -> throw IllegalArgumentException("未知数据源: $sourceId")
        }
    }

    /**
     * 数据源鉴权 Cookie：显式传入优先（如 arzon 年龄验证动态 Cookie），
     * 其次独立配置，最后用内置默认（javdb 登录 Cookie / mgstage·prestige·fanza 年龄验证）
     */
    fun cookieFor(sourceId: String, explicit: String? = null): String? {
        explicit?.takeIf { it.isNotBlank() }?.let { return it }
        val id = sourceId.trim().lowercase()
        sourceConfig(id)?.cookie?.takeIf { it.isNotBlank() }?.let { return it }
        return when (id) {
            "javdb" -> scraper.javdbCookie
            "mgstage" -> "adc=1"
            "prestige" -> "__age_auth__=true"
            "fanza" -> "age_check_done=1"
            else -> null
        }?.takeIf { it.isNotBlank() }
    }
}
