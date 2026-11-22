# sinJav 开发交接文档（会话上下文快照）

> 用途：在新电脑/新会话中恢复开发上下文。让 AI 助手先完整阅读本文件，再继续开发。
> 快照时间：2026-11-18，对应状态：全部计划任务完成，17 个测试全部通过，`./gradlew build` 成功。

## 1. 项目目标

JAV 影片自动整理 + 元数据刮削器后端（参考 Solr159/JavBoss 的实现方式）：

1. **扫描**：递归扫描源目录，从文件名识别番号（有码普通番号 / FC2 / Heyzo / 无码站点格式），零侵入（只读源目录）
2. **刮削**：按番号从 JavBus / JavDB 抓取元数据（标题/女优/厂商/系列/日期/标签/封面/剧照），支持 fallback 主备回退与 merge 字段合并两种策略，限速防风控
3. **整理**：按 API 指定方式（硬链接/软链接/移动/复制）整理到目标目录，命名模板可配置（默认 `{number}/{number}`，即转移后文件名 = 番号），生成 Kodi/Jellyfin/Emby 兼容 `.nfo` + poster/fanart/剧照
4. **API 契约**：手写静态 `docs/openapi.yaml`（OpenAPI 3.1），控制器实现严格对齐契约

## 2. 已确认的决策（用户拍板，勿更改）

| 决策项 | 结论 |
|---|---|
| 数据库 | 用户选了 PostgreSQL/MySQL，后因"无外部数据库"改为：**默认 SQLite**（`data/sinjav.db` 零配置），通过环境变量 `SINJAV_DB_URL`/`SINJAV_DB_USER`/`SINJAV_DB_PASSWORD` 可切 PostgreSQL。仓储 SQL 已做双方言兼容 |
| API 契约 | 仅手写静态 openapi.yaml，**不集成 springdoc** |
| 媒体输出 | 整理时生成 .nfo + poster.jpg + fanart.jpg + 剧照 |
| 触发方式 | **仅 REST API 触发**，无定时任务 |
| 鉴权/前端 | 本期无鉴权、无前端 |

## 3. 技术栈与关键版本坑（Spring Boot 4 特有，踩过的坑）

- Kotlin 2.3 + Spring Boot 4.1.2 + Gradle（Java 17 toolchain），Windows/PowerShell 环境
- **Jackson 3**：包名是 `tools.jackson.*` 不是 `com.fasterxml.*`；必须加 `tools.jackson.module:jackson-module-kotlin`（否则 Kotlin data class 默认值/nullability 反序列化报错）
- **Boot 4 模块化**：Flyway 自动配置在 `org.springframework.boot:spring-boot-flyway`（starter 不会带上，必须显式加，否则 Flyway 根本不跑）；TestRestTemplate 移到 `org.springframework.boot:spring-boot-resttestclient`（包名 `org.springframework.boot.resttestclient.TestRestTemplate`，需 `@AutoConfigureTestRestTemplate`），且依赖 `org.springframework.boot:spring-boot-restclient`
- **SQLite (xerial 3.53+)**：
  - `GeneratedKeyHolder` 列名是 `last_insert_rowid()` 不是 `id`，取 keys 第一个数值列
  - `CURRENT_TIMESTAMP` 产出无毫秒 TEXT，而驱动默认 `date_string_format=yyyy-MM-dd HH:mm:ss.SSS` 解析失败 → 通过 Hikari `data-source-properties.date_string_format: "yyyy-MM-dd HH:mm:ss"` 修复（PG 驱动忽略该属性）
  - 日期必须用 JDBC 参数（`setDate`/`setTimestamp`，存 INTEGER epoch）写入，不能用 SQL 字面量字符串（TEXT 会按 date_string_format 解析失败）
  - Flyway 迁移按方言分目录：`db/migration/sqlite/V1__init_schema.sql` 与 `db/migration/postgresql/V1__init_schema.sql`，application.yaml 用 `classpath:db/migration/{vendor}`
- `SettingsService` 启动即查库 → 需要 `@DependsOnDatabaseInitialization`（否则 Flyway 之前跑，报 no such table）
- `@Async` 自调用失效 → 异步执行体拆到独立 `JobRunner`（`open fun` + `@Async("jobExecutor")`）
- 整理命名模板语义：渲染结果的**除最后一段外是子目录，最后一段是目标文件基名**（扩展名沿用源文件）。默认模板 `{number}/{number}`——转移后的文件名即番号（2026-11-20 需求，原为 `{number}/{number} - {title}`；用户已保存过设置的话需自行更新设置页模板）
- **整理任务完成后自动触发 Emby 全库刷新**（JobRunner.runOrganize/runPipeline 末尾，EmbyClient.refreshLibrary → POST /Library/Refresh）：Emby 未配置（baseUrl/apiKey 空）时跳过；刷新失败只 appendError 记录，不影响整理任务结果
- **Emby 番号快照与「已存在」体系（2026-11-21 需求）**：POST /emby/sync（EmbySyncService，同步执行）遍历全部电影媒体库（每页 500，识别链 文件路径→原标题→标题），全量替换 emby_items 快照表（V4 迁移，两方言）；MovieSummaryDto/MovieDetailDto 增 `embyExists`（批量查 emby_items），影片库卡片显示「Emby已有」标签（MoviesView 工具栏有「同步 Emby」按钮）；整理弹窗新增第四组「已存在」= 番号在 emby_items 且 organize_status='pending'（listForOrganize group=exists，pending 组 NOT EXISTS 保证互斥）；OrganizeRequest/OrganizePreviewRequest/OrganizeParams 增 `videoOnly`（只整理视频：跳过 nfo/图片，优先级高于全局 generateNfo/downloadImages），适配 Emby 已有多分辨率媒体的场景
- **CF 过盾专用信任管理器**（HttpClientFactory.MitmTrustManager）：MITM 签发的证书按「CA 验签 + 有效期」信任，不走 JDK PKIX 字节级 DN 链匹配——部分 MITM 代理（trawl）签子证书时 issuer DN 的 ASN.1 编码与 CA subject 不一致，JDK 会误判「Path does not chain」而 openssl 规范化比较通过；代理直通的真实站点证书回退 JDK 标准 PKIX。TLS 握手实际收到的证书链由 LoggingTrustManager 打进日志（诊断用）
- 整理目标目录与整理方式已从全局配置移除（V3 迁移清掉 settings 行）：OrganizeRequest/OrganizePreviewRequest/pipeline 的 organize 段**每次必须显式传 targetDir + mode**（DTO @NotBlank，Organizer 内 requiredMode/requiredTargetDir 兜底）；命名模板/冲突策略/nfo/图片仍是全局可覆盖
- 整理重整语义（前端三列整理弹窗）：已 organized 记录再整理 = 重整。链接/复制类删除旧目标文件及同目录 movie.nfo/poster/fanart/preview*.jpg（目录空则移除）；move 类不删文件，把 source_path 重置为旧 target_path 后重新整理，且重整不能用链接模式（源文件已不在源位置）。逐条覆盖走 OrganizeRequest.items（recordId + 可选 targetPath/mode）；预览走 POST /organize/preview（同步、只算路径不执行）
- **整理中状态（2026-11-21 需求）**：organize_status 新增 `organizing` 值——整理任务开始时 JobRunner.executeOrganize 批量标记涉及的记录（markOrganizing），逐条处理完落到 organized/failed/skipped，finally 兜底把本任务未落终态的记录复位为 pending（resetOrganizingToPending(ids)，按 id 防并发误复位）。重整判断在 Organizer.executeItem 里改为「organized 或 (organizing 且有 targetPath)」。整理弹窗三组：待整理/整理中（新折叠组，不可勾选）/已整理；「整理选中」提交后弹窗不关闭，刷新三组便于连续整理
- **启动恢复（2026-11-22）**：容器重建会丢掉执行线程但 jobs 表残留 running、file_records 残留 organizing（且 existsRunning 会误判互斥）。JobStartupRecovery 监听 ApplicationReadyEvent（晚于 Flyway）：running 任务逐个 appendError「应用重启，任务中断」+ finish(failed)；resetAllOrganizingToPending 复位全部 organizing 记录
- **封面竖版化（2026-11-22，调研后重写）**：JavBus/JavDb 封面是 DMM 全封套高清扫描，横向拼接 [封底|书脊|前封面]，**前封面固定在右侧**、宽高比约 w:h=1:1.42。CoverCropper.cropFrontCover(number, bytes)（scraper 包）在 width>height 时按 JavSP（5.2k stars 事实标准）同款确定性规则裁取：posterW = h/1.42 后**右对齐**裁取（不做任何左右识别——DMM ps.jpg 虽是原生竖版但仅约 245×350 分辨率太低，横版裁剪是同类工具通行做法；曾试过亮度自动识别误判率高已弃用）。排除：number 以 FC2 开头（单张产品图）、竖图/方图、解析失败——均原样返回。应用点：ImageStore.downloadAll 的本地 poster.jpg 缓存（卡片展示）、Organizer.downloadImages 的整理 poster.jpg（fanart.jpg 保留横版原图作背景）。手动上传（saveLocal）不裁剪。已有番号需重新刮削才会更新本地缓存
- **Emby 图片推送（2026-11-22）**：重新刮削对比面板新增「推送封面」「推送剧照」按钮（EmbyItemDrawer diff-actions）。后端 POST /emby/items/{id}/images/primary 与 /images/backdrops（EmbyRescrapeService.pushCover/pushBackdrops）：封面=预览 coverUrl 下载（cropFrontCover 竖版化）→ 回退本地 poster.jpg 缓存（ImageStore.readPoster，新增 readPreviews）；剧照=预览 previewUrls（换行分隔）→ 回退本地 previewN.jpg；Backdrop 先倒序 deleteImage 清空再逐张 uploadImage 防叠加（EmbyClient 新增 exchangeImage/uploadImage/deleteImage/backdropCount，BackdropImageTags 数组取数量）。前端推送封面后加 cache-bust 时间戳刷新代理图（后端代理图带 max-age=86400）。两按钮与「保存」独立，不动元数据
- **图片上传 500 修复（2026-11-22）**：Emby 对 WebP/AVIF 字节流或 Content-Type 不匹配的上传返回 500。修复：ImageFormats（scraper 包）魔数检测（jpeg/png/gif/webp/avif/bmp）；CoverCropper.normalizeJpeg——JPEG 原样、PNG/GIF/BMP 重编码 JPEG、WebP/AVIF 抛可读 IllegalArgumentException（提示换来源）；EmbyClient.uploadImage Content-Type 按魔数自动判定；exchangeImage 失败时把 Emby 错误响应体带进异常消息便于定位。推送封面/剧照上传前均 normalize；剧照 normalize 在清空 Backdrop **之前**执行（全部不支持时不破坏 Emby 数据）
- **Emby 图片上传协议（关键）**：Emby 的 POST /Items/{id}/Images/{type} 要求请求体为图片字节的 **Base-64 编码字符串**（Jellyfin 才是原始二进制）；EmbyClient.exchangeImage 用 Base64.getEncoder().encodeToString(body) 后 ofString 发送。错误响应体（Emby 返回 base64 解码失败等）已拼入异常消息便于排查
- **刮削体系设计文档（2026-11-22）**：docs/SCRAPER_DESIGN.md——完整架构、数据源与合并规则、番号识别链、图片管线（裁剪/格式/缓存）、Emby 集成、任务系统、配置清单、**从零复刻的分阶段计划（P0-P5）**与 10 条踩坑清单。新会话了解刮削全貌先读这份文档
- **JavSP 源码研究（2026-11-22）**：docs/JAVSP_RESEARCH.md——JavSP 全部 16 个数据源的字段覆盖统计、5 条按番号类型的流水线与默认顺序、info_summary 字段级合并机制（first-non-empty + genre=javdb 例外 + cover 候选列表 + javdb 封面降级 fallback + 番号投票 + required_keys 终检）、封面下载 big_covers 优先与 AI 裁剪触发条件、与本项目 ScraperChain 的对照差距表
- **待办备忘（2026-11-22）**：docs/SCRAPER_DESIGN.md 第十章——评审补充的 21 项未实现注意事项，按高（SSRF 校验/Cookie 失效检测/SQLite busy_timeout/javdb 封面降级）/中（封面候选列表/番号投票/EXIF/单位归一/番号后缀/genre 映射/required_keys/健康检查/按站限速/日志脱敏）/低（Jellyfin 分支/锁定字段验证/路径截断/AI 裁剪/翻译缓存）优先级勾选清单，后续逐项独立实现
- **JavSP 爬虫移植（2026-11-22）**：按 docs/JAVSP_RESEARCH.md 规格卡移植 16 个数据源（原 Python 逐字段转写），全部 @Component 注册进 ScraperChain（byId 自动收集）。新 id：airav(JSON API，barcode 查询+列表搜索兜底+马赛克破坏版过滤)、arzon/arzon_iv(共享 ArzonScraperBase：agecheck 会话 Cookie→搜索第一条→表格解析；arzon 把レーベル并入 genre)、avsox(javbus 风格，FC2 补 PPV 前缀，FC2 的系列=拍摄者落 maker)、avwiki(URL 直达 av-wiki.net/{番号}，srcset 最大宽封面，dl.dltable dt/dd)、fanza(适配：JavSP 按 cid 直达→我们按番号猜测 cid（小写+补零 5 位）或 DMM 站内搜索 digital/videoa 优先；age_check_done=1；region 文案检测)、fc2(article/{id}，标题反爬取 h3 全文本，封面优先预览图，跳 id.fc2.com=要求日本 IP)、fc2ppvdb(モザイク字段判定有无码→未用，販売者→label)、fc2fan(fc2fanLocalPath 本地 {番号}.html 优先，回退 fc2club.top 镜像)、jav321(唯一 POST：postForm /search sn=番号，搜索即详情，封面=预览图列表首项)、javlib(/cn/vl_searchbyid，唯一命中重定向进详情，多候选剔除蓝光版)、javmenu(mrzyx.xyz/{番号} 直达，video[data-poster] 封面，广告后缀清理)、mgstage(adc=1，女优 td 文本+链接合并，简介保留 br 换行)、prestige(__age_auth__=true，发售日在链接 ?date= 参数，レーベル→series，HTTP 500=无此片)、gyutto/dl_getchu(GYUTTO-/GETCHU- 前缀专用，EUC-JP 解码)。HttpClientFactory 新增 getDetailed(body+最终URI+Set-Cookie)与 postForm；ScraperProps 新增 15 个 baseUrl+fc2fanLocalPath；ScraperParsers 统一日期(ISO/yyyy-MM-dd/yyyy年MM月dd日)与时长(H:MM:SS 换算分钟)解析。**默认 sourceOrder 不变**（javbus, javdb, missav），新源按需配置启用；Jackson3 的 JsonNode.map/forEach 与 Kotlin 扩展冲突，迭代一律用 children() buildList 模式。未移植字段：score/magnet/uncensored/actress_pics（模型无对应字段）
- **403 处理与日志降噪（2026-11-22）**：HttpClientFactory 新增 describeHttpFailure——403/503 读响应体分类（Just a moment/challenge-platform→CF 挑战需过盾代理或换镜像；CF Error 1020→地区 IP 屏蔽如 JavDB 屏蔽日本 IP），get/getDetailed/postForm 全接入；mgstage/airav 爬虫修正 403 误报（原先包装成「未找到番号」，现明确报「拒绝访问（反爬/地域限制）」）；ScraperChain 失败日志分类降噪（「未找到」降 DEBUG 无堆栈，真实故障单行 WARN+异常类名，堆栈放 DEBUG）；ScraperProps 默认 userAgent 换完整 Chrome 131 UA（裸 Mozilla/5.0 是 mgstage/javlib/airav 403 的高概率根因，注意：曾保存过设置的实例 DB 里可能存有旧值）。**CF 方案定案（用户确认）：沿用 trawl 过盾代理 + cfProxySources，不移植 JavSP cloudscraper**
- **刮削链功能（2026-11-22）**：设置面板新增「刮削链（按番号类型，复刻 JavSP 流水线）」，5 条链与番号归类规则——ScrapeChainType 枚举（NORMAL/FC2/CID/GETCHU/GYUTTO）+ ScraperChain.chainTypeOf（FC2 前缀→fc2、GETCHU-/GYUTTO- 前缀→专用链、纯数字 6-8 位→cid、其余→normal）+ ScraperChain.chainFor（过滤未注册源 id，链全无效回退 sourceOrder）；ScraperProps 新增 chainNormal/chainFc2/chainCid/chainGetchu/chainGyutto（默认= JavSP 原始流水线顺序），chainFor(type) 空配置回退 sourceOrder；scrapeWithSource/scrapeAllSources 均按 chainFor 取链（显式传 sources 仍优先，多源对比/重刮不受影响）；SettingsUpdate/SettingsDto/SettingsService/openapi（SettingsUpdate+Settings schema）/前端 SettingsView（5 条链输入+JavSP 默认恢复按钮+可用源 id 提示，CF 过盾下拉改为全链并集）全链路打通；ScraperChainTest 覆盖归类与回退。sourceOrder 保留为回退默认值不再作为主路径
- **数据源配置面板（2026-11-22）**：设置页新增「数据源配置」表（knownSources 全部注册源逐行配置：站点地址/鉴权 Cookie/超时 ms/间隔 ms）。实现：SourceConfig（config 包，baseUrl/cookie/timeoutMs/intervalMs，hasAnyOverride 全空视为未配置）挂 ScraperProps.sources（yaml 可绑定 + settings 表 sourceConfigs JSON 整体替换持久化，applySourceConfigs 小写归一并剔除空条目）；AppProperties.baseUrlFor(id)（独立配置覆盖内置 base-url-*，arzon/arzon_iv 同默认站）与 cookieFor(id, explicit)（优先级：显式传入 > 独立配置 > 内置默认——javdb 回退 javdbCookie、mgstage adc=1、prestige __age_auth__=true、fanza age_check_done=1）；HttpClientFactory.throttle(source) 改为**按源独立限速**（不同源并行、同源按间隔串行，per-source lastRequestBySource），timeoutFor 每源 timeoutMs 覆盖，get/getDetailed/postForm Cookie 统一走 cookieFor（javdb/fanza/mgstage/prestige 爬虫已移除硬编码 Cookie）；SettingsDto/SettingsUpdate 增 sourceConfigs+knownSources（SettingsController 注入 ScraperChain 提供）；openapi 增 SourceConfig schema；前端 types.ts SourceConfig + SettingsView el-table 配置卡；SourceConfigTest 4 用例（覆盖优先级/内置 Cookie/arzon 系）。未配置源行为完全不变
- 刮削翻译：ScrapeService 落库前经 TranslationService 翻译——标题译中文存 title（原标题存 title_orig 列，V2 迁移；NFO originaltitle 用原标题）；简介存「中文简介\n\n原简介」。翻译源优先级 DeepL(deepl-api-key) > 百度(baidu-translate-app-id/secret) > Google 免费接口兜底，设置页可配，失败回退原文不阻断刮削。**整理路径 {title} 占位符用 title_orig（无则回退 title），保存路径不使用译名**
- **元数据标题格式统一为「番号-中文标题」**（2026-11-20 需求）：三条落库路径都加前缀——ScrapeService 批量刮削、ScrapeCompareService.apply（多源对比）、EmbyRescrapeService 回写 name；title_orig 仍是原题，NFO title 用 DB title（带前缀）、originaltitle 用原题
- 多源对比刮削（ScrapeDialog 主流程）：前端「开始刮削」→ POST /scrape/preview（同步并发抓全源，ScraperChain.scrapeAllSources 返回按 source-order 排序的各源结果，不落库）→ 用户逐字段选来源（各源/留空/手动输入，默认=合并规则）→ POST /scrape/apply（ScrapeCompareService.apply：逐字段组装元数据，标题/简介翻译，manual 值不翻译，markScraped+downloadImages 同步执行）。「全部刮削」按钮保留旧任务流程（按合并规则自动落库）。已刮削影片可重新对比覆盖落库
- Emby 重新刮削也是多源对比：/emby/items/{id}/rescrape/preview 返回 fields/defaults（ScrapeCompareService.fieldMaps）+ 合并 proposed；/emby/items/{id}/rescrape/apply 按逐字段选择回写 Emby（留空=不回写，manual 不翻译；厂商/厂牌→工作室，女优/系列/导演→标签，类别过滤画质标签并译名）
- 测试：SQLite 库文件放 `build/` 下（`@TempDir` 会在连接池关闭前删除文件而失败）；集成测试 `SinJavApplicationTests` 走通扫描→识别→复制整理→NFO 全链路（刮削依赖外网，测试里用 JDBC 直写模拟刮削结果）

## 4. 代码结构（top.sincs.sinjav 包）

```
src/main/kotlin/top/sincs/sinjav/
├── SinJavApplication.kt          # 启动类（无特殊注解，@EnableAsync 在 AsyncConfig）
├── common/GlobalExceptionHandler.kt   # ProblemDetail 统一错误
├── config/
│   ├── AppProperties.kt          # @ConfigurationProperties: scraper/organize/scan/async
│   ├── AsyncConfig.kt            # @EnableAsync + jobExecutor 线程池
│   └── SettingsService.kt        # 运行时配置：DB 持久化 + 启动应用覆盖值 + PUT 后立即生效
├── scan/
│   ├── DirectoryScanner.kt       # 递归遍历 + 视频扩展名过滤 + minFileSizeMb(50MB)
│   ├── NumberRecognizer.kt       # 策略链: FC2 → Heyzo → 有码 → 无码；前导零规范化；多候选取最后匹配
│   └── ScanService.kt            # 增量扫描（路径+大小+mtime 指纹），入库 file_records
├── scraper/
│   ├── MovieMetadata.kt          # 统一元数据模型
│   ├── HttpClientFactory.kt      # JDK HttpClient + UA/Cookie/代理/全局限速+并发许可
│   ├── JavBusScraper.kt          # Jsoup 解析，选择器常量化（站点改版改这里）
│   ├── JavDbScraper.kt           # 支持 Cookie 配置
│   ├── ScraperChain.kt           # fallback/merge 策略，scrapeWithSource() 返回来源
│   └── ScrapeService.kt          # 批量刮削，失败记录 fail_reason，可重试
├── domain/
│   ├── Models.kt                 # Movie/FileRecord/Job 领域模型
│   ├── MovieRepository.kt / FileRecordRepository.kt / JobRepository.kt
├── organize/
│   ├── LinkStrategy.kt           # HardLink/SymLink/Move/Copy（跨分区/权限报错不降级）
│   ├── NamingTemplate.kt         # 占位符 {number}{title}{date}{maker}{series}{actress} + 非法字符清洗
│   ├── NfoWriter.kt              # Kodi/Jellyfin/Emby 兼容 movie.nfo
│   └── Organizer.kt              # 建目录→链接/移动→写nfo→下图片→更新 file_records；含预览(preview)与逐条整理(executeItem，支持重整已整理记录)
├── job/
│   ├── JobService.kt             # 创建任务+分发（同类型互斥），params JSON 序列化
│   └── JobRunner.kt              # @Async 执行体：runScan/runScrape/runOrganize/runPipeline
└── api/
    ├── TriggerControllers.kt     # POST /scans /scans/preview /scans/commit /scrape /organize /organize/preview /pipeline → 202 JobAccepted
    ├── QueryControllers.kt       # GET /movies /movies/{number} /file-records /organize/records /jobs /jobs/{id}；DELETE /movies/{number}（删记录+关联+图片缓存，不动视频）
    ├── SettingsController.kt     # GET/PUT /settings
    └── dto/ApiDtos.kt / PageDto.kt
```

配置：`src/main/resources/application.yaml`（server.port=8656；SQLite 默认 + SINJAV_DB_* 环境变量切换 PG）
迁移：`src/main/resources/db/migration/{sqlite,postgresql}/V1__init_schema.sql`（表：movies/actresses/genres/movie_actresses/movie_genres/file_records/jobs/settings）

## 5. 构建 / 运行 / 测试

```powershell
cd f:/sinJav
./gradlew test          # 17 个测试全过（含 SQLite 全链路集成测试）
./gradlew bootRun       # 启动，端口 8656
```

测试文件：
- `src/test/kotlin/top/sincs/sinjav/scan/NumberRecognizerTest.kt`
- `src/test/kotlin/top/sincs/sinjav/organize/NamingTemplateTest.kt`、`NfoWriterTest.kt`
- `src/test/kotlin/top/sincs/sinjav/SinJavApplicationTests.kt`（集成：RANDOM_PORT + TestRestTemplate + SQLite）

## 6. 验证状态

- ✅ 编译 + 17 测试通过（2026-11-18）
- ✅ 集成链路：扫描识别 ABP-123 → job completed → file_records recognized → 影片 pending → 复制整理到 `{number}/{number} - {title}` → movie.nfo 生成 → 源文件未动 → organize_status=organized
- ⚠️ 刮削器选择器（JavBus/JavDb）未经过真实网络环境验证，实际部署时需用几个番号实测并微调选择器常量

## 7. 后续待办（按优先级）

1. 真实环境实测刮削（代理/UA/Cookie 配置、选择器校准）
2. MySQL 方言迁移脚本与 URL 兼容性验证
3. 鉴权（API Key / Basic）扩展
4. 前端对接（消费 docs/openapi.yaml）
5. 整理模式 symlink 在 Windows 非开发者模式下的错误提示优化
