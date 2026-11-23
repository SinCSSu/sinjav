import type {
  JobStatus,
  JobType,
  LinkMode,
  MovieStatus,
  OrganizeStatus,
  RecognizeStatus,
} from './api/types'

type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

export const movieStatusLabels: Record<MovieStatus, string> = {
  pending: '待刮削',
  scraped: '已刮削',
  failed: '刮削失败',
}

export const movieStatusTagTypes: Record<MovieStatus, TagType> = {
  pending: 'info',
  scraped: 'success',
  failed: 'danger',
}

export const jobTypeLabels: Record<JobType, string> = {
  scan: '扫描',
  scrape: '刮削',
  organize: '整理',
  pipeline: '流水线',
}

export const jobStatusLabels: Record<JobStatus, string> = {
  running: '运行中',
  completed: '已完成',
  failed: '失败',
  cancelled: '已取消',
}

export const jobStatusTagTypes: Record<JobStatus, TagType> = {
  running: 'primary',
  completed: 'success',
  failed: 'danger',
  cancelled: 'info',
}

export const recognizeStatusLabels: Record<RecognizeStatus, string> = {
  recognized: '已识别',
  unrecognized: '未识别',
  filtered: '已过滤',
}

export const recognizeStatusTagTypes: Record<RecognizeStatus, TagType> = {
  recognized: 'success',
  unrecognized: 'info',
  filtered: 'warning',
}

export const organizeStatusLabels: Record<OrganizeStatus, string> = {
  pending: '待整理',
  organizing: '整理中',
  organized: '已整理',
  failed: '整理失败',
  skipped: '已跳过',
}

export const organizeStatusTagTypes: Record<OrganizeStatus, TagType> = {
  pending: 'info',
  organizing: 'primary',
  organized: 'success',
  failed: 'danger',
  skipped: 'warning',
}

export const linkModeLabels: Record<LinkMode, string> = {
  hardlink: '硬链接',
  symlink: '软链接',
  move: '移动',
  copy: '复制',
}

export function formatFileSize(bytes: number): string {
  if (!bytes && bytes !== 0) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`
}

export function formatDateTime(value?: string | null): string {
  if (!value) return '-'
  const d = new Date(value)
  if (isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

export function formatDuration(startedAt: string, finishedAt?: string | null): string {
  const start = new Date(startedAt).getTime()
  const end = finishedAt ? new Date(finishedAt).getTime() : Date.now()
  if (isNaN(start)) return '-'
  const seconds = Math.max(0, Math.floor((end - start) / 1000))
  if (seconds < 60) return `${seconds}s`
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  if (m < 60) return `${m}m ${s}s`
  return `${Math.floor(m / 60)}h ${m % 60}m`
}
