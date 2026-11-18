// 与 docs/openapi.yaml 对齐的类型定义

export type JobType = 'scan' | 'scrape' | 'organize' | 'pipeline'
export type JobStatus = 'running' | 'completed' | 'failed' | 'cancelled'
export type MovieStatus = 'pending' | 'scraped' | 'failed'
export type RecognizeStatus = 'recognized' | 'unrecognized' | 'filtered'
export type OrganizeStatus = 'pending' | 'organized' | 'failed' | 'skipped'
export type LinkMode = 'hardlink' | 'symlink' | 'move' | 'copy'
export type ConflictPolicy = 'skip' | 'overwrite' | 'rename'
export type ScraperStrategy = 'fallback' | 'merge'
export type ScraperSource = 'javbus' | 'javdb'
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
  releaseDate?: string | null
  maker?: string | null
  actresses: string[]
  coverUrl?: string | null
  status: MovieStatus
}

export interface Movie extends MovieSummary {
  rawNumber?: string | null
  titleZh?: string | null
  director?: string | null
  label?: string | null
  series?: string | null
  durationMin?: number | null
  previewUrls: string[]
  plot?: string | null
  source?: ScraperSource | 'merged' | null
  failReason?: string | null
  scrapedAt?: string | null
  createdAt: string
  updatedAt: string
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
}

export interface ScrapeRequest {
  numbers?: string[]
  sources?: ScraperSource[]
  strategy?: ScraperStrategy
}

export interface OrganizeRequest {
  targetDir?: string
  mode?: LinkMode
  namingTemplate?: string
  conflictPolicy?: ConflictPolicy
  generateNfo?: boolean
  downloadImages?: boolean
  numbers?: string[]
}

export interface PipelineRequest {
  scan?: ScanRequest
  scrape?: ScrapeRequest
  organize?: OrganizeRequest
}

export type SettingsUpdate = {
  sourceOrder?: string
  strategy?: ScraperStrategy
  userAgent?: string
  javdbCookie?: string
  proxyHost?: string
  proxyPort?: number
  requestIntervalMs?: number
  concurrency?: number
  targetDir?: string
  mode?: LinkMode
  namingTemplate?: string
  conflictPolicy?: ConflictPolicy
  generateNfo?: boolean
  downloadImages?: boolean
}

export type Settings = SettingsUpdate
