package top.sincs.sinjav.scraper

/**
 * 番号类型（复刻 JavSP 的 5 条刮削流水线）：
 * - NORMAL：有码常规番号（ABP-123、HEYZO-1031、CARIB-081621-001 等）
 * - FC2：FC2-PPV 系
 * - CID：纯数字内容 ID（DMM cid 形态，如 00123abcd45678）
 * - GETCHU / GYUTTO：同人平台专用番号
 */
enum class ScrapeChainType(val configKey: String, val label: String) {
    NORMAL("chainNormal", "有码常规"),
    FC2("chainFc2", "FC2"),
    CID("chainCid", "CID"),
    GETCHU("chainGetchu", "Getchu"),
    GYUTTO("chainGyutto", "Gyutto"),
}
