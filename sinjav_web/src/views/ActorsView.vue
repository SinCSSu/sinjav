<template>
  <div>
    <div class="page-header">
      <h2>演员管理</h2>
      <span class="hint">共 {{ total }} 位演员</span>
    </div>

    <!-- 工具栏：搜索 + 头像过滤 + 批量操作 -->
    <div class="toolbar">
      <el-input
        v-model="searchInput"
        class="search-input"
        placeholder="搜索演员名 / 别名"
        clearable
        :prefix-icon="SearchIcon"
      />
      <el-radio-group v-model="photoFilter" @change="applyFilters">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="has">仅有头像</el-radio-button>
        <el-radio-button value="none">仅无头像</el-radio-button>
      </el-radio-group>
      <el-button :icon="RefreshLeftIcon" @click="resetFilters">重置</el-button>

      <div class="toolbar-actions">
        <el-tooltip content="为全部缺头像的演员抓取 Gfriends 头像（异步任务，可查看进度）" placement="top">
          <el-button type="primary" :icon="PictureIcon" :loading="scrapingPhotos" @click="doScrapePhotos">
            补全头像
          </el-button>
        </el-tooltip>
        <el-tooltip content="遍历 Emby 媒体库条目，把演员合并进本地演员库（异步任务）" placement="top">
          <el-button :icon="RefreshIcon" :loading="syncingEmby" @click="doSyncEmbyActors">同步 Emby 演员</el-button>
        </el-tooltip>
        <el-tooltip content="重新下载 Gfriends 头像库索引（同步执行，可能耗时数十秒）" placement="top">
          <el-button :icon="LinkIcon" :loading="syncingGfriends" @click="doSyncGfriends">更新 Gfriends 索引</el-button>
        </el-tooltip>
      </div>
    </div>

    <!-- 头像网格 -->
    <div v-loading="loading" class="list-wrap">
      <el-empty v-if="!loading && items.length === 0" :description="emptyText" />
      <div v-else class="actor-grid">
        <div
          v-for="a in items"
          :key="a.id"
          class="actor-card"
          :class="{ 'is-active': selectedActorId === a.id }"
          role="button"
          tabindex="0"
          :title="a.alias ? `${a.name}（${a.alias}）` : a.name"
          @click="openDetail(a)"
          @keyup.enter="openDetail(a)"
        >
          <div class="avatar">
            <el-image
              v-if="a.photoPath"
              class="avatar-img"
              :src="actorPhotoUrl(a.photoPath)"
              fit="cover"
              lazy
            >
              <template #placeholder>
                <div class="avatar-fallback">加载中…</div>
              </template>
              <template #error>
                <div class="avatar-fallback">暂无头像</div>
              </template>
            </el-image>
            <div v-else class="avatar-fallback">暂无头像</div>
            <span class="count-badge">{{ a.movieCount }} 部</span>
          </div>
          <div class="actor-name">{{ a.name }}</div>
        </div>
      </div>
    </div>

    <!-- 分页 -->
    <div v-if="total > 0" class="pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[20, 40, 60, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @current-change="load"
        @size-change="onSizeChange"
      />
    </div>

    <!-- 异步任务进度弹窗（补全头像 / 同步 Emby 演员共用，进度取自 GET /jobs/{jobId}） -->
    <el-dialog
      v-model="jobVisible"
      :title="jobTitle ? `${jobTitle}·任务进度` : '任务进度'"
      width="min(560px, 94vw)"
      :close-on-click-modal="false"
    >
      <el-skeleton v-if="!job" :rows="3" animated />
      <template v-else>
        <div class="job-head">
          <el-tag size="small" :type="jobStatusTagTypes[job.status]">{{ jobStatusLabels[job.status] }}</el-tag>
          <span class="hint">任务 #{{ job.id }}</span>
          <span class="hint">耗时 {{ formatDuration(job.startedAt, job.finishedAt) }}</span>
        </div>
        <el-progress
          :percentage="jobPercent"
          :status="progressStatus"
          :stroke-width="14"
          striped
          :striped-flow="job.status === 'running'"
        />
        <div class="job-stats">
          <span>已完成 {{ job.doneItems }} / {{ job.totalItems }}</span>
          <span v-if="job.failItems > 0" class="job-fail">失败 {{ job.failItems }}</span>
        </div>
        <div v-if="job.errors.length > 0" class="job-errors">
          <div class="job-errors-title">失败明细（{{ job.errors.length }}）</div>
          <el-scrollbar max-height="160px">
            <div v-for="(err, i) in job.errors" :key="i" class="job-error-row">
              <span class="job-error-item">{{ err.item }}</span>
              <span class="job-error-reason">{{ err.reason }}</span>
            </div>
          </el-scrollbar>
        </div>
      </template>
      <template #footer>
        <div class="dialog-footer">
          <el-button
            v-if="job?.status === 'running'"
            type="danger"
            plain
            :loading="stoppingJob"
            @click="stopJob"
          >
            停止任务
          </el-button>
          <el-button type="primary" @click="jobVisible = false">
            {{ job?.status === 'running' ? '后台继续' : '关闭' }}
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 演员详情弹窗：别名编辑 / 重抓头像 / 推送 Emby / 所属影片 -->
    <ActorDetailDialog
      :actor-id="selectedActorId"
      :visible="detailDialogVisible"
      @close="detailDialogVisible = false"
      @updated="refreshList"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  Link as LinkIcon,
  Picture as PictureIcon,
  Refresh as RefreshIcon,
  RefreshLeft as RefreshLeftIcon,
  Search as SearchIcon,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { actorPhotoUrl, api } from '../api'
import type { Actor, Job } from '../api/types'
import { formatDuration, jobStatusLabels, jobStatusTagTypes } from '../utils'
import ActorDetailDialog from '../components/ActorDetailDialog.vue'

/** 搜索防抖：输入停止 500ms 后自动查询 */
const SEARCH_DEBOUNCE_MS = 500
/** 任务进度轮询间隔 */
const JOB_POLL_INTERVAL_MS = 1500

const searchInput = ref('')
/** 头像三态过滤：'' 全部 / 'has' 仅有头像 / 'none' 仅无头像 */
const photoFilter = ref<'' | 'has' | 'none'>('')
const page = ref(1)
const size = ref(20)
const total = ref(0)
const items = ref<Actor[]>([])
const loading = ref(false)

const scrapingPhotos = ref(false)
const syncingEmby = ref(false)
const syncingGfriends = ref(false)

/** 选中的演员（详情弹窗高亮 + 数据源） */
const selectedActorId = ref<number | null>(null)
const detailDialogVisible = ref(false)

// 任务进度弹窗状态
const jobVisible = ref(false)
const jobTitle = ref('')
const jobId = ref<number | null>(null)
const job = ref<Job | null>(null)
const stoppingJob = ref(false)
let jobTimer: number | undefined

// 搜索防抖定时器
let searchTimer: number | undefined
/** 程序化清空搜索框时跳过防抖触发，避免重置后重复查询 */
let skipSearchOnce = false
/** 请求序号：丢弃过期响应，避免快速输入/切页时旧结果覆盖新结果 */
let reqSeq = 0

const emptyText = computed(() => {
  if (searchInput.value.trim() || photoFilter.value) return '没有匹配的演员，试试调整搜索关键字或头像过滤条件'
  return '演员库为空：可先「同步 Emby 演员」，或刮削影片后自动入库'
})

const jobPercent = computed(() => {
  const j = job.value
  if (!j) return 0
  if (j.status === 'completed') return 100
  if (j.totalItems <= 0) return 0
  return Math.min(100, Math.floor((j.doneItems / j.totalItems) * 100))
})

const progressStatus = computed<'success' | 'exception' | undefined>(() => {
  const s = job.value?.status
  if (s === 'completed') return 'success'
  if (s === 'failed') return 'exception'
  return undefined
})

function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/** 当前过滤条件 → hasPhoto 参数（undefined 表示不过滤） */
function hasPhotoParam(): boolean | undefined {
  if (photoFilter.value === 'has') return true
  if (photoFilter.value === 'none') return false
  return undefined
}

async function load() {
  const seq = ++reqSeq
  loading.value = true
  try {
    const res = await api.listActors({
      page: page.value,
      size: size.value,
      search: searchInput.value.trim() || undefined,
      hasPhoto: hasPhotoParam(),
    })
    if (seq !== reqSeq) return
    items.value = res.items
    total.value = res.total
  } catch (e) {
    if (seq !== reqSeq) return
    items.value = []
    total.value = 0
    ElMessage.error(errMsg(e))
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

/** 搜索/过滤条件变化：回到第一页重新查询 */
function applyFilters() {
  clearSearchTimer()
  page.value = 1
  load()
}

/** 重置：清空搜索与头像过滤，恢复默认列表 */
function resetFilters() {
  clearSearchTimer()
  // 搜索框有内容时才需要跳过 watcher 的防抖（空值不会触发 watch）
  if (searchInput.value) {
    skipSearchOnce = true
    searchInput.value = ''
  }
  photoFilter.value = ''
  page.value = 1
  load()
}

function onSizeChange() {
  page.value = 1
  load()
}

/** 搜索框输入防抖：停止输入 500ms 后自动查询 */
watch(searchInput, () => {
  if (skipSearchOnce) {
    skipSearchOnce = false
    return
  }
  clearSearchTimer()
  searchTimer = window.setTimeout(() => {
    searchTimer = undefined
    applyFilters()
  }, SEARCH_DEBOUNCE_MS)
})

function clearSearchTimer() {
  if (searchTimer !== undefined) {
    window.clearTimeout(searchTimer)
    searchTimer = undefined
  }
}

/** 点击卡片：打开演员详情弹窗 */
function openDetail(actor: Actor) {
  selectedActorId.value = actor.id
  detailDialogVisible.value = true
}

/** 详情弹窗内改动（别名保存 / 头像重抓）后刷新当前页列表 */
function refreshList() {
  load()
}

/* ---------------------------------------------------------------- 批量操作 */

/** 补全头像：body 为空 = 全部缺头像的演员，返回 jobId 后打开进度弹窗 */
async function doScrapePhotos() {
  scrapingPhotos.value = true
  try {
    const r = await api.scrapeActorPhotos()
    openJobDialog('补全头像', r.jobId)
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    scrapingPhotos.value = false
  }
}

/** 同步 Emby 演员：异步任务，返回 jobId 后打开进度弹窗 */
async function doSyncEmbyActors() {
  syncingEmby.value = true
  try {
    const r = await api.syncEmbyActors()
    openJobDialog('同步 Emby 演员', r.jobId)
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    syncingEmby.value = false
  }
}

/** 更新 Gfriends 索引：同步接口（可能耗时数十秒），成功只提示条目数 */
async function doSyncGfriends() {
  syncingGfriends.value = true
  try {
    const r = await api.syncGfriendsIndex()
    if (r.success) {
      ElMessage.success(`Gfriends 索引已更新，共 ${r.indexCount} 条`)
    } else {
      ElMessage.warning(r.message || 'Gfriends 索引同步失败，已保留旧索引')
    }
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    syncingGfriends.value = false
  }
}

/* ---------------------------------------------------------------- 任务进度 */

function openJobDialog(title: string, id: number) {
  jobTitle.value = title
  jobId.value = id
  job.value = null
  jobVisible.value = true
  startJobPoll()
}

function startJobPoll() {
  stopJobPoll()
  pollJob()
  jobTimer = window.setInterval(pollJob, JOB_POLL_INTERVAL_MS)
}

/** 轮询任务状态：结束后刷新列表并提示结果（弹窗关闭也继续，直到任务结束） */
async function pollJob() {
  const id = jobId.value
  if (id === null) return
  try {
    const j = await api.getJob(id)
    // 期间又开了别的任务：丢弃这次旧结果
    if (jobId.value !== id) return
    job.value = j
    if (j.status === 'running') return
    stopJobPoll()
    load()
    const summary = `完成 ${j.doneItems}/${j.totalItems}${j.failItems > 0 ? `，失败 ${j.failItems}` : ''}`
    ElMessage({
      type: j.status === 'completed' ? 'success' : 'warning',
      message: `${jobTitle.value}${j.status === 'completed' ? '已完成' : '已结束'}：${summary}`,
    })
  } catch (e) {
    stopJobPoll()
    ElMessage.error(errMsg(e))
  }
}

function stopJobPoll() {
  if (jobTimer !== undefined) {
    window.clearInterval(jobTimer)
    jobTimer = undefined
  }
}

/** 停止运行中的任务（协作式取消：当前条目执行完后终止） */
async function stopJob() {
  const id = jobId.value
  if (id === null) return
  stoppingJob.value = true
  try {
    await api.stopJob(id)
    ElMessage.success('已发送停止请求，当前条目完成后终止')
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    stoppingJob.value = false
  }
}

onMounted(load)

onUnmounted(() => {
  clearSearchTimer()
  stopJobPoll()
})
</script>

<style scoped>
.search-input {
  width: 260px;
}
/* 操作按钮组：整体右对齐，窄屏换行时保持成组（不会被拆散到两行两侧） */
.toolbar-actions {
  margin-left: auto;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.list-wrap {
  min-height: 240px;
}

/* ---------- 头像网格 ---------- */
.actor-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 16px;
}
.actor-card {
  cursor: pointer;
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px 8px 10px;
  border: 1px solid var(--glass-border);
  border-radius: var(--glass-radius);
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  box-shadow: var(--glass-shadow);
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}
/* hover：阴影加深 + 轻微放大；放大后需盖住相邻卡片，故提升层级 */
.actor-card:hover,
.actor-card:focus-visible {
  transform: scale(1.05);
  box-shadow: var(--glass-shadow-hover);
  z-index: 2;
}
.actor-card.is-active {
  border-color: var(--glass-accent-border);
  box-shadow: 0 0 0 2px var(--glass-accent) inset, var(--glass-shadow);
}

.avatar {
  position: relative;
  /* 150 x 200 的 3:4 头像；宽度随网格列宽自适应 */
  aspect-ratio: 3 / 4;
  border-radius: var(--glass-radius-sm);
  overflow: hidden;
  background: var(--cover-placeholder-bg);
}
.avatar-img {
  width: 100%;
  height: 100%;
  display: block;
}
.avatar-img :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
/* 无头像占位：灰色底 + 居中提示文字 */
.avatar-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: linear-gradient(160deg, rgba(158, 168, 192, 0.22), rgba(140, 152, 182, 0.14));
}
/* 影片数角标：压在头像右下角 */
.count-badge {
  position: absolute;
  right: 6px;
  bottom: 6px;
  padding: 1px 7px;
  font-size: 11px;
  line-height: 1.6;
  color: #fff;
  background: rgba(38, 44, 64, 0.62);
  backdrop-filter: var(--glass-blur-light);
  -webkit-backdrop-filter: var(--glass-blur-light);
  border-radius: var(--el-border-radius-round);
  pointer-events: none;
}
.actor-name {
  text-align: center;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---------- 任务进度弹窗 ---------- */
.job-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.job-stats {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.job-fail {
  color: var(--el-color-danger);
}
.job-errors {
  margin-top: 12px;
  border-top: 1px solid var(--glass-border-soft);
  padding-top: 10px;
}
.job-errors-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.job-error-row {
  display: flex;
  gap: 10px;
  padding: 4px 6px;
  font-size: 12px;
  border-radius: var(--el-border-radius-small);
}
.job-error-row:nth-child(odd) {
  background: var(--row-hover-bg);
}
.job-error-item {
  flex: 0 0 120px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.job-error-reason {
  flex: 1;
  min-width: 0;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}
.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* ---------- 响应式 ---------- */
@media (max-width: 900px) {
  .search-input {
    width: 100%;
  }
  .toolbar-actions {
    margin-left: 0;
  }
  .actor-grid {
    grid-template-columns: repeat(auto-fill, minmax(110px, 1fr));
    gap: 12px;
  }
  .pager {
    justify-content: center;
  }
}
/* 触屏无 hover：不做放大，避免误触后卡片保持放大态 */
@media (hover: none) {
  .actor-card:hover {
    transform: none;
  }
}
</style>

