# sinJav

一个跑在本机的 JAV 影片库整理工具：把你已有的视频文件扫描入库，从文件名识别作品编号，自动抓取并翻译元数据，再按自己的命名规则整理成 Kodi / Jellyfin / Emby 能直接识别的媒体库结构。带一个 Vue 管理界面，也可以用 API 操作。

它不下载影片，只管理你本地已有的文件。除「移动」模式外，整理过程不会改动源目录里的视频（默认用硬链接）。

## 它能做什么

整个流程分成四步，可以单独执行，也可以用「流水线」一次跑完：

1. **扫描**：递归遍历指定目录，按扩展名和大小（默认 ≥ 50 MB）筛选视频，从文件名识别作品编号。支持先「预览」再勾选确认，避免误入库。文件记录以路径为键、用大小和修改时间做指纹，重复扫描时指纹未变的文件直接跳过，实现增量扫描；改名或换目录会被当作新文件。
2. **刮削**：并发请求 JavBus / JavDB / MissAV，按 `javbus → javdb → missav` 的优先级合并字段。标题、简介、标签会优先用 DeepL 翻译，其次百度，最后兜底 Google 免费接口；日文汉字用 opencc 转简体，原标题保留在 `title_orig`。
3. **整理**：按模板（默认 `{number}/{number} - {title}`）生成目录和文件名，支持硬链接 / 软链接 / 移动 / 复制四种模式，冲突可选跳过 / 覆盖 / 重命名。同时写出 `movie.nfo` 和封面、剧照。
4. **管理**：Web 界面里浏览影片、查看文件记录、跟踪异步任务进度、在线播放（HTTP Range 分片，只读源文件），以及修改运行时配置。

所有操作都由 API 或界面手动触发，**没有定时任务**，也不会在后台偷偷扫描你的磁盘。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 后端 | Kotlin 2.3 + Spring Boot 4.1（Java 17） |
| 构建 | Gradle Wrapper 9.9.1（`gradlew` / `gradlew.bat`） |
| 数据库 | SQLite（默认，零配置）；也可切 PostgreSQL；Flyway 管理迁移 |
| 数据访问 | Spring JDBC（无 JPA），手写 SQL |
| 爬虫 / 解析 | JDK HttpClient + Jsoup，繁简转换用 opencc4j |
| 前端 | Vue 3 + TypeScript + Vite 8 + vue-router（hash 模式）+ Element Plus |
| API 文档 | 手写的 OpenAPI 3.1 文件：`docs/openapi.yaml` |

## 快速开始

### 1. 启动后端

需要本机有 JDK 17；Gradle 本身由 wrapper 自动下载。

Windows（CMD / PowerShell）：

```bat
mkdir data
gradlew.bat bootRun
```

macOS / Linux：

```bash
mkdir -p data
./gradlew bootRun
```

服务监听 `http://localhost:8656`。首次启动 Flyway 会自动建表，数据库文件默认在 `data/sinjav.db`。如果忘了创建 `data/` 目录导致启动报路径错误，手动建一个即可。

### 2. 启动前端

另开一个终端：

```bash
cd sinjav_web
npm install
npm run dev
```

浏览器打开 `http://localhost:5173`。Vite 会把 `/api` 和 `/local-images` 代理到后端的 8656 端口，所以两个服务都要开着。

想只跑一个服务，可以执行 `npm run build`，然后把 `sinjav_web/dist/` 里的东西拷到 `src/main/resources/static/`，由 Spring Boot 直接托管（前端用 hash 路由，不需要额外配置）。

### 3. 第一次用

1. 进「设置」页，把 **整理目标目录** 填上（比如媒体库的路径），确认整理模式（默认硬链接）。
2. 进「仪表盘」点「扫描」，选择源目录，先看预览、取消不想要的文件，再确认落库。
3. 点「刮削」补全元数据；必要时在设置里填 JavDB Cookie 或翻译 API Key。
4. 点「整理」生成媒体库文件；也可以直接用「流水线」把扫描 → 刮削 → 整理串起来。

任务都是异步的，提交后返回 202 和一个任务 ID，在「任务」页看进度和失败明细。

## 配置

配置分两层：

- **`src/main/resources/application.yaml`**：启动级配置（数据库、端口、代理、翻译密钥、线程池等）。其中 `SINJAV_DB_URL` / `SINJAV_DB_USER` / `SINJAV_DB_PASSWORD` 环境变量可以把数据库切到 PostgreSQL，例如 `SINJAV_DB_URL=jdbc:postgresql://host:5432/sinjav`。
- **设置页（`GET/PUT /api/v1/settings`）**：运行期配置存在数据库的 `settings` 表里，改完立即生效，重启后依然保留。刮削源顺序、整理模板、JavDB Cookie、DeepL / 百度翻译密钥这些都可以直接在界面上改；密钥在日志里会打码成 `***`。

几个容易踩坑的点：

- 刮削有请求间隔（默认 1.5 秒）和全局并发限制（默认 1），别为了快把它们调得太激进。
- JavDB 部分信息需要登录，要填 Cookie；遇到 Cloudflare 挑战时可以配置过盾代理（`cf-proxy-url` + `cf-proxy-sources`）和对应 CA 证书。
- 硬链接要求源和目标在同一文件系统；跨盘会失败，这种情况改用复制或移动。Windows 下软链接需要管理员权限或开发者模式。
- SQLite 是单写者，适合个人本机使用；多实例部署请换 PostgreSQL。

## 项目结构

```
sinjav/
├── src/main/kotlin/top/sincs/sinjav/
│   ├── api/          REST 控制器、DTO、静态资源与本地图片映射
│   ├── common/       全局异常处理
│   ├── config/       配置属性、线程池、运行时设置服务
│   ├── domain/       数据模型与 JDBC Repository
│   ├── job/          异步任务的创建、互斥与执行
│   ├── organize/     命名模板、链接策略、NFO 与文件整理
│   ├── scan/         目录扫描、番号识别、预览/提交
│   ├── scraper/      各站点爬虫、并发链、翻译、图片下载
│   └── SinJavApplication.kt
├── src/main/resources/
│   ├── application.yaml
│   ├── db/migration/{sqlite,postgresql}/   Flyway 建表脚本
│   └── ca.crt                             过盾代理 CA（可选）
├── sinjav_web/       前端源码（Vite 项目）
├── docs/
│   ├── openapi.yaml         完整 API 契约（18 条路径）
│   └── SESSION_HANDOFF.md   开发交接快照，部分描述可能已过时
└── build.gradle
```

## API

统一前缀 `/api/v1`，大致分四类：

- **触发**：`POST /scans`、`/scans/preview`、`/scans/commit`、`/scrape`、`/organize`、`/organize/preview`、`/pipeline`
- **查询**：`GET /movies`、`/movies/{number}`、`/file-records`、`/organize/records`、`/jobs`、`/jobs/{jobId}`
- **配置**：`GET|PUT /settings`、`DELETE /movies/{number}`
- **文件**：`GET /fs/roots`、`/fs/dirs`、`/videos`（支持 Range 播放）、`/images`（域名白名单代理）

完整请求/响应结构见 `docs/openapi.yaml`。

**当前版本没有任何鉴权。** `/api/v1/videos?path=...` 和 `/api/v1/fs/dirs?path=...` 会读取调用方指定的本地路径，因此请只在本机或可信网络里使用，不要把服务暴露到公网。删除影片只删数据库记录和本地图片缓存，**不会删除视频文件**。

## 验证

```bash
./gradlew test          # 后端单元测试与集成测试
cd sinjav_web && npm run typecheck   # 前端类型检查
```

后端测试覆盖了番号识别、命名模板、NFO 生成、扫描预览、各站点解析以及一条「扫描 → 复制整理 → NFO」的全链路集成测试。

## 已知限制

- 刮削器的站点选择器尚未在真实网络环境里充分验证，实际部署时可能需要根据站点改版微调选择器或 Cookie。
- 没有鉴权、没有多用户、没有定时任务；设计目标就是本机单人使用。
- 只有 SQLite 和 PostgreSQL 的迁移脚本，暂无 MySQL 方言。
- `docs/SESSION_HANDOFF.md` 是某次开发的交接快照，里面的测试数量、目录路径等描述可能与当前代码不一致，以源码为准。
