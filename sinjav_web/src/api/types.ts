// 与 docs/openapi.yaml 对齐的类型定义

export type JobType = 'scan' | 'scrape' | 'organize' | 'pipeline'
export type JobStatus = 'running' | 'completed' | 'failed' | 'cancelled'

// ---------- 登录认证 ----------

export interface AuthStatus {
  initialized: boolean
  username?: string | null
  authenticated: boolean
}

export interface LoginResult {
  username: string
}

export interface CredentialsUpdate {
  currentPassword: string
  username?: string
  newPassword?: string
}
export type MovieStatus = 'pending' | 'scraped' | 'failed'
export type RecognizeStatus = 'recognized' | 'unrecognized' | 'filtered'
export type OrganizeStatus = 'pending' | 'organizing' | 'organized' | 'failed' | 'skipped'
export type LinkMode = 'hardlink' | 'symlink' | 'move' | 'copy'
export type ConflictPolicy = 'skip' | 'overwrite' | 'rename'
export type ScraperSource = 'javbus' | 'javdb' | 'missav'
export type MovieSort = 'release_date' | 'number' | 'created_at'

export interface PageMeta {
  page: number
  size: number
  total: number
  totalPages: number
}

export type Paged<T> = PageMeta & { items: T[] }

// ---------- 影片 ----------
export interface MovieSummary {
  number: string
  title?: string | null
  /** 原名（源站原始标题，日文/繁体） */
  titleOrig?: string | null
  releaseDate?: string | null
  maker?: string | null
  actresses: string[]
  coverUrl?: string | null
  status: MovieStatus
  plot?: string | null
  /** 该番号已存在于 Emby 媒体库（依据最近一次「同步 Emby 库」快照） */
  embyExists?: boolean
}

export interface Movie extends MovieSummary {
  rawNumber?: string | null
  titleZh?: string | null
  titleOrig?: string | null
  director?: string | null
  label?: string | null
  series?: string | null
  durationMin?: number | null
  previewUrls: string[]
  /** 封面实际生效来源（候选回退后可能与首位候选不同） */
  coverSource?: string | null
  /** 剧照来源数据源 id */
  previewSource?: string | null
  /** 封面候选列表（按优先级排序，javdb 沉底；下载失败可逐候选重试） */
  coverCandidates?: CoverCandidate[]
  genres: string[]
  plot?: string | null
  source?: ScraperSource | 'merged' | null
  failReason?: string | null
  scrapedAt?: string | null
  createdAt: string
  updatedAt: string
}

/** 封面候选项：URL + 来源数据源 id（下载失败自动换下一候选） */
export interface CoverCandidate {
  url: string
  source?: string | null
}

export interface MovieDetail extends Movie {
  fileRecords: FileRecord[]
}

// ---------- 文件记录 ----------
export interface FileRecord {
  id: number
  sourcePath: string
  fileName: string
  fileSize: number
  fileMtime?: string
  number?: string | null
  recognizeStatus: RecognizeStatus
  targetPath?: string | null
  linkMode?: LinkMode | null
  organizeStatus: OrganizeStatus
  organizeError?: string | null
  createdAt: string
}

// ---------- 任务 ----------
export interface JobError {
  item: string
  reason: string
}

export interface Job {
  id: number
  type: JobType
  status: JobStatus
  params?: Record<string, unknown>
  totalItems: number
  doneItems: number
  failItems: number
  errors: JobError[]
  startedAt: string
  finishedAt?: string | null
}

export interface JobAccepted {
  jobId: number
  type: JobType
  status: JobStatus
}

// ---------- 请求 ----------
export interface ScanRequest {
  sourceDirs: string[]
  recursive?: boolean
  extensions?: string[]
  minFileSizeMb?: number
  excludePaths?: string[]
  filenamePattern?: string
}

export interface FsNode {
  path: string
  name: string
}

export interface FsDirListing {
  path: string
  directories: FsNode[]
}

export interface ScanPreviewRequest {
  sourceDirs: string[]
  recursive?: boolean
  extensions?: string[]
  minFileSizeMb?: number
  filenamePattern?: string
}

export interface ScanPreviewItem {
  sourcePath: string
  fileName: string
  fileSize: number
  fileMtime: string | null
  number: string | null
  recognizeStatus: 'recognized' | 'unrecognized'
}

export interface ScanPreviewResponse {
  items: ScanPreviewItem[]
  total: number
  truncated: boolean
}

export interface ScanCommitItem {
  sourcePath: string
  fileName: string
  fileSize: number
  fileMtime?: string | null
}

export interface ScanCommitResponse {
  total: number
  recognized: number
  unrecognized: number
}

export interface ScrapeRequest {
  numbers?: string[]
  sources?: ScraperSource[]
}

export interface OrganizeItemRequest {
  recordId: number
  /** 完整目标路径（目录+文件名）；省略时按命名模板渲染 */
  targetPath?: string
  /** 整理方式覆盖；省略时使用请求级 mode */
  mode?: LinkMode
}

export interface OrganizeRequest {
  /** 目标目录：必填（整理不再使用全局配置） */
  targetDir: string
  /** 整理方式：必填（整理不再使用全局配置） */
  mode: LinkMode
  namingTemplate?: string
  conflictPolicy?: ConflictPolicy
  generateNfo?: boolean
  downloadImages?: boolean
  /** 只整理视频：仅转移视频文件，不生成 nfo/图片（适配 Emby 已存在媒体的多分辨率入库） */
  videoOnly?: boolean
  numbers?: string[]
  /** 按文件记录逐条整理（可覆盖目标路径/方式），优先于 numbers；organized 记录视为重整 */
  items?: OrganizeItemRequest[]
}

// ---------- 整理弹窗 ----------

export interface OrganizeRecordItem {
  recordId: number
  sourcePath: string
  fileName: string
  fileSize: number
  number?: string | null
  title?: string | null
  coverUrl?: string | null
  organizeStatus: OrganizeStatus
  targetPath?: string | null
  linkMode?: LinkMode | null
}

export interface OrganizePreviewRequest {
  recordIds: number[]
  /** 目标目录：必填（整理不再使用全局配置） */
  targetDir: string
  /** 整理方式：必填（整理不再使用全局配置） */
  mode: LinkMode
  namingTemplate?: string
  conflictPolicy?: ConflictPolicy
  /** 只整理视频：仅计算视频目标路径，不生成 nfo/图片 */
  videoOnly?: boolean
}

export interface OrganizePreviewItem {
  recordId: number
  sourcePath: string
  fileName: string
  number?: string | null
  title?: string | null
  coverUrl?: string | null
  targetPath?: string | null
  mode?: LinkMode | null
  conflictPolicy?: ConflictPolicy | null
  reorganize: boolean
  currentTargetPath?: string | null
  currentLinkMode?: LinkMode | null
  error?: string | null
}

export interface OrganizePreviewResponse {
  items: OrganizePreviewItem[]
}

export interface PipelineRequest {
  scan?: ScanRequest
  scrape?: ScrapeRequest
  organize?: OrganizeRequest
}

// ---------- 多源对比刮削 ----------

/** 单字段来源选择：source=null 留空；'manual' 手动输入（value 为用户输入）；其余为数据源 id */
export interface ScrapeFieldChoice {
  source?: string | null
  value?: string | null
}

export interface ScrapePreviewResponse {
  number: string
  /** 有结果的来源 id（按 source-order 排序） */
  sources: string[]
  /** 字段键 → 来源 id → 原文值（仅含该来源有值的字段） */
  fields: Record<string, Record<string, string>>
  /** 字段键 → 合并规则的默认来源 id（该字段所有来源均无值时为 null） */
  defaults: Record<string, string | null>
}

export interface ScrapeApplyRequest {
  /** 落库番号（对比界面可编辑修正识别错误） */
  number: string
  /** 原记录番号；与 number 不同时把该记录改用 number 落库（movies/file_records 同步改番号） */
  recordNumber?: string
  fields: Record<string, ScrapeFieldChoice>
  /** 封面候选列表（各源 coverUrl 原值）：所选封面置顶、javdb 沉底后持久化，下载失败自动换源 */
  coverCandidates?: CoverCandidate[]
}

/** 对比界面手动翻译请求：仅传需要翻译的字段（省略/空白不处理） */
export interface ScrapeTranslateRequest {
  title?: string
  plot?: string
  genres?: string
}

/** 手动翻译结果：与请求字段一一对应（翻译失败回退原文；类别以「 / 」重新拼接） */
export interface ScrapeTranslateResponse {
  title?: string | null
  plot?: string | null
  genres?: string | null
}

/** 单个数据源独立配置；留空/0 = 使用内置默认与全局配置 */
export interface SourceConfig {
  baseUrl?: string
  cookie?: string
  timeoutMs?: number
  intervalMs?: number
}

export type SettingsUpdate = {
  sourceOrder?: string
  /** 每数据源独立配置（key=数据源 id，整体替换） */
  sourceConfigs?: Record<string, SourceConfig>
  /** 刮削链·有码常规（复刻 JavSP：逗号分隔，顺序即字段优先级） */
  chainNormal?: string
  /** 刮削链·FC2（FC2-PPV 系番号） */
  chainFc2?: string
  /** 刮削链·CID（纯数字内容 ID） */
  chainCid?: string
  /** 刮削链·Getchu（GETCHU- 前缀番号） */
  chainGetchu?: string
  /** 刮削链·Gyutto（GYUTTO- 前缀番号） */
  chainGyutto?: string
  userAgent?: string
  javdbCookie?: string
  proxyHost?: string
  proxyPort?: number
  /** CF 过盾代理地址（如 trawl MITM http://127.0.0.1:8192）；空则全部走全局代理/直连 */
  cfProxyUrl?: string
  /** 需要 CF 过盾的数据源 id，逗号分隔（如 "javdb"） */
  cfProxySources?: string
  requestIntervalMs?: number
  concurrency?: number
  /** 普通请求超时 (ms) */
  timeoutMs?: number
  /** CF 过盾请求超时 (ms)：需等待 CF 挑战，通常更长 */
  cfTimeoutMs?: number
  namingTemplate?: string
  conflictPolicy?: ConflictPolicy
  generateNfo?: boolean
  downloadImages?: boolean
  /** 翻译 API：DeepL > 百度 > Google 兜底 */
  deeplApiKey?: string
  /** 自动翻译：批量刮削时翻译标题/简介/类别标签；关闭则直接落源站内容 */
  autoTranslate?: boolean
  baiduTranslateAppId?: string
  baiduTranslateSecret?: string
  /** Emby 集成：服务地址 + API Key + 独立代理（留空直连） */
  embyBaseUrl?: string
  embyApiKey?: string
  embyProxyHost?: string
  embyProxyPort?: number
  /** 图片缓存过期天数（0 = 永久保留） */
  imagesTtlDays?: number
  /** 只读：扫描全局配置（GET 返回，PUT 忽略），供扫描弹窗高级选项回填 */
  videoExtensions?: string
  minFileSizeMb?: number
  /** 扫描默认文件名匹配正则（空 = 不过滤） */
  filenamePattern?: string
}

export type Settings = SettingsUpdate & {
  /** 全部已注册数据源 id（数据源配置表的行） */
  knownSources?: string[]
}

// ---------- Emby ----------

export interface EmbyLibrary {
  id: string
  name: string
  collectionType?: string | null
}

export interface EmbyItemSummary {
  id: string
  name: string
  originalTitle?: string | null
  productionYear?: number | null
  premiereDate?: string | null
  path?: string | null
  /** 是否有主图（封面统一走 /emby/items/{id}/images/primary 代理） */
  hasImage: boolean
}

export interface EmbyPage {
  page: number
  size: number
  total: number
  items: EmbyItemSummary[]
}

export interface EmbyItemDetail extends EmbyItemSummary {
  overview?: string | null
  genres: string[]
  studios: string[]
  tags: string[]
  officialRating?: string | null
  communityRating?: number | null
  runtimeMin?: number | null
  providerIds: Record<string, string>
  dateCreated?: string | null
}

/** 元数据修改请求：仅提交需要修改的字段 */
export interface EmbyMetadataUpdate {
  name?: string
  originalTitle?: string
  overview?: string
  genres?: string[]
  studios?: string[]
  tags?: string[]
  premiereDate?: string
  productionYear?: number
  officialRating?: string
  communityRating?: number
}

/** 本地刮削预览：刮削前后元数据对比，确认后再应用 */
export interface EmbyRescrapePreview {
  itemId: string
  /** 从条目文件名/原标题/标题识别出的番号 */
  matchedNumber: string
  /** 刮削数据源 */
  source: string
  /** 刮削前的当前 Emby 元数据 */
  current: EmbyItemDetail
  /** 刮削得到的元数据（待确认应用） */
  proposed: EmbyMetadataUpdate
  /** 多源对比：字段键 → 来源 id → 原文值（仅含该源有值的字段） */
  fields: Record<string, Record<string, string>>
  /** 多源对比：字段键 → 合并规则的默认来源 id（该字段所有来源均无值时为 null） */
  defaults: Record<string, string | null>
  /** 多源对比：有结果的来源 id 列表（按 source-order 排序） */
  sources: string[]
}

/** 多源对比重新刮削应用请求 */
export interface EmbyRescrapeApplyRequest {
  number: string
  fields: Record<string, ScrapeFieldChoice>
}

/** 图片推送请求（重新刮削界面「推送封面/推送剧照」）：URL 省略时后端回退本地图片缓存 */
export interface EmbyImagePushRequest {
  number: string
  coverUrl?: string
  previewUrls?: string[]
}

/** 图片推送结果 */
export interface EmbyImagePushResult {
  pushed: number
}
