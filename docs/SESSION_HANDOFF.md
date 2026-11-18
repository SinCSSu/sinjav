# sinJav 开发交接文档（会话上下文快照）

> 用途：在新电脑/新会话中恢复开发上下文。让 AI 助手先完整阅读本文件，再继续开发。
> 快照时间：2026-11-18，对应状态：全部计划任务完成，17 个测试全部通过，`./gradlew build` 成功。

## 1. 项目目标

JAV 影片自动整理 + 元数据刮削器后端（参考 Solr159/JavBoss 的实现方式）：

1. **扫描**：递归扫描源目录，从文件名识别番号（有码普通番号 / FC2 / Heyzo / 无码站点格式），零侵入（只读源目录）
2. **刮削**：按番号从 JavBus / JavDB 抓取元数据（标题/女优/厂商/系列/日期/标签/封面/剧照），支持 fallback 主备回退与 merge 字段合并两种策略，限速防风控
3. **整理**：按 API 指定方式（硬链接/软链接/移动/复制）整理到目标目录，命名模板可配置（如 `{number}/{number} - {title}`），生成 Kodi/Jellyfin/Emby 兼容 `.nfo` + poster/fanart/剧照
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
- 整理命名模板语义：渲染结果的**除最后一段外是子目录，最后一段是目标文件基名**（扩展名沿用源文件）
- 整理重整语义（前端三列整理弹窗）：已 organized 记录再整理 = 重整。链接/复制类删除旧目标文件及同目录 movie.nfo/poster/fanart/preview*.jpg（目录空则移除）；move 类不删文件，把 source_path 重置为旧 target_path 后重新整理，且重整不能用链接模式（源文件已不在源位置）。逐条覆盖走 OrganizeRequest.items（recordId + 可选 targetPath/mode）；预览走 POST /organize/preview（同步、只算路径不执行）
- 刮削翻译：ScrapeService 落库前经 TranslationService 翻译——标题译中文存 title（原标题存 title_orig 列，V2 迁移；NFO originaltitle 用原标题）；简介存「中文简介\n\n原简介」。翻译源优先级 DeepL(deepl-api-key) > 百度(baidu-translate-app-id/secret) > Google 免费接口兜底，设置页可配，失败回退原文不阻断刮削。**整理路径 {title} 占位符用 title_orig（无则回退 title），保存路径不使用译名**
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
