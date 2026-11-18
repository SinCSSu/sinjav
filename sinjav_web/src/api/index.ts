import type {
  FileRecord,
  FsDirListing,
  FsNode,
  Job,
  JobAccepted,
  JobStatus,
  JobType,
  MovieDetail,
  MovieSort,
  MovieStatus,
  MovieSummary,
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
  ScrapeRequest,
  Settings,
  SettingsUpdate,
} from './types'

const BASE = '/api/v1'

/** 将源站图片 URL 转为后端图片代理地址（源站有 Referer 防盗链，浏览器无法直接加载） */
export function imgProxy(url: string): string {
  return `${BASE}/images?url=${encodeURIComponent(url)}`
}

/** 刮削时下载到本地的图片地址（后端静态映射 /local-images/{番号}/文件名） */
export function localImg(number: string, file: string): string {
  return `/local-images/${encodeURIComponent(number)}/${file}`
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

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response
  try {
    res = await fetch(BASE + path, {
      headers: { 'Content-Type': 'application/json' },
      ...init,
    })
  } catch {
    throw new Error('无法连接后端服务（localhost:8656）')
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
    throw new Error(message)
  }
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
  triggerOrganize(body: OrganizeRequest) {
    return post<JobAccepted>('/organize', body)
  },
  triggerPipeline(body: PipelineRequest) {
    return post<JobAccepted>('/pipeline', body)
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

  // 设置
  getSettings() {
    return request<Settings>('/settings')
  },
  updateSettings(body: SettingsUpdate) {
    return request<Settings>('/settings', { method: 'PUT', body: JSON.stringify(body) })
  },
}
