<template>
  <el-dialog
    v-model="visible"
    title="触发刮削"
    width="min(960px, 96vw)"
    top="6vh"
    :close-on-click-modal="false"
  >
    <div class="scrape-layout">
      <!-- 左栏：媒体列表 -->
      <div class="col col-left">
        <div class="list-title">
          <span>未刮削</span>
          <el-tag size="small" type="warning" v-if="unscraped.length">{{ unscraped.length }}</el-tag>
        </div>
        <el-scrollbar class="list-scroll">
          <div
            v-for="m in unscraped"
            :key="m.number"
            class="movie-row"
            :class="{ active: selected?.number === m.number }"
            @click="selected = m"
          >
            <span class="num mono">{{ m.number }}</span>
            <el-tag size="small" :type="m.status === 'failed' ? 'danger' : 'warning'">
              {{ m.status === 'failed' ? '失败' : '待刮削' }}
            </el-tag>
          </div>
          <el-empty
            v-if="!loading && unscraped.length === 0"
            description="没有待刮削的媒体"
            :image-size="60"
          />
          <div v-if="loading" class="loading-hint">加载中…</div>
        </el-scrollbar>
        <el-collapse class="scraped-collapse">
          <el-collapse-item :title="`已刮削（${scraped.length}）`">
            <div
              v-for="m in scraped"
              :key="m.number"
              class="movie-row"
              :class="{ active: selected?.number === m.number }"
              @click="selected = m"
            >
              <span class="num mono">{{ m.number }}</span>
              <span class="title-hint">{{ m.title || '' }}</span>
            </div>
            <div v-if="!loading && scraped.length === 0" class="loading-hint">无已刮削媒体</div>
          </el-collapse-item>
        </el-collapse>
      </div>

      <!-- 右栏：刮削信息 -->
      <div class="col col-right">
        <h4 class="list-title">刮削信息</h4>
        <el-empty v-if="!selected" description="点击左侧媒体查看详情" :image-size="70" />
        <template v-else>
          <div class="detail">
            <img
              v-if="selected.coverUrl"
              class="cover"
              :src="localImg(selected.number, 'poster.jpg')"
              alt=""
              @error="imgFallback($event, imgProxy(selected.coverUrl))"
            />
            <div class="detail-rows">
              <div class="detail-row"><span class="k">番号</span><span class="mono">{{ selected.number }}</span></div>
              <div class="detail-row"><span class="k">标题</span><span>{{ selected.title || '—' }}</span></div>
              <div class="detail-row"><span class="k">发行日期</span><span>{{ selected.releaseDate || '—' }}</span></div>
              <div class="detail-row"><span class="k">厂商</span><span>{{ selected.maker || '—' }}</span></div>
              <div class="detail-row"><span class="k">女优</span><span>{{ selected.actresses.join(' / ') || '—' }}</span></div>
              <div class="detail-row">
                <span class="k">状态</span>
                <el-tag size="small" :type="statusTagType(selected.status)">{{ statusLabel(selected.status) }}</el-tag>
              </div>
            </div>
          </div>
          <div v-if="selected.plot" class="plot">
            <span class="k">简介</span>
            <p class="plot-text">{{ selected.plot }}</p>
          </div>
        </template>
      </div>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button
          type="primary"
          :loading="scraping"
          :disabled="!selected"
          @click="scrapeSelected"
        >
          刮削
        </el-button>
        <el-button
          type="success"
          :loading="scrapingAll"
          :disabled="unscraped.length === 0"
          @click="scrapeAll"
        >
          全部刮削
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, imgFallback, imgProxy, localImg } from '../api'
import type { MovieStatus, MovieSummary } from '../api/types'

const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{ created: [] }>()

const loading = ref(false)
const unscraped = ref<MovieSummary[]>([])
const scraped = ref<MovieSummary[]>([])
const selected = ref<MovieSummary | null>(null)
const scraping = ref(false)
const scrapingAll = ref(false)
let pollTimer: number | undefined

watch(visible, (open) => {
  if (open) {
    selected.value = null
    loadLists()
  } else {
    stopPoll()
  }
})

async function loadLists() {
  loading.value = true
  try {
    const [pending, failed, done] = await Promise.all([
      api.listMovies({ status: 'pending', size: 200 }),
      api.listMovies({ status: 'failed', size: 200 }),
      api.listMovies({ status: 'scraped', size: 100 }),
    ])
    unscraped.value = [...pending.items, ...failed.items]
    scraped.value = done.items
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loading.value = false
  }
}

async function scrapeSelected() {
  if (!selected.value) return
  scraping.value = true
  try {
    const job = await api.triggerScrape({ numbers: [selected.value.number] })
    ElMessage.success(`刮削任务 #${job.jobId} 已创建`)
    emit('created')
    pollUntilDone(job.jobId)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    scraping.value = false
  }
}

async function scrapeAll() {
  scrapingAll.value = true
  try {
    const job = await api.triggerScrape({})
    ElMessage.success(`刮削任务 #${job.jobId} 已创建（共 ${unscraped.value.length} 个待刮削）`)
    emit('created')
    pollUntilDone(job.jobId)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    scrapingAll.value = false
  }
}

/** 轮询任务状态，结束后刷新列表 */
function pollUntilDone(jobId: number) {
  stopPoll()
  pollTimer = window.setInterval(async () => {
    try {
      const job = await api.getJob(jobId)
      if (job.status === 'completed' || job.status === 'failed') {
        stopPoll()
        await loadLists()
        ElMessage({
          type: job.status === 'completed' ? 'success' : 'warning',
          message: job.status === 'completed'
            ? `刮削任务 #${jobId} 完成`
            : `刮削任务 #${jobId} 结束（存在失败项，可在任务页查看原因）`,
        })
      }
    } catch {
      stopPoll()
    }
  }, 2000)
}

function stopPoll() {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

function statusLabel(s: MovieStatus): string {
  return s === 'pending' ? '待刮削' : s === 'failed' ? '失败' : '已刮削'
}

function statusTagType(s: MovieStatus): 'warning' | 'danger' | 'success' {
  return s === 'pending' ? 'warning' : s === 'failed' ? 'danger' : 'success'
}
</script>

<style scoped>
.scrape-layout {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 12px;
  height: 58vh;
  min-height: 380px;
}
.col {
  display: flex;
  flex-direction: column;
  gap: 8px;
  border: 1px solid var(--glass-border-soft);
  border-radius: var(--glass-radius);
  padding: 14px;
  overflow: hidden;
  background: var(--glass-bg-soft);
  backdrop-filter: var(--glass-blur-light);
  -webkit-backdrop-filter: var(--glass-blur-light);
}
.col-left {
  width: 320px;
}
.list-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}
.list-scroll {
  flex: 1;
}
.movie-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}
.movie-row:hover {
  background: rgba(255, 255, 255, 0.55);
}
.movie-row.active {
  background: var(--glass-accent);
  box-shadow: 0 0 0 1px rgba(91, 110, 225, 0.2) inset;
}
.num {
  font-size: 13px;
  font-weight: 600;
}
.title-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
}
.loading-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-align: center;
  padding: 12px 0;
}
.scraped-collapse :deep(.el-collapse-item__header) {
  font-size: 13px;
}
.scraped-collapse :deep(.el-collapse-item__content) {
  padding-bottom: 0;
}
.scraped-collapse :deep(.el-collapse-item__wrap) {
  max-height: 220px;
  overflow: auto;
}
.detail {
  display: flex;
  gap: 16px;
  overflow: auto;
}
.cover {
  width: 180px;
  object-fit: cover;
  border-radius: var(--glass-radius-sm);
  align-self: flex-start;
  flex-shrink: 0;
  box-shadow: var(--glass-shadow);
}
.detail-rows {
  display: flex;
  flex-direction: column;
  gap: 10px;
  font-size: 13px;
  min-width: 0;
}
.detail-row {
  display: flex;
  flex-direction: column;
  gap: 2px;
  word-break: break-all;
}
.detail-row .k {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.plot {
  margin-top: 4px;
  border-top: 1px solid var(--glass-border-soft);
  padding-top: 10px;
  overflow: auto;
  flex: 1;
  min-height: 0;
}
.plot .k {
  display: block;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.plot-text {
  margin: 6px 0 0;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-line;
  word-break: break-all;
}
.dialog-footer {
  display: flex;
  justify-content: flex-end;
}
</style>
