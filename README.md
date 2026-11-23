# sinJav

一个跑在本机的 JAV 影片库整理工具：把你已有的视频文件扫描入库，从文件名识别作品编号，自动抓取并翻译元数据，再按自己的命名规则整理成 Kodi / Jellyfin / Emby 能直接识别的媒体库结构。带一个 Vue 管理界面（需登录），也可以用 API 操作。

它不下载影片，只管理你本地已有的文件。除「移动」模式外，整理过程不会改动源目录里的视频（默认用硬链接）。

## 它能做什么

整个流程分成几块，界面上从仪表盘的「扫描 / 刮削 / 整理」按钮分别触发：

1. **扫描**：递归遍历指定目录，按扩展名和大小（默认 ≥ 50 MB）筛选视频，从文件名识别作品编号。先「预览」、把不要的文件移除，再「确认」同步落库，避免误入库。文件记录以路径为键、用大小和修改时间做指纹，重复扫描时指纹未变的文件直接跳过，实现增量扫描；改名或换目录会被当作新文件。扫描结果列表里内置在线预览播放器（HTTP Range 分片，只读源文件，支持 mp4/m4v/webm/mov）。
2. **刮削**：并发请求 JavBus / JavDB / MissAV，默认按 `javbus → javdb → missav` 的优先级合并字段。刮削结果以**对比界面**呈现，每个字段可以：
   - 单独选择落库来源（任一数据源 / 手动输入 / 留空），切换来源自动填入该源的原始值；
   - 直接在输入框里编辑，「保存即所见」，落库时不再二次加工；
   - 点「翻译」按钮同步翻译/繁转简标题、简介、类别（DeepL 优先、百度兜底），回填后仍可手动修改；
   - 番号本身可编辑，改完可重新刮削，落库时按新番号改名并同步文件记录关联。
   标题统一带番号前缀，日文汉字用 opencc 转简体，原标题保留在 `title_orig`。另支持「手动落库」：不抓取不翻译，直接录入元数据并上传封面/剧照。
3. **整理**：按模板（默认 `{number}/{number}`，即转移后的文件名就是 `番号.原扩展名`）生成目录和文件名，支持硬链接 / 软链接 / 移动 / 复制四种模式（可逐条覆盖），冲突可选跳过 / 覆盖 / 重命名，先 `organize/preview` 逐条预览/改路径再执行。目标目录与整理方式不再走全局配置，每次整理时显式指定。同时写出 `movie.nfo` 和封面、剧照（`{title}` 用原始标题，路径里不用译名）。
4. **管理**：Web 界面里浏览影片库、查看文件记录页、跟踪异步任务进度、修改运行时配置与账号，删除影片（只删数据库记录和本地图片缓存，不动视频文件）。
5. **Emby 集成**：在设置页配置 Emby 服务地址和 API Key 后，影片库页可切换到 Emby 数据源，浏览指定媒体库的条目（实时代理，不落库，封面经后端转发、不暴露 API Key）。Emby 条目支持：
   - 直接编辑元数据（标题、简介、类别、标签等）回写 Emby；
   - 「重新刮削」：从条目的文件名/原标题/标题识别番号（识别失败可手动输入），用本应用刮削管线刮取元数据，以与本地刮削一致的逐字段对比界面确认后回写 Emby（不触发 Emby 自身刮削）。
6. **演员管理**：演员从「刮削结果的女优字段」和「Emby 条目演员」两条路进来，自动建库。演员页提供头像网格（搜索 + 有/无头像过滤）、演员详情（别名 tag 编辑、所属影片）、批量补全头像（异步任务、可停止）与 Emby 演员同步。头像来自 [Gfriends](https://github.com/gfriends/gfriends) 头像库：点一次「更新 Gfriends 索引」把索引入库，之后**刮削/重刮/Emby 同步收尾都会自动给新演员补头像**（`sinjav.actors.auto-scrape-after-batch`）；别名（中文名/英文名 → 日文名）决定匹配命中率，改完可重抓。已缓存 `Person Id` 的演员还能把头像推回 Emby Person 主图。详见 `docs/guides/actor-management-guide.md`。
7. **登录与账号**：单用户登录。首次启动进入初始化向导创建管理员账号（用户名 + 密码，BCrypt 存储）；登录建立 30 天会话 Cookie（HttpOnly、SameSite=Lax，访问自动续期），连续 5 次失败锁定 15 分钟；设置页可验证当前密码后修改用户名/密码。

除扫描确认（同步落库）外，刮削、整理等都是后台任务，提交后在「任务」页看进度和失败明细。所有操作都由 API 或界面手动触发，**没有定时任务**，也不会在后台偷偷扫描你的磁盘。（流水线 API `POST /pipeline` 仍保留，可把扫描 → 刮削 → 整理一次串起来，但界面上已无对应按钮。）

## 技术栈

| 层 | 选型 |
| --- | --- |
| 后端 | Kotlin 2.3.21 + Spring Boot 4.1.2（Java 17） |
| 安全 | Spring Security：会话登录 + BCrypt 凭据 + 内存防爆破锁定 |
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

1. 打开页面会跳转登录页；首次启动登录页是**初始化向导**，创建管理员账号后自动登录。
2. 进「仪表盘」点「扫描」，选择源目录，预览结果里移除不要的文件，点「确认」落库。
3. 点「刮削」补全元数据：在对比界面里逐字段选来源、编辑内容、按需翻译，确认后落库；必要时在设置里填 JavDB Cookie 或翻译 API Key。
4. 点「整理」，在弹窗里指定目标目录和整理模式，先预览再执行。

### 4. Docker 部署（可选）

仓库里带了一个联合部署的 `docker-compose.yml`，一次拉起三个容器：

| 服务 | 说明 |
| --- | --- |
| `sinjav` | 本应用（Web + API，端口 8656），同时托管前端静态资源，浏览器直接开 `http://localhost:8656/` |
| `trawl` | Cloudflare 过盾代理（MITM），sinjav 通过 `http://trawl:8192` 走它访问需要过盾的数据源（默认 javdb） |
| `redis` | trawl 的会话缓存，仅内部网络访问 |

compose **不本地构建**，需要先把镜像准备好：

```bash
docker build -t sinjav:latest .    # 多阶段构建：前端 dist + 后端 bootJar，单容器同源部署
docker compose up -d
```

启动时 `sinjav` 会等 `trawl` 健康检查通过；`trawl` 生成的 CA 证书通过共享数据卷只读挂载给 `sinjav`（`SINJAV_SCRAPER_CF_CA_CERT_PATH=/ca/trawl_ca.pem`）。数据落在 `./data`（SQLite + 图片缓存），视频目录通过 `SINJAV_MEDIA_DIR` 环境变量指定（默认 `./media`），整理时按容器内路径 `/media/...` 填写。

**如果要使用软链接整理模式，请保证容器内映射路径和宿主机系统路径保持严格一致**（例如宿主机 `/data/media` 映射为容器内 `/data/media`，而不是映射到 `/media`），否则生成的软链接在容器外会指向不存在的路径，软链接无法生效。

## 配置

配置分两层：

- **`src/main/resources/application.yaml`**：启动级配置（数据库、端口、代理、翻译密钥、线程池等）。其中 `SINJAV_DB_URL` / `SINJAV_DB_USER` / `SINJAV_DB_PASSWORD` 环境变量可以把数据库切到 PostgreSQL，例如 `SINJAV_DB_URL=jdbc:postgresql://host:5432/sinjav`。
- **设置页（`GET/PUT /api/v1/settings`）**：运行期配置存在数据库的 `settings` 表里，改完立即生效，重启后依然保留。刮削源顺序、JavDB Cookie、DeepL / 百度翻译密钥、Emby 连接、图片缓存清理这些都可以直接在界面上改；密钥在日志里会打码成 `***`。登录凭据（`authUsername` / `authPasswordHash`）也存这张表，但刻意不经 Settings API 返回，只能通过账号管理接口修改。

几个容易踩坑的点：

- 刮削有请求间隔（默认 1.5 秒）和全局并发限制（默认 1），别为了快把它们调得太激进。
- JavDB 部分信息需要登录，要填 Cookie；遇到 Cloudflare 挑战时可以配置过盾代理（`cf-proxy-url` + `cf-proxy-sources`）和对应 CA 证书。
- 硬链接要求源和目标在同一文件系统；跨盘会失败，这种情况改用复制或移动。Windows 下软链接需要管理员权限或开发者模式。
- SQLite 是单写者，适合个人本机使用；多实例部署请换 PostgreSQL。

## 项目结构

```
sinjav/
├── src/main/kotlin/top/sincs/sinjav/
│   ├── actor/        演员库：Gfriends 索引/头像抓取、别名归一化、Emby 演员同步
│   ├── api/          REST 控制器（含认证 AuthController）、DTO、静态资源与本地图片映射
│   ├── common/       全局异常处理
│   ├── config/       配置属性、安全配置、认证服务、线程池、运行时设置服务
│   ├── domain/       数据模型与 JDBC Repository
│   ├── job/          异步任务的创建、互斥与执行
│   ├── organize/     命名模板、链接策略、NFO 与文件整理
│   ├── scan/         目录扫描、番号识别、预览/提交
│   ├── scraper/      各站点爬虫、并发链、刮削对比、翻译、图片下载
│   └── SinJavApplication.kt
├── src/main/resources/
│   ├── application.yaml
│   ├── db/migration/{sqlite,postgresql}/   Flyway 建表脚本
│   └── ca.crt                             过盾代理 CA（可选）
├── sinjav_web/       前端源码（Vite 项目）
├── docs/
│   ├── openapi.yaml         完整 API 契约（43 条路径）
│   ├── plans/               功能设计文档（如演员管理）
│   ├── guides/              面向使用者的功能指南
│   └── SESSION_HANDOFF.md   开发交接快照，部分描述可能已过时
├── Dockerfile        多阶段镜像构建（前端 dist + 后端 bootJar → sinjav:latest）
├── docker-compose.yml   sinjav + trawl（CF 过盾代理）+ Redis 联合部署
└── build.gradle
```

## API

统一前缀 `/api/v1`，除认证接口外均需登录会话；当前共 43 条路径，大致分：

- **认证**：`GET /auth/status`、`POST /auth/init`、`POST /auth/login`、`POST /auth/logout`、`PUT /auth/credentials`
- **触发**：`POST /scans`、`/scans/preview`、`/scans/commit`、`/scrape`、`/scrape/preview`、`/scrape/apply`、`/scrape/translate`、`/scrape/manual`（multipart）、`/organize`、`/organize/preview`、`/pipeline`（保留，界面无按钮）
- **查询**：`GET /movies`、`/movies/{number}`、`DELETE /movies/{number}`、`/file-records`、`/organize/records`、`/jobs`、`/jobs/{jobId}`
- **配置**：`GET|PUT /settings`、`POST /settings/images/cleanup`
- **Emby**：`GET /emby/status`、`/emby/libraries`、`/emby/items`、`/emby/items/{id}`、`PUT /emby/items/{id}/metadata`、`POST /emby/items/{id}/rescrape/preview`、`POST /emby/items/{id}/rescrape/apply`、`GET /emby/items/{id}/images/primary`
- **演员**：`GET /actors`、`GET|PATCH /actors/{id}`、`POST /actors/{id}/scrape-photo`、`POST /actors/scrape-photos`、`POST /actors/sync-emby`、`GET /gfriends/status`、`POST /gfriends/sync`（同义路径 `/actors/gfriends/*`）
- **文件**：`GET /fs/roots`、`/fs/dirs`、`/videos`（支持 Range 播放）、`/images`（域名白名单代理）

完整请求/响应结构见 `docs/openapi.yaml`。

有单用户登录鉴权（防爆破：连续 5 次失败锁定 15 分钟），但仍要注意：`/api/v1/videos?path=...` 和 `/api/v1/fs/dirs?path=...` 会读取调用方指定的本地路径，请只在本机或可信网络里使用，不要把服务暴露到公网。删除影片只删数据库记录和本地图片缓存，**不会删除视频文件**。

## 验证

```bash
./gradlew test                  # 后端单元测试与集成测试
./gradlew jacocoTestReport      # 覆盖率报告（build/reports/jacoco/test/html/index.html）
cd sinjav_web && npm run typecheck   # 前端类型检查
```

后端测试覆盖了番号识别、命名模板、NFO 生成、扫描预览、各站点解析、认证链路与防爆破锁定、
演员管理（Gfriends 索引/头像抓取/别名归一化/Emby 演员同步），以及两条完整链路集成测试：
「扫描 → 复制整理 → NFO」与「刮削 → 新演员自动补头像 → Emby 同步 → 演员列表 API」。

## 已知限制

- 刮削器的站点选择器尚未在真实网络环境里充分验证，实际部署时可能需要根据站点改版微调选择器或 Cookie。
- 单用户、无多用户、无定时任务；设计目标就是本机单人使用。
- 只有 SQLite 和 PostgreSQL 的迁移脚本，暂无 MySQL 方言。
- 演员头像只有 Gfriends 一个来源（精确匹配、区分大小写）；演员详情里的「推送到 Emby」按钮尚未实现后端接口（点击提示「开发中」），头像下载成功后会自动推送 Person 主图。
- `docs/SESSION_HANDOFF.md` 是某次开发的交接快照，里面的测试数量、目录路径等描述可能与当前代码不一致，以源码为准。
