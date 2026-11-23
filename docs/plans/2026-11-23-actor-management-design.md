# 演员管理（演员库 + 头像刮削）设计

日期：2026-11-23
状态：已评审通过

## 背景与目标

新增演员管理功能：从本地已刮削媒体和 Emby 上（能识别到番号的媒体）读取女演员，
刮削对应照片。调研结论：

- JavSP 只有"半成品"：`javbus`/`jav321` 站点解析器抓女优头像 URL（`MovieInfo.actress_pics`），
  仅写入 NFO `<actor><thumb>`；无下载、无演员库、无 Emby 联动。
- MDCx（`sqzw-x/mdcx`，`tools/emby_actor_image.py` / `emby_actor_info.py`）实现了完整功能：
  `/emby/Persons` 拉演员列表 → Gfriends 头像库（Filetree.json 缓存 + `{名}.jpg` 精确匹配）/
  graphis.ne.jp / 本地头像夹三级来源 → 下载裁 2:3 → Base64 上传到 `Items/{personId}/Images/Primary`；
  另有 Kodi `.actors` 模式、Wikipedia+本地库的演员资料补全、刮削后自动补全（ACTOR_PHOTO_AUTO）。
- Gfriends（`gfriends/gfriends`）：社区女优头像仓库，`Content/{上传者}/{名}.jpg`，
  `Filetree.json` 为索引，按 `{日文名}.jpg` 精确匹配 → **别名（中文名→日文名）映射是命中率关键**。

## 已确认的决策

- 头像数据源：**Gfriends 头像库为主**（不做站点实时抓取兜底，留待后续）。
- 存储：**本地 `data/actors/` + 可选推送 Emby**（Emby 推送目标为独立的 Person 条目，与影片无关）。
- 演员来源：本地用 `actresses` 表；Emby 侧只取 `emby_items` 中已识别番号条目的演员
  （不做 `/Persons` 全量拉取，避免混入非 AV 库演员）。
- 后端代码新包 `top.sincs.sinjav.actor`。
- 功能范围：演员列表页、演员详情（含别名编辑、所属影片）、刮削后自动补头像、别名映射编辑。

## 数据模型（V5__actress_photo.sql，sqlite + postgresql 双套）

- `actresses` 加列：`photo_path TEXT NULL`、`alias TEXT NULL`（规范名，用于匹配与文件名）、
  `emby_person_id TEXT NULL`、`updated_at`
- 新表 `actress_aliases(id, actress_id, alias UNIQUE)` —— 多别名，匹配时逐一尝试
- 新表 `gfriends_index(name UNIQUE, url)` —— Filetree.json 解析结果落库
- 本地照片：`data/actors/{规范名}.jpg`，路径相对化存 `photo_path`

## 后端（新包 actor）

1. `GfriendsService`
   - `syncIndex()`：下载 Filetree.json（URL 可配置、支持代理），解析 upsert；
     记录同步时间，超阈值（7 天）自动重同步
   - `findPhoto(name)`：按 `aliases + name` 优先级查 `{名}.jpg` / `{名}.png`
   - 下载复用 `ImageStore`（下载 + `valid_pic` 校验）
2. `ActorPhotoService`
   - `scrapePhoto(actressId)`：查索引 → 下载 → 写 `photo_path` → 配置开启且有
     `emby_person_id` 则推送 Emby
   - `scrapeAllMissing()`：批量补全（列表页按钮），异步任务 + 进度上报
   - 刮削自动补全：`ScrapeService.execute()` 收尾与 `EmbyRescrapeService` 收尾各挂一份
     `scrapeMissingForNew()`（只处理本次新演员，异步不阻塞返回）——双入口共用同一实现
3. `EmbyActorSyncService`
   - 扩展 `EmbyClient.detailFrom` 解析条目 `People`（Role=Actor）
   - `syncFromEmby()`：遍历 `emby_items` 已识别番号条目 → 演员名归一化合并入本地表；
     顺带读 Person 的 `ImageTags.Primary` 标记 Emby 已有头像
   - `EmbyClient` 新增 `listPersons(searchTerm?)`；推送复用 Base64 POST 到
     `Items/{personId}/Images/Primary`

## API（ActorController，`/api/v1/actors`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/actors` | 分页列表 `?page&size&search&hasPhoto` |
| GET | `/actors/{id}` | 详情 + 所属影片列表 |
| PATCH | `/actors/{id}` | 编辑 `{alias, aliases[]}` |
| POST | `/actors/sync-emby` | Emby 演员同步（异步任务） |
| POST | `/actors/scrape-photos` | 批量补全 `{actorIds?}` |
| POST | `/actors/{id}/scrape-photo` | 单个补全 |
| POST | `/actors/{id}/push-emby` | 推送 Emby（可 `{overwrite}`） |
| GET/POST | `/gfriends/status` / `/gfriends/sync` | 索引状态 / 手动同步 |

静态图片：`data/actors/` 挂静态资源，与封面同机制。

## 前端（sinjav_web）

- `ActorsView.vue`（路由 `/actors`，侧边栏"演员"）：头像网格（懒加载、无头像灰块）、
  搜索 + 有/无头像过滤；工具栏：补全头像 / 同步 Emby 演员 / Gfriends 索引更新；
  进度复用 ScrapeDialog 的任务进度模式
- `ActorDetailDialog.vue`：大图 + 别名 tag 编辑 + 所属影片卡片（跳 `/movies?number=`）+
  单演员"重新抓取头像 / 推送 Emby"
- 别名编辑合并进详情，不单独做页面

## 错误处理与边界

- Filetree 下载失败：保留旧索引；首次失败则任务中止并报错
- 索引无该演员：标记失败原因，占位显示；不做实时抓取兜底
- 图片校验失败：删文件、不写路径、计失败
- 同一演员多写法：同步/补全前 `normalize` 合并（`movie_actresses` 关联迁移，空行清理）
- 改规范名：照片文件改名、`emby_person_id` 失效重查
- 别名唯一性冲突：409 + 前端提示
- Emby 未配置/失败：返回明确错误码，本地功能不受影响
- 同名 Person 歧义：取名字完全相等者；仍多个则跳过记日志
- Emby 已有头像默认跳过（可 overwrite 强制）
- 本地有照片但 Emby 无该 Person：提示不报错
- 批量任务可取消、可复用进行中任务；单演员限速 1.5s（对齐 MDCx `scrap_interval`）

## 后续可选（第一版不做）

- 站点头像实时抓取兜底（对齐 JavSP `actress_pics`，javbus/javdb avatar 解析）
- graphis.ne.jp 头像/背景图
- 演员资料补全（Wikipedia / minnano-av）
