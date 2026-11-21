# JavSP 数据源机制研究（复刻参考）

> 基于 Yuukiy/JavSP（master 分支）源码逐文件分析：
> `javsp/__main__.py`（并发抓取+汇总）、`javsp/datatype.py`（数据模型）、`javsp/web/*.py`（16 个爬虫）、`config.yml`（默认流水线）。
> 本文回答三个问题：**用了哪些数据源、来源优先级怎么运作、每个源刮什么字段**。

---

## 一、数据模型：共 24 个字段（MovieInfo）

| 字段 | 含义 | 备注 |
|---|---|---|
| dvdid / cid | 番号 / DMM Content ID | 二选一，决定走 normal 还是 cid 流水线 |
| title / ori_title | 标题（不含番号）/ 原始标题 | ori_title 仅在标题被处理过时赋值 |
| plot | 简介 | |
| cover / big_cover | 封面 URL / 高清封面 URL | big_cover 是「按 DMM 命名规律猜测的高清地址」 |
| preview_pics / preview_video | 剧照 URL 列表 / 预览视频 URL | |
| genre / genre_id / genre_norm | 标签 / 标签ID / 统一后标签 | genre_id 解决同名 genre 与多语言映射（CSV 映射表） |
| actress / actress_pics | 女优 / 女优头像（dict） | 头像单列以便别名归一后同步替换 key |
| director / producer / publisher | 导演 / 制作商 / 发行商 | |
| serial | 系列 | |
| duration / publish_date / score | 时长 / 发布日期 / 评分（10 分制字符串） | |
| uncensored | 是否无码 | |
| magnet | 磁力链接 | 不写入 NFO 主信息，仅记录 |
| url | 影片页面 URL | |

---

## 二、数据源全景：16 个爬虫及字段覆盖

按番号类型分为 5 条流水线（`config.yml: crawler.selection`），**列表顺序即优先级**：

| 类型 | 默认顺序 |
|---|---|
| **normal**（有码常规） | `airav → avsox → javbus → javdb → javlib → jav321 → mgstage → prestige` |
| **fc2** | `fc2 → avsox → javdb → javmenu → fc2ppvdb` |
| **cid**（Fanza CID） | `fanza` |
| **getchu** | `dl_getchu` |
| **gyutto** | `gyutto` |

可用爬虫全集（normal 流水线可自由增删）：`airav, avsox, avwiki, fanza, fc2, fc2fan, javbus, javdb, javlib, javmenu, jav321, mgstage, prestige, arzon, arzon_iv`。

### 各爬虫实际填充的字段（源码统计）

| 爬虫 | 填充字段 |
|---|---|
| **javbus** | title, cover, actress, actress_pics, director, duration, genre, genre_id, genre_norm, producer, publisher, serial, uncensored, preview_pics, url |
| **javdb** | title, **ori_title**, cover, actress, director, duration, genre, genre_id, genre_norm, producer, publisher, serial, uncensored, score, **magnet**, preview_pics, preview_video, url |
| **airav** | title, cover, actress, genre, plot, producer, publish_date, preview_pics, preview_video, url |
| **avsox** | title, cover, actress, genre, duration, producer, serial, publish_date, url |
| **avwiki** | title, cover, uncensored, url（只做轻量补充，主要贡献无码判定） |
| **javlib** | title, cover, actress, director, duration, genre, producer, publisher, score, publish_date, url |
| **jav321** | title, cover, actress, actress_pics, genre, genre_id, plot, producer, serial, score, duration, publish_date, preview_pics, preview_video, url |
| **mgstage** | title, cover, actress, genre, plot, producer, serial, score, duration, uncensored, publish_date, preview_pics, preview_video, url |
| **prestige** | title, cover, actress, genre, plot, serial, duration, uncensored, publish_date, preview_pics, url |
| **fanza**（cid 流水线） | title, cover, actress, director, duration, genre, genre_id, plot, producer, serial, score, uncensored, publish_date, preview_pics, preview_video, url（字段最全的单一源） |
| **arzon** | title, cover, actress, director, duration, genre, plot, producer, serial, publish_date, preview_pics, url（需 Cookie，age 校验） |
| **arzon_iv** | title, cover, actress, director, duration, genre, plot, producer, publish_date, url |
| **fc2**（fc2 流水线） | title, cover, genre, producer, score, duration, publish_date, preview_pics, preview_video, url |
| **fc2ppvdb** | title, cover, actress, genre, publisher, uncensored, duration, publish_date, preview_pics, preview_video, url |
| **javmenu** | title, cover, actress, genre, genre_id, producer, duration, magnet, publish_date, preview_pics, preview_video, url |
| **fc2fan** | title, cover, actress, genre, producer, score, uncensored（镜像站，默认不启用） |
| **dl_getchu / gyutto** | title, cover, actress, genre, plot, producer, duration, publish_date, preview_pics, url |

**观察结论（复刻时取舍依据）**：
- **javbus + javdb 覆盖了几乎所有字段**（两者互补：javbus 有 plot 之外的稳定元数据，javdb 独有 ori_title/magnet/score/中文名）
- **plot 主要靠 airav/jav321/mgstage/prestige/fanza/gyutto**（javbus/javdb 多数影片无剧情简介，javdb plot 截断 2000 字符）
- **actress_pics 只有 javbus/jav321 提供**；**magnet 只有 javdb/javmenu**
- fanza（cid 模式）是单一源但字段最全，DMM 官方数据质量最高

---

## 三、来源优先级机制（info_summary 源码逐行拆解）

### 3.1 并发抓取，按优先级排序的字典承载

`parallel_crawler()`：按 `crawler.selection[movie.data_src]` 的顺序为每个爬虫创建**独立的 MovieInfo 实例**（dict 的插入顺序 = 优先级顺序），多线程并发抓取，失败的源直接从 dict 中删除。

特殊：cid 模式下若同时有有效 dvdid，会**额外跑一遍 normal 流水线**，再按抓取结果自动更正影片类型（cid 或 normal）。

### 3.2 字段级合并：first-non-empty-wins

`info_summary()` 遍历 all_info（按优先级顺序），对每个字段：**final_info 当前为空 且 该源有值 → 采用**。由于 dict 顺序即优先级，等价于「每个字段取优先级最高的非空值」。

### 3.3 特殊字段的专门规则（重点！）

1. **genre 例外**：javdb 的 genre 存在则**无条件优先**采用（javdb 标签分类最规范），否则走 first-non-empty
2. **cover / big_cover 收集为有序列表**：不去重地按优先级追加进 `covers` / `big_covers`，最终 `cover = covers[0]`——这是**候选列表**而非单值
3. **javdb 封面降级**（`use_javdb_cover` 配置）：
   - `fallback`（默认）：javdb 封面**移到 covers 列表末尾**——其他源都没有封面时才用（javdb 封面有站点水印）
   - `no`：直接从候选中移除
4. **uncensored 单独处理**：取第一个非 None 值（布尔语义，不能用「非空」判断）
5. **番号投票**（`respect_site_avid`，默认 true）：统计各源返回的番号，**出现次数最多（权重最高）的番号胜出**——用站点数据修正本地识别错误
6. **女优别名归一**（`normalize_actress_name`）：有 actress_pics 时，按 `data/actress_alias.json` 把艺名解析为固定名
7. **标题尾部女优名清理**（`remove_trailing_actor_name`）：各源标题去掉结尾的女优名
8. **合成 genre**：硬字幕 → 追加「内嵌字幕」；无码流出/破解文件 → 追加「无码流出/破解」
9. **required_keys 终检**（默认 `[cover, title]`）：任何一个必需字段所有源都拿不到 → 整部影片判为抓取失败

### 3.4 封面下载的优先级（download_cover）

- `cover.highres: true`（默认开）时：**先试 big_covers（按 DMM 命名规律猜测的高清地址）**，HTTPError 不重试直接换下一个；都失败再退回 covers 列表逐个试
- 下载后 `valid_pic` 校验图片完整性，无效则换候选下一个
- **fanart = 下载到的完整封面原图；poster = 裁剪结果**

### 3.5 裁剪规则（process_poster）

- 默认 `DefaultCropper`：海报比例 w:h = 1:1.42，对横版全封套（`h/w < 1.42`）按 `posterW = h/1.42` **右对齐裁取**（DMM 扫描前封面固定在右侧），确定性规则、无识别
- **AI 裁剪触发条件**（配置 `cover.crop.engine`，基于 slimeface 人脸检测）：影片为无码（uncensored）/ fc2 流水线 / label 匹配 `crop.on_id_pattern` 正则（素人系番号）——这几类封面不是标准双联封套
- 可选水印标签：硬字幕右下角、无码左下角贴 PNG
- 剧照下载到 `extrafanart/` 目录（`extra_fanarts.enabled`，带间隔防封）

---

## 四、对本项目的对照结论

| 机制 | JavSP | sinJav 现状 | 差距/可借鉴 |
|---|---|---|---|
| 流水线分型 | 按番号类型 5 条 pipeline | 全番号同一组源 | 可加 FC2 专门流水线 |
| 优先级 | 顺序字典 + first-non-empty | source-order 排序 pick | 等价 |
| genre 例外 | javdb 无条件优先 | 顺序 pick | 若要标签统一可借鉴 |
| 封面候选列表 | covers 有序列表 + 逐个试下载 | 单值 coverUrl | 可改为候选列表（下载失败自动换源） |
| javdb 封面降级 | fallback/no 配置 | 无（javdb 排前面就用它的） | ❗建议实现 fallback |
| 高清封面猜测 | big_covers 优先 | 无 | DMM pl.jpg 猜测规律可借鉴 |
| 番号投票 | 多源番号按出现次数投票 | 单纯 normalize | 识别容错可借鉴 |
| 女优别名归一 | actress_alias.json | 无 | 可选 |
| required_keys | [cover, title] 终检 | title 硬要求 | 可补 cover |
| AI 裁剪触发 | 无码/fc2/素人番号 → 人脸裁剪 | FC2 直接不裁 | 素人系可按 label 正则走 AI 或不裁 |

---

## 五、出处索引

- 合并逻辑：`javsp/__main__.py` → `parallel_crawler()`（并发与流水线选择）、`info_summary()`（字段级合并与特殊规则）、`download_cover()`（封面候选下载）、`process_poster()`（裁剪触发）
- 数据模型：`javsp/datatype.py` → `MovieInfo`（24 字段）、`Movie.data_src`（流水线类型）
- 默认流水线与开关：`config.yml` → `crawler.selection` / `required_keys` / `use_javdb_cover` / `respect_site_avid` / `normalize_actress_name` / `cover.highres`
- 字段覆盖统计：`javsp/web/{airav,avsox,avwiki,fanza,fc2,fc2fan,javbus,javdb,javlib,javmenu,jav321,mgstage,prestige,arzon,arzon_iv,dl_getchu,gyutto,fc2ppvdb}.py`
- 裁剪：`javsp/cropper/{interface,slimeface_crop}.py`
