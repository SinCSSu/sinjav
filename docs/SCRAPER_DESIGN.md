# 刮削逻辑设计文档 & 复刻计划

> 本文档整理 sinJav 刮削体系的完整设计：数据源选取、字段来源优先级、番号识别、图片管线、
> 翻译、Emby 集成、任务系统，并给出从零复刻一套同等能力系统的分阶段实施计划。
> 所有结论均来自当前代码实现，带文件级出处。

---

## 一、系统架构总览

```
文件扫描 → 番号识别 → 多源刮削（并发 + 合并） → 本地图片缓存 → 元数据落库
                ↓                                        ↓
          Emby 快照同步 ←──── 图片推送 ──── 重新刮削对比界面
                                                         ↓
                                              整理（硬链/软链/移动/复制）
                                                         ↓
                                              NFO + poster/fanart + Emby 刷新
```

核心包职责：

| 包 | 职责 |
|---|---|
| `scan` | 目录扫描、番号识别（NumberRecognizer） |
| `scraper` | 数据源实现、合并策略链、对比刮削、图片缓存/裁剪/格式检测、翻译 |
| `domain` | movie / file_records / jobs / emby_items 仓储（JdbcTemplate，无 JPA） |
| `organize` | 整理执行器、命名模板、NFO 写入 |
| `emby` | Emby 客户端、快照同步、重新刮削回写与图片推送 |
| `job` | 任务调度（scan/scrape/organize/pipeline）、互斥、启动恢复 |
| `api` | REST 控制器 + openapi.yaml 契约 |
| `config` | AppProperties（prefix=`sinjav`） |

---

## 二、数据源与字段来源优先级

### 2.1 数据源清单（scraper 包）

| Scraper | id | 抓取方式 | 特点 |
|---|---|---|---|
| JavBusScraper | `javbus` | 搜索页 → 详情页两跳 | 字段最全，封面为 DMM 全封套横版大图（`a.bigImage`） |
| JavDbScraper | `javdb` | 搜索页 → 详情页 | 需登录 Cookie；额外产出 **titleZh**（`strong.origin-title`）简繁 locale 双标签匹配；女优用 `a.actor-female` 过滤男性演员；plot 截断 2000 字符 |
| MissAvScraper | `missav` | 直接访问 `/{number小写}` 详情页 | 免搜索页；封面走 fourhoi.com CDN（有 CF 盾，需过盾代理） |

### 2.2 来源优先级与合并规则（ScraperChain）

- **顺序即优先级**：`sinjav.scraper.source-order`（默认 `javbus, javdb, missav`），排在前面的源字段优先，后面的只补缺
- **全源并发抓取**，结果按 source-order 排序后做字段级合并：
  - 标量字段（title/plot/date/maker/...）：pick 排序后第一个非空值
  - 列表字段（actresses/genres/previewUrls）：**并集合并**（保序去重）
- **coverSource 记录**：封面实际取自哪个源，随落库保存（推送 Emby 时用它回源下载）
- **同源失败不阻断**：单源 scrape 抛 ScrapeException 记日志继续，全源失败才算失败
- **成功判定**：与 JavSP 的 `required_keys: [cover, title]` 思路一致——封面 URL 与标题必须至少其一存在（当前实现：title 为硬要求）

### 2.3 多源对比刮削（ScrapeCompareService，重新刮削 UI 后端）

- **fieldKeys（12 个）**：`title, plot, releaseDate, durationMin, director, maker, label, series, actresses, genres, coverUrl, previewUrls`
- **defaults**：按 source-order 排序后第一个有值的源
- 预览时先 `NumberRecognizer.normalize` 校正番号；apply 支持改番号（重命名+查重）
- 列表输入分隔符：`，` `,` `、` `/` `\n`
- 落库后同步下载图片缓存（coverUrl + previewUrls + coverSource + previewSource）
- 翻译按钮：对 plot/genres 等做 DeepL → 百度 → Google 免费兜底（opencc4j 繁转简），翻译结果回填输入框由用户确认后保存

### 2.4 借鉴 JavSP 的经验（未实现但值得复刻时纳入）

- **javdb 封面降级**：javdb 封面带站点水印，应降级为 fallback（仅在其他源无封面时使用）
- **分类型流水线**：FC2/素人等番号类型走专门数据源组合（JavSP 按 normal/fc2/cid/getchu/gyutto 分 pipeline）

---

## 三、番号识别（NumberRecognizer）

识别链（firstNotNullOfOrNull，顺序固定）：

| 策略 | 正则要点 | 归一化 |
|---|---|---|
| FC2 | `(?i)fc2[-_\s]?(?:ppv)?[-_\s]?(\d{6,9})` | `FC2-PPV-{n}` |
| Heyzo | `(?i)heyzo(?:_hdid)?[-_\s]?(\d{3,4})` | `HEYZO-{n}` |
| Standard | `([a-z]{2,6})[-_\s]?(\d{2,5})`，多候选取最后一个，数字保留前导零 | `ABF-017` 不变 `ABF-17`；过滤干扰词 CD/HD/4K/PT/VOL/PART |
| Caribbean | `(\d{6})[-_](\d{2,3})`，仅文件名含无码站标识（carib/1pon/10mu/tokyo/pacifica/muramura）时启用 | `CARIB-{6位}-{2~3位}` |

- 扫描入口：`recognize(filename)`（取 `substringBeforeLast('.')`）
- API 用途：`normalize(input)`（补假扩展名走同一链路）
- Emby 快照识别链：**文件路径 → originalTitle → name**（三级回退）

---

## 四、图片管线（本体系最容易踩坑的部分）

### 4.1 下载（HttpClientFactory）

- `download(url, referer = null, source = null): ByteArray`
- 自动补 `Referer = URL 源站点 origin`（防盗链）
- 代理/UA/Cookie/超时/全局限速统一管理；CF 过盾请求分流到专用代理（`cfProxySources` 匹配的域名），自签 CA 信任

### 4.2 格式检测（ImageFormats）

魔数识别 `JPEG(FFD8FF) / PNG / GIF / WEBP(RIFF..WEBP) / AVIF(..ftyp) / BMP`；未知返回 null。
`isEmbyUnsupported` = webp / avif。

### 4.3 封面竖版化（CoverCropper）—— 经调研 JavSP 后的确定性规则

- DMM/JavBus 封面是全封套高清扫描，横向拼接 `[封底|书脊|前封面]`，**前封面固定在右侧**
- 裁剪规则：`POSTER_RATIO = 1.42`（海报 w:h = 1:1.42），`posterW = height / 1.42` **右对齐裁取**
- 排除三层：**FC2 番号前缀**（单张产品图不裁）/ **竖图或方图**（height >= width）/ **解析失败** → 均原样返回
- `normalizeJpeg()`：JPEG 原样；PNG/GIF/BMP 重编码 JPEG；WebP/AVIF 抛可读异常（Emby 不支持，会 500）
- ❗ 已弃用方案：亮度自动识别左右封（误判率高，前封面偏亮即判错）

### 4.4 本地缓存（ImageStore）

- 目录：`{images.dir}/{番号}/`（默认 `./data/images`）
- 文件：`poster.jpg`（裁剪后竖版）、`preview{i+1}.jpg`
- 手动上传（saveLocal）同名覆盖、**不裁剪**
- TTL 清理：每日 04:00（Asia/Shanghai），按 mtime，`ttlDays<=0` 永久保留

### 4.5 整理输出（Organizer）

- `movie.nfo` + `poster.jpg`（裁剪竖版）+ `fanart.jpg`（**横版原图**，Emby 背景需要 landscape）+ `preview{i}.jpg`
- `videoOnly=true`：只转移视频，跳过 nfo 与图片（适配 Emby 已刮好的多分辨率入库）
- 命名模板占位符：`{number} {title} {date} {maker} {series} {actress}`（actress 取第一位；title 用原文不用译名）
- 非法字符清洗 + Windows 保留名处理（CON/PRN/AUX/NUL/COM1-9/LPT1-9）
- mode ∈ hardlink/symlink/move/copy；conflictPolicy 默认 skip；move 记录不能再链接（重整以旧目标为新源）

---

## 五、Emby 集成

### 5.1 客户端协议要点（EmbyClient）

- 鉴权：`X-Emby-Token` 请求头
- 元数据回写：读完整条目 → 覆盖非空字段 → `POST /Items/{id}`（整条回传）
- **图片上传协议（重大坑）**：Emby 的 `POST /Items/{id}/Images/{type}` 要求请求体为图片字节的 **Base64 编码字符串**（Jellyfin 才是原始二进制）；Content-Type 按魔数判定，识别失败拒绝上传
- 错误处理：非 2xx 时把 **Emby 错误响应体**拼入异常消息（定位 500 根因的关键手段）

### 5.2 快照同步（EmbySyncService）

- `POST /emby/sync`：遍历全部电影库（每页 500），识别番号（文件路径→原标题→名称），**事务内全量替换** `emby_items(item_id, number, name, path, synced_at)`
- 用途：影片卡片「Emby已有」标签、整理弹窗「已存在」分组（EXISTS / NOT EXISTS 互斥）
- 快照是时点数据：Emby 库变更后需手动重新同步

### 5.3 重新刮削回写与图片推送（EmbyRescrapeService）

- 元数据映射：标题→`Name`（统一「番号-标题」）+ `OriginalTitle`；简介→`Overview`；日期→`PremiereDate`+`ProductionYear`；maker+label→`Studios`；actresses+series+label+director→`Tags`；genres 过滤画质标签
- `pushCover`：封面 URL 下载 + cropFrontCover → 失败回退本地 `poster.jpg` 缓存 → normalizeJpeg → `uploadImage(itemId, "Primary")`
- `pushBackdrops`：previewUrls 下载（单张失败跳过）或回退本地 preview1..N → 逐张 normalizeJpeg（全部不支持则报错**不动 Emby 数据**）→ **倒序删除全部旧 Backdrop**（避免索引前移漏删）→ 逐张上传
- 前端：推送封面成功后加 cache-bust 时间戳刷新代理图（代理图带 `max-age=86400` 缓存头）

### 5.4 封面代理

`GET /emby/items/{id}/images/primary` 后端转发 Emby 主图，不暴露 API Key；无图 404。

---

## 六、任务系统

- 任务类型：`scan / scrape / organize / pipeline`（scan→scrape→organize 组合）
- 互斥：`existsRunning(type)` 同类型同时只允许一个运行（JobConflictException）
- 停止：协作式 requestStop，进行中条目执行完
- organize/pipeline 完成后触发 `emby.refreshLibrary()`（未配置跳过、失败不阻断）
- organizing 标记：整理前批量标记记录 → finally 兜底按 id 复位 pending
- **启动恢复（JobStartupRecovery）**：监听 `ApplicationReadyEvent`（晚于 Flyway），容器重建后：
  - 残留 running 任务 → 标 failed（附「应用重启，任务中断」）
  - 残留 organizing 记录 → 全量复位 pending
  - 解决 existsRunning 永久互斥死锁

---

## 七、配置项清单（sinjav.*）

| 组 | 关键项 | 默认值 |
|---|---|---|
| scraper | source-order | `javbus, javdb, missav` |
| scraper | javdb-cookie / user-agent | 必填 javdb 才可用 |
| scraper | proxy-host/port, request-interval-ms, concurrency, timeout-ms, cf-timeout-ms, max-retries-per-item | 1500 / 1 / 15000 / 60000 / 1 |
| scraper | base-url-javbus/javdb/missav | 官方域名（可换镜像） |
| scraper | cf-proxy-url / cf-proxy-sources / cf-ca-cert-path | 空（CF 过盾代理） |
| scraper | auto-translate / deepl-api-key / baidu-* | true / 空 |
| organize | naming-template / conflict-policy / generate-nfo / download-images | `{number}/{number}` / skip / true / true |
| scan | video-extensions / min-file-size-mb / filename-pattern | 常见视频格式 / 50 / 空 |
| emby | base-url / api-key / proxy-host/port | 空 |
| images | download / dir / ttl-days | true / `./data/images` / 3（0=永久） |
| async | job-pool-size / queue-capacity | 2 / 100 |

数据库迁移：Flyway，`V1__init_schema` → `V2__movie_title_orig` → `V3__drop_organize_global_config` → `V4__emby_items`，**SQLite 与 PostgreSQL 双方言同步演进**。

---

## 八、复刻计划（从零实现）

> 目标：用最小可验证的增量复刻本体系。每阶段末尾给出验收标准与关键坑位。

### P0 骨架（1-2 天）

- [ ] Spring Boot 4 + Kotlin 项目；JdbcTemplate（不用 JPA）；Flyway 双方言迁移（建议 SQLite 起步，PG 适配放最后）
- [ ] AppProperties 配置骨架（scraper/organize/scan/emby/images/async 六组）
- [ ] jobs 表 + JobService（create/existsRunning/list/stop）+ @Async 线程池
- [ ] REST 契约先行：手写 openapi.yaml（全部 API 先定契约再实现）
- [ ] 前端 Vue3 + Element Plus 骨架（api 层封装 + openapi 对齐的 types.ts）

**验收**：scan 任务能跑完并落 file_records。
**坑**：JdbcTemplate batchUpdate 参数要 `.toTypedArray()`。

### P1 识别与刮削（3-5 天）

- [ ] NumberRecognizer（四策略识别链 + normalize，保留前导零）
- [ ] HttpClientFactory（UA/代理/限速/超时/重试 + Referer 自动补全）
- [ ] MovieMetadata 模型 + Scraper 接口 + 3 个数据源（JavBus → JavDb → MissAV 顺序实现）
- [ ] ScraperChain 并发抓取 + source-order 字段级合并（列表并集）+ coverSource 记录
- [ ] ScrapeService 批量刮削任务 + 翻译服务（可先接免费兜底，DeepL/百度后补）
- [ ] ScrapeCompareService 多源对比（fieldKeys 12 个 + defaults）

**验收**：对一个番号能出多源对比预览并逐字段落库。
**坑**：JavDb 需要 Cookie；MissAV 域名经常变；字段解析要用「先精确后模糊」的标签匹配防误配（如「發行商」vs「發行日期」）。

### P2 图片管线（2-3 天）

- [ ] ImageFormats 魔数检测
- [ ] CoverCropper：`posterW = h/1.42` 右对齐裁取 + FC2/竖图/解析失败排除 + normalizeJpeg
- [ ] ImageStore 缓存（poster.jpg / previewN.jpg + TTL 清理）
- [ ] 前端卡片 2:3 竖版展示 + `/local-images/{番号}/*` 静态服务

**验收**：刮削后本地缓存得到竖版 poster。
**坑**：❗不要做亮度左右封识别（已验证误判率高）；横版=右对齐裁剪是确定性规则；WebP 必须归一化。

### P3 整理与任务（2-3 天）

- [ ] NamingTemplate（占位符 + Windows 保留名清洗）
- [ ] NfoWriter（movie.nfo）
- [ ] Organizer 四种 mode + conflictPolicy + videoOnly + 重整（fanart 保留横版原图）
- [ ] JobRunner（scan/scrape/organize/pipeline）+ organizing 标记与 finally 复位
- [ ] JobStartupRecovery 启动恢复

**验收**：容器 kill -9 后重启，无残留 running 任务、organizing 全部复位、同类型任务可再触发。
**坑**：hardlink 失败要自动降级 copy；move 模式的重整路径语义。

### P4 Emby 集成（2-3 天）

- [ ] EmbyClient（Token 鉴权 / serverInfo / 库列表 / Items 分页 / updateMetadata）
- [ ] 图片上传：**Base64 body** + 魔数 Content-Type + 错误响应体入异常
- [ ] EmbySyncService 快照 + emby_items 表（V4 迁移）
- [ ] EmbyRescrapeService（对比回写 + pushCover/pushBackdrops，Backdrop 倒序清空重传）
- [ ] 封面代理 + 前端 cache-bust
- [ ] organize/pipeline 完成后 refreshLibrary

**验收**：推送封面/剧照后 Emby 条目图正确且重复推送不叠加。
**坑**：❗Emby 图片上传是 Base64 字符串不是二进制（500 报 base64 解码错误）；WebP/AVIF 上传 500；Backdrop 删除会使索引前移必须倒序；代理图缓存头需前端 cache-bust。

### P5 前端打磨（2-3 天）

- [ ] 影片库卡片（竖版封面 + Emby已有/状态 badge + 源路径行）
- [ ] 整理弹窗（四分组：待整理/已存在/整理中/已整理；折叠懒加载；下半区统一滚动容器）
- [ ] 重新刮削对比面板（逐字段来源选择 + 翻译 + 推送封面/剧照 + 保存）
- [ ] 任务中心（进度/日志/停止）

### 复刻优先级建议

若只想复刻核心价值，最小集 = **P1（识别+刮削+合并）+ P2（图片管线）**；P4 是与 Emby 联动的差异化能力；P3/P5 可按需裁剪。

---

## 九、已验证的踩坑清单（复刻必读）

1. **Emby 图片上传是 Base64 字符串**——原始二进制会得到 500「not a valid Base-64 string」
2. **封套扫描前封面固定在右侧**——`h/1.42` 右对齐裁取，不要做任何自动识别
3. **FC2 封面是单张产品图**——按番号前缀排除裁剪
4. **WebP/AVIF 无法被 ImageIO 解码且 Emby 不支持**——上传前归一化 JPEG，无法转码时给用户可读错误
5. **JavDB 封面带水印**——建议降级为封面 fallback 来源
6. **Backdrops 删除会使索引前移**——倒序删除
7. **容器重建残留 running 任务**——启动恢复组件必须做，否则互斥检查永久死锁
8. **代理图 86400 缓存头**——推送后前端需 cache-bust 刷新
9. **SQLite/PostgreSQL 双方言**——迁移从 V1 就要双份维护，JdbcTemplate SQL 避免方言特性
10. **图片下载必须带 Referer**（源站防盗链）；MissAV CDN 需 CF 过盾代理分流

---

## 十、后续待办备忘（2026-11-22 评审补充，逐项独立实现）

> 复刻/评审过程中识别的补充注意事项，**均未实现**，按优先级分四组，后续逐项落地。

### 高优先（安全 / 可用性 / 稳定性）

- [ ] **SSRF 校验**：图片推送接口（EmbyRescrapeService.pushCover/pushBackdrops）接受前端传入的 coverUrl/previewUrls，服务端直接下载——需校验 scheme 白名单（http/https）并拒绝内网地址段（127.x/10.x/172.16-31.x/192.168.x/169.254.x 云元数据）
- [ ] **JavDB Cookie 静默失效检测**：Cookie 过期后返回登录页/空结果而非报错，刮削「成功但全空」——ScrapeService 需在结果异常空时告警提示检查 Cookie
- [ ] **SQLite busy_timeout / 写事务串行化**：jobPoolSize=2 并发 + emby_items 全量替换事务，SQLite 可能 database is locked
- [ ] **javdb 封面降级 fallback**（JavSP use_javdb_cover）：javdb 封面带水印，应降级为封面候选末位，仅其他源无封面时使用

### 中优先（数据质量）

- [ ] **封面候选列表**：coverUrl 由单值改为按 source-order 的有序候选列表，下载失败自动换下一个源（JavSP covers 机制）
- [ ] **番号多源投票**（JavSP respect_site_avid）：多源返回的番号按出现次数投票，修正本地识别错误
- [ ] **EXIF 方向处理**：ImageIO 解码前 exif_transpose（手动上传图片可能旋转 90°）
- [ ] **时长/日期单位归一化**：各源分钟 vs 秒、2021/03/05 vs 2021-03-05，Scraper 内必须归一化后再返回
- [ ] **番号后缀属性识别**：-C（中字）/ -U（无码）/ -CD1/-CD2（分片），识别链过滤（JavSP detect_special_attr）
- [ ] **genre 跨站映射表**：每站 CSV 映射 + genre_id（JavSP data/genre_*.csv 方案）
- [ ] **required_keys 终检**：抓取成功判定补上 cover（对齐 JavSP [cover, title]）

### 中优先（风控运维）

- [ ] **TLS 指纹限制认知**：Java HttpClient JA3 指纹易被 CF 识别，纯 HTTP 客户端方案的硬上限——过盾代理需承担全部此类请求，无法绕过时属预期
- [ ] **站点健康检查**：启动时探测 base-url-* 可用性（JavSP 有每日爬虫自动测试机制）；镜像域名漂移频繁
- [ ] **限速按站点维度**：全局 requestIntervalMs 不合理，DMM 图床抗得住、JavDB 敏感，按 source 配置间隔
- [ ] **日志脱敏**：成人内容标题不要打进 INFO 级日志；data/images、SQLite 库确认在 .gitignore

### 低优先（打磨）

- [ ] **Jellyfin 兼容分支**：图片上传协议两者相反（Emby=Base64 字符串 / Jellyfin=原始二进制），按 /System/Info 产品名分支
- [ ] **Emby 锁定字段验证**：用户锁定的元数据回写可能静默不生效，推送后读回验证
- [ ] **refreshLibrary 异步语义**：整理完刷新后 Emby 界面不会立即出现新条目，前端不得假设刷新完成
- [ ] **路径长度截断**：NamingTemplate 按 Windows MAX_PATH 剩余长度截短标题（JavSP 按标点分段递减方案）
- [ ] **AI 裁剪触发条件**（JavSP process_poster）：无码 / fc2 / label 匹配素人系正则 → 人脸检测裁剪（slimeface 思路），其余固定右对齐
- [ ] **翻译缓存与配额**：免费引擎限流，术语表一致性（已有标签缓存，可扩展）
