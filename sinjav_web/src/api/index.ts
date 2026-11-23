import type {
  ActorAliasUpdate,
  ActorDetail,
  ActorJobAccepted,
  ActorListResponse,
  ActorPhotoScrapeResult,
  AuthStatus,
  CredentialsUpdate,
  EmbyImagePushRequest,
  EmbyImagePushResult,
  EmbyRescrapeApplyRequest,
  EmbyItemDetail,
  EmbyLibrary,
  EmbyMetadataUpdate,
  EmbyRescrapePreview,
  EmbyPage,
  FileRecord,
  FsDirListing,
  FsNode,
  GfriendsSyncResult,
  Job,
  JobAccepted,
  JobStatus,
  JobType,
  LoginResult,
  MovieDetail,
  MovieSort,
  MovieStatus,
  MovieSummary,
  OrganizePreviewRequest,
  OrganizePreviewResponse,
  OrganizeRecordItem,
  OrganizeRequest,
  Paged,
  PipelineRequest,
  RecognizeStatus,
  OrganizeStatus,
  ScanCommitItem,
  ScanCommitResponse,
  ScanPreviewRequest,
  ScanPreviewResponse,
  ScanRequest,
  ScrapeApplyRequest,
  ScrapePreviewResponse,
  ScrapeRequest,
  ScrapeTranslateRequest,
  ScrapeTranslateResponse,
  Settings,
  SettingsUpdate,
} from './types'
import { redirectToLogin } from '../auth'

const BASE = '/api/v1'

/** 将源站图片 URL 转为后端图片代理地址（源站有 Referer 防盗链，浏览器无法直接加载） */
export function imgProxy(url: string): string {
  return `${BASE}/images?url=${encodeURIComponent(url)}`
}

/** 刮削时下载到本地的图片地址（后端静态映射 /local-images/{番号}/文件名） */
export function localImg(number: string, file: string): string {
  return `/local-images/${encodeURIComponent(number)}/${file}`
}

/**
 * 演员头像地址（后端静态映射 /local-actors/{文件名}）。
 *
 * `photoPath` 只存相对路径（`actors/{规范名}.jpg`），而 WebConfig 把头像目录本身映射到
 * `/local-actors/**`，所以只取最后一段文件名，与后端 `ActorPhotoService.resolvePhoto` 一致。
 */
export function actorPhotoUrl(photoPath: string): string {
  const file = photoPath.substring(photoPath.lastIndexOf('/') + 1)
  return `/local-actors/${encodeURIComponent(file)}`
}

/** <img> @error 处理：本地图片缺失时回退到代理地址（仅回退一次，避免循环） */
export function imgFallback(e: Event, fallbackUrl: string): void {
  const el = e.target as HTMLImageElement
  if (fallbackUrl && el.dataset.fbd !== '1') {
    el.dataset.fbd = '1'
    el.src = fallbackUrl
  }
}

/** 视频流地址（HTTP Range 分片传输，<video> 直接可用） */
export function videoUrl(path: string): string {
  return `${BASE}/videos?path=${encodeURIComponent(path)}`
}

/** Emby 条目封面（后端代理转发，不暴露 API Key） */
export function embyPosterUrl(id: string): string {
  return `${BASE}/emby/items/${encodeURIComponent(id)}/images/primary`
}

/**
 * 接口错误（带 HTTP 状态码）。
 *
 * `request()` 原本只抛出 `Error(message)`，调用方无法区分「接口未实现（404）」等特例；
 * 这里扩展一个 `status` 字段，`instanceof Error` 仍成立，旧代码不受影响。
 */
export class ApiError extends Error {
  /** HTTP 状态码（网络不可达时由普通 Error 表示，不会走到这里） */
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const isForm = init?.body instanceof FormData
  let res: Response
  try {
    res = await fetch(BASE + path, {
      ...init,
      // FormData 交给浏览器自动设置 multipart boundary，不能手动指定 Content-Type
      headers: isForm ? undefined : { 'Content-Type': 'application/json', ...init?.headers },
    })
  } catch {
    throw new Error('无法连接后端服务（localhost:8656）')
  }
  // 会话失效统一跳登录页（认证接口自身除外，由登录页自行处理）
  if (res.status === 401 && !path.startsWith('/auth/')) {
    redirectToLogin()
    throw new Error('未登录或会话已过期，请重新登录')
  }
  if (!res.ok) {
    let message = `请求失败（HTTP ${res.status}）`
    try {
      const problem = await res.json()
      if (typeof problem.detail === 'string') message = problem.detail
      else if (typeof problem.title === 'string') message = problem.title
    } catch {
      // 非 JSON 响应，使用默认消息
    }
    throw new ApiError(message, res.status)
  }
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

function qs(params: Record<string, string | number | undefined>): string {
  const parts = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
  return parts.length ? `?${parts.join('&')}` : ''
}

function post<T>(path: string, body: unknown): Promise<T> {
  return request<T>(path, { method: 'POST', body: JSON.stringify(body) })
}

function put<T>(path: string, body: unknown): Promise<T> {
  return request<T>(path, { method: 'PUT', body: JSON.stringify(body) })
}

export interface MovieQuery {
  page?: number
  size?: number
  keyword?: string
  status?: MovieStatus
  sort?: MovieSort
  order?: 'asc' | 'desc'
}

export interface FileRecordQuery {
  page?: number
  size?: number
  number?: string
  recognizeStatus?: RecognizeStatus
  organizeStatus?: OrganizeStatus
}

export interface JobQuery {
  page?: number
  size?: number
  type?: JobType
  status?: JobStatus
}

export interface ActorQuery {
  page?: number
  size?: number
  /** 关键字：匹配 name / alias / 别名表 */
  search?: string
  /** 头像三态过滤：true 有头像、false 无头像、省略不过滤 */
  hasPhoto?: boolean
}

export const api = {
  // 触发任务
  triggerScan(body: ScanRequest) {
    return post<JobAccepted>('/scans', body)
  },
  previewScan(body: ScanPreviewRequest) {
    return post<ScanPreviewResponse>('/scans/preview', body)
  },
  commitScan(items: ScanCommitItem[]) {
    return post<ScanCommitResponse>('/scans/commit', { items })
  },

  // 文件系统浏览
  listFsRoots() {
    return request<FsNode[]>('/fs/roots')
  },
  listFsDirs(path: string) {
    return request<FsDirListing>(`/fs/dirs${qs({ path })}`)
  },

  triggerScrape(body: ScrapeRequest) {
    return post<JobAccepted>('/scrape', body)
  },
  /** 多源对比刮削预览：同步返回各源原始字段值与合并默认来源（不落库） */
  scrapePreview(body: { number: string }) {
    return post<ScrapePreviewResponse>('/scrape/preview', body)
  },
  /** 多源对比刮削落库：按逐字段来源选择写库并下载图片（不自动翻译，保存输入框当前内容） */
  scrapeApply(body: ScrapeApplyRequest) {
    return post<{ number: string; status: string }>('/scrape/apply', body)
  },
  /** 对比界面手动翻译：翻译/繁→简转换标题、简介，逐个翻译类别，返回译文供回填输入框（不落库） */
  scrapeTranslate(body: ScrapeTranslateRequest) {
    return post<ScrapeTranslateResponse>('/scrape/translate', body)
  },
  /** 手动落库：手动录入元数据并上传封面/剧照（multipart，同步落库，不抓取不翻译） */
  scrapeManual(form: FormData) {
    return request<{ number: string; status: string }>('/scrape/manual', { method: 'POST', body: form })
  },
  triggerOrganize(body: OrganizeRequest) {
    return post<JobAccepted>('/organize', body)
  },
  /** 整理预览：同步计算每条记录的目标路径，不执行 */
  previewOrganize(body: OrganizePreviewRequest) {
    return post<OrganizePreviewResponse>('/organize/preview', body)
  },
  /** 整理弹窗文件记录列表（group: pending | organizing | organized | exists） */
  listOrganizeRecords(group: 'pending' | 'organizing' | 'organized' | 'exists', page = 1, size = 100, keyword?: string) {
    return request<Paged<OrganizeRecordItem>>(
      `/organize/records${qs({ group, page, size, keyword })}`,
    )
  },
  triggerPipeline(body: PipelineRequest) {
    return post<JobAccepted>('/pipeline', body)
  },

  // 认证
  authStatus() {
    return request<AuthStatus>('/auth/status')
  },
  authInit(username: string, password: string) {
    return post<LoginResult>('/auth/init', { username, password })
  },
  authLogin(username: string, password: string) {
    return post<LoginResult>('/auth/login', { username, password })
  },
  authLogout() {
    return post<void>('/auth/logout', {})
  },
  authUpdateCredentials(body: CredentialsUpdate) {
    return put<LoginResult>('/auth/credentials', body)
  },

  // 影片
  listMovies(query: MovieQuery = {}) {
    return request<Paged<MovieSummary>>(
      `/movies${qs({
        page: query.page,
        size: query.size,
        keyword: query.keyword,
        status: query.status,
        sort: query.sort,
        order: query.order,
      })}`,
    )
  },
  getMovie(number: string) {
    return request<MovieDetail>(`/movies/${encodeURIComponent(number)}`)
  },
  /** 删除影片记录（含关联文件记录与本地图片缓存，不动视频文件） */
  deleteMovie(number: string) {
    return request<void>(`/movies/${encodeURIComponent(number)}`, { method: 'DELETE' })
  },
  /** 重新下载图片：按持久化封面候选逐个重试（下载失败自动换源），剧照按 preview_urls 重下 */
  retryMovieImages(number: string) {
    return post<{ number: string; coverSource: string | null; previewCount: number }>(
      `/movies/${encodeURIComponent(number)}/images/retry`,
      {},
    )
  },

  // 文件记录
  listFileRecords(query: FileRecordQuery = {}) {
    return request<Paged<FileRecord>>(
      `/file-records${qs({
        page: query.page,
        size: query.size,
        number: query.number,
        recognizeStatus: query.recognizeStatus,
        organizeStatus: query.organizeStatus,
      })}`,
    )
  },

  // 任务
  listJobs(query: JobQuery = {}) {
    return request<Paged<Job>>(
      `/jobs${qs({
        page: query.page,
        size: query.size,
        type: query.type,
        status: query.status,
      })}`,
    )
  },
  getJob(jobId: number) {
    return request<Job>(`/jobs/${jobId}`)
  },
  /** 停止运行中的任务（协作式取消，正在执行的条目会完成） */
  stopJob(jobId: number) {
    return post<Job>(`/jobs/${jobId}/stop`, {})
  },

  // 设置
  getSettings() {
    return request<Settings>('/settings')
  },
  updateSettings(body: SettingsUpdate) {
    return request<Settings>('/settings', { method: 'PUT', body: JSON.stringify(body) })
  },
  /** 手动清理过期图片缓存（按设置的 TTL），返回删除的文件数与字节数 */
  cleanupImageCache() {
    return post<{ files: number; bytes: number }>('/settings/images/cleanup', {})
  },

  // Emby（实时代理，不落库）
  getEmbyStatus() {
    return request<{ serverName: string; version: string }>('/emby/status')
  },
  /** 同步 Emby 媒体库：识别番号写入本地快照（用于「已存在于emby」标记与「已存在」分组） */
  syncEmby() {
    return post<{ scanned: number; matched: number; syncedAt: string }>('/emby/sync', {})
  },
  listEmbyLibraries() {
    return request<EmbyLibrary[]>('/emby/libraries')
  },
  listEmbyItems(query: { libraryId: string; page?: number; size?: number; keyword?: string }) {
    return request<EmbyPage>(
      `/emby/items${qs({ libraryId: query.libraryId, page: query.page, size: query.size, keyword: query.keyword })}`,
    )
  },
  getEmbyItem(id: string) {
    return request<EmbyItemDetail>(`/emby/items/${encodeURIComponent(id)}`)
  },
  updateEmbyMetadata(id: string, body: EmbyMetadataUpdate) {
    return put<EmbyItemDetail>(`/emby/items/${encodeURIComponent(id)}/metadata`, body)
  },
  /** 重新刮削预览：识别番号（自动识别失败时可手动指定 number）→ 并发抓全源 → 返回各源原始值与前后对比 */
  previewEmbyRescrape(id: string, number?: string) {
    return post<EmbyRescrapePreview>(`/emby/items/${encodeURIComponent(id)}/rescrape/preview`, {
      number: number?.trim() || undefined,
    })
  },
  /** 重新刮削应用：按逐字段来源选择组装元数据并回写 Emby（留空字段不回写） */
  applyEmbyRescrape(id: string, body: EmbyRescrapeApplyRequest) {
    return post<EmbyMetadataUpdate>(`/emby/items/${encodeURIComponent(id)}/rescrape/apply`, body)
  },
  /** 推送封面到 Emby（Primary 图）：URL 优先（横版自动裁竖版），回退本地缓存 */
  pushEmbyCover(id: string, body: EmbyImagePushRequest) {
    return post<EmbyImagePushResult>(`/emby/items/${encodeURIComponent(id)}/images/primary`, body)
  },
  /** 推送剧照到 Emby（Backdrop）：清空已有背景图后逐张上传 */
  pushEmbyBackdrops(id: string, body: EmbyImagePushRequest) {
    return post<EmbyImagePushResult>(`/emby/items/${encodeURIComponent(id)}/images/backdrops`, body)
  },

  // 演员（批次 5 后端接口）
  /** 演员分页列表：search 匹配 name/alias/别名表，hasPhoto 为三态过滤 */
  listActors(query: ActorQuery = {}) {
    return request<ActorListResponse>(
      `/actors${qs({
        page: query.page,
        size: query.size,
        search: query.search,
        // 布尔要显式转换：qs 只接受 string/number，false 需要序列化成 'false'
        hasPhoto: query.hasPhoto === undefined ? undefined : String(query.hasPhoto),
      })}`,
    )
  },
  /** 批量补全头像（异步任务）：actorIds 为空/省略 = 全部缺头像的演员，返回 202 + jobId */
  scrapeActorPhotos(actorIds?: number[]) {
    return post<ActorJobAccepted>('/actors/scrape-photos', { actorIds })
  },
  /** 演员详情：演员信息 + 别名列表（不含规范名）+ 所属影片 */
  getActorDetail(id: number) {
    return request<ActorDetail>(`/actors/${id}`)
  },
  /**
   * 编辑别名：`alias` 传空串清空规范名（省略/null 不修改），`aliases` 全量替换。
   * 返回编辑后的详情（含实际落库的别名列表），别名被其他演员占用时后端返回 400。
   */
  updateActorAliases(id: number, body: ActorAliasUpdate) {
    return request<ActorDetail>(`/actors/${id}`, { method: 'PATCH', body: JSON.stringify(body) })
  },
  /**
   * 单个补全头像（同步）：成功/跳过/未命中/下载失败四态。
   * 后端直接返回枚举名（裸 JSON 字符串），这里兼容对象包装形式后统一成枚举值。
   *
   * @param force true = 忽略本地已有文件强制重下（「重新抓取头像」按钮用；规范名改过后重抓才能
   *   让头像文件名跟随新规范名，旧文件由后端清理）
   */
  async scrapeActorPhoto(id: number, force = false): Promise<ActorPhotoScrapeResult> {
    const r = await post<ActorPhotoScrapeResult | { result: ActorPhotoScrapeResult }>(
      `/actors/${id}/scrape-photo${force ? '?force=true' : ''}`,
      {},
    )
    return typeof r === 'string' ? r : r.result
  },
  /**
   * 推送演员到 Emby（头像 + 人名关联）。
   *
   * 注意：后端批次 5 尚未实现该接口（当前返回 404），前端先按占位调用，
   * 调用方需按 `ApiError.status === 404` 给出「功能开发中」提示。
   *
   * @param overwrite 是否覆盖 Emby Person 已有主图（设计文档：请求体 `{overwrite}`）
   */
  pushActorToEmby(id: number, overwrite = false) {
    return post<{ success?: boolean; message?: string | null }>(`/actors/${id}/push-emby`, { overwrite })
  },
  /** 同步 Emby 演员（异步任务）：返回 202 + jobId，进度复用 GET /jobs/{jobId} */
  syncEmbyActors() {
    return post<ActorJobAccepted>('/actors/sync-emby', {})
  },
  /** 更新 Gfriends 头像库索引（同步执行，可能耗时数十秒） */
  syncGfriendsIndex() {
    return post<GfriendsSyncResult>('/gfriends/sync', {})
  },
}
