<template>
  <el-dialog
    v-model="visible"
    title="刮削"
    width="min(1100px, 96vw)"
    top="5vh"
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

      <!-- 右栏：详情 / 多源对比 -->
      <div class="col col-right">
        <!-- 详情模式 -->
        <template v-if="!compareMode">
          <h4 class="list-title">刮削信息</h4>
          <el-empty v-if="!selected" description="暂无详情" :image-size="70" />
          <el-empty v-else-if="compareLoading" description="正在并发抓取各数据源…" :image-size="70" />
          <template v-else>
            <div class="detail">
              <img
                v-if="selected.coverUrl"
                class="cover zoomable"
                :src="localImg(selected.number, 'poster.jpg')"
                alt=""
                @error="imgFallback($event, imgProxy(selected.coverUrl))"
                @click="openViewer([imgProxy(selected.coverUrl)], 0)"
              />
              <div class="detail-rows">
                <div class="detail-row"><span class="k">番号</span><span class="mono">{{ selected.number }}</span></div>
                <div class="detail-row"><span class="k">标题</span><span>{{ selected.title || '—' }}</span></div>
                <div class="detail-row"><span class="k">发行日期</span><span>{{ selected.releaseDate || '—' }}</span></div>
                <div class="detail-row"><span class="k">厂商</span><span>{{ selected.maker || '—' }}</span></div>
                <div class="detail-row"><span class="k">女优</span><span>{{ selected.actresses.join(' / ') || '—' }}</span></div>
                <div class="detail-row">
                  <span class="k">状态</span>
                  <el-tag v-if="scrapeBusy && selected.status !== 'scraped'" size="small" type="primary">刮削中…</el-tag>
                  <el-tag v-else size="small" :type="statusTagType(selected.status)">{{ statusLabel(selected.status) }}</el-tag>
                </div>
              </div>
            </div>
            <div v-if="selected.plot" class="plot">
              <span class="k">简介</span>
              <p class="plot-text">{{ selected.plot }}</p>
            </div>
          </template>
        </template>

        <!-- 多源对比模式 -->
        <template v-else>
          <div class="cmp-toolbar">
            <el-input
              v-model="numberInput"
              size="small"
              class="cmp-number-input mono"
              placeholder="番号"
              @keyup.enter="rescrapeByNumber"
            />
            <el-button
              size="small"
              :loading="compareLoading"
              :disabled="!numberInput.trim()"
              @click="rescrapeByNumber"
            >
              重新刮削
            </el-button>
          </div>
          <el-scrollbar v-if="preview" class="cmp-scroll">
            <div class="cmp-body">
              <div class="cmp-main">
                <img
                  v-if="currentCoverUrl"
                  class="cover zoomable"
                  :src="imgProxy(currentCoverUrl)"
                  alt=""
                  @error="imgFallback($event, imgProxy(currentCoverUrl))"
                  @click="openViewer([imgProxy(currentCoverUrl)], 0)"
                />
                <div class="cmp-fields">
                  <div v-for="f in FIELDS" :key="f.key" class="cmp-row">
                    <span class="k">{{ f.label }}</span>
                    <el-select v-model="sel[f.key]" size="small" class="cmp-select">
                      <el-option v-for="s in fieldSources(f.key)" :key="s" :label="s" :value="s" />
                      <el-option label="留空" value="" />
                      <el-option label="手动输入" :value="MANUAL" />
                    </el-select>
                    <el-input
                      v-model="vals[f.key]"
                      size="small"
                      :type="f.multiline ? 'textarea' : 'text'"
                      :rows="f.key === 'plot' ? 4 : 2"
                      :disabled="!sel[f.key]"
                      :placeholder="f.key === 'previewUrls' ? '多个 URL 用逗号或换行分隔' : sel[f.key] === MANUAL ? '输入自定义值' : '留空不落库'"
                      class="cmp-value cmp-manual"
                    />
                  </div>
                </div>
              </div>
              <div v-if="currentPreviews.length" class="cmp-thumbs">
                <span class="k">剧照（{{ currentPreviews.length }} 张）</span>
                <div class="thumbs-row">
                  <img
                    v-for="(u, i) in currentPreviews.slice(0, 4)"
                    :key="i"
                    class="thumb zoomable"
                    :src="imgProxy(u)"
                    alt=""
                    @error="imgFallback($event, imgProxy(u))"
                    @click="openViewer(currentPreviews.map(p => imgProxy(p)), i)"
                  />
                  <span v-if="currentPreviews.length > 4" class="more">…</span>
                </div>
              </div>
            </div>
          </el-scrollbar>
        </template>
      </div>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <template v-if="compareMode">
          <el-button :disabled="applying" @click="compareMode = false">返回详情</el-button>
          <el-button :loading="translating" :disabled="!preview" @click="translateFields">翻译</el-button>
          <el-button type="primary" :loading="applying" :disabled="!preview" @click="applyCompare">
            保存
          </el-button>
        </template>
        <template v-else>
          <el-button
            type="primary"
            :loading="compareLoading"
            :disabled="!selected"
            @click="openCompare"
          >
            开始刮削
          </el-button>
          <el-button
            type="success"
            :loading="scrapingAll"
            :disabled="unscraped.length === 0"
            @click="scrapeAll"
          >
            全部刮削
          </el-button>
        </template>
      </div>
    </template>
  </el-dialog>
  <ImagePreviewViewer
    :visible="viewerVisible"
    :urls="viewerUrls"
    :initial-index="viewerIndex"
    @close="viewerVisible = false"
  />
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, imgFallback, imgProxy, localImg } from '../api'
import type { MovieStatus, MovieSummary, ScrapeFieldChoice, ScrapePreviewResponse } from '../api/types'
import ImagePreviewViewer from './ImagePreviewViewer.vue'

const visible = defineModel<boolean>({ default: false })
/** 卡片快捷刮削入口：打开弹窗时自动定位该番号并进入多源对比界面 */
const props = defineProps<{ presetNumber?: string }>()
const emit = defineEmits<{ created: [] }>()

const MANUAL = '__manual__'
/** 对比字段配置（与后端 ScrapeCompareService.fieldKeys 一致） */
const FIELDS = [
  { key: 'title', label: '标题', multiline: false },
  { key: 'plot', label: '简介', multiline: true },
  { key: 'releaseDate', label: '发行日期', multiline: false },
  { key: 'durationMin', label: '时长(分钟)', multiline: false },
  { key: 'director', label: '导演', multiline: false },
  { key: 'maker', label: '厂商', multiline: false },
  { key: 'label', label: '发行商', multiline: false },
  { key: 'series', label: '系列', multiline: false },
  { key: 'actresses', label: '女优', multiline: false },
  { key: 'genres', label: '类别', multiline: false },
  { key: 'coverUrl', label: '封面', multiline: false },
  { key: 'previewUrls', label: '剧照', multiline: true },
] as const

const loading = ref(false)
const unscraped = ref<MovieSummary[]>([])
const scraped = ref<MovieSummary[]>([])
const selected = ref<MovieSummary | null>(null)
const scrapingAll = ref(false)
const scrapeBusy = ref(false)
let pollTimer: number | undefined

// 多源对比状态
const compareMode = ref(false)
const compareLoading = ref(false)
const applying = ref(false)
const translating = ref(false)
const preview = ref<ScrapePreviewResponse | null>(null)
/** 对比界面可编辑的番号（识别错了可改后重新刮削） */
const numberInput = ref('')
/** key → 来源 id / ''(留空) / MANUAL(手动输入) */
const sel = ref<Record<string, string>>({})
/** key → 可编辑的最终值：切换来源时自动填入该来源原文，可直接编辑或点「翻译」回填译文后再保存 */
const vals = ref<Record<string, string>>({})

// 大图预览
const viewerVisible = ref(false)
const viewerUrls = ref<string[]>([])
const viewerIndex = ref(0)

function openViewer(urls: string[], index: number) {
  viewerUrls.value = urls
  viewerIndex.value = index
  viewerVisible.value = true
}

watch(visible, async (open) => {
  if (open) {
    selected.value = null
    resetCompare()
    if (props.presetNumber) {
      await loadLists()
      const m = unscraped.value.find(x => x.number === props.presetNumber)
        ?? scraped.value.find(x => x.number === props.presetNumber)
      if (m) {
        selected.value = m
        await nextTick() // 让 watch(selected) 的 resetCompare 先执行，再进对比模式
        openCompare()
      }
    } else {
      loadLists()
    }
  } else {
    stopPoll()
  }
})

// 切换影片时退出对比模式
watch(selected, resetCompare)

function resetCompare() {
  compareMode.value = false
  compareLoading.value = false
  preview.value = null
  numberInput.value = ''
  sel.value = {}
  vals.value = {}
}

// 切换字段来源时重填该字段的值（选中来源原文 / 留空清空）
FIELDS.forEach((f) => {
  watch(
    () => sel.value[f.key],
    (src) => {
      vals.value[f.key] = src && src !== MANUAL ? (fieldValue(f.key, src) ?? '') : ''
    },
  )
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

/** 开始刮削：并发抓取全部数据源，进入多源对比界面（不落库） */
function openCompare() {
  if (!selected.value) return
  resetCompare()
  numberInput.value = selected.value.number
  compareMode.value = true
  doScrapePreview(numberInput.value)
}

/** 编辑番号后重新刮削：按新番号并发抓全源，刷新对比面板（不落库） */
function rescrapeByNumber() {
  const n = numberInput.value.trim()
  if (!n) return
  doScrapePreview(n)
}

function doScrapePreview(number: string) {
  compareLoading.value = true
  api.scrapePreview({ number })
    .then((res) => {
      preview.value = res
      numberInput.value = res.number
      // 默认按合并规则选来源（source-order 靠前的源优先）；无值字段留空
      sel.value = Object.fromEntries(FIELDS.map(f => [f.key, res.defaults[f.key] ?? '']))
      // 各字段值按默认来源原文预填（可在输入框直接编辑后再落库）
      vals.value = Object.fromEntries(FIELDS.map(f => [f.key, res.fields[f.key]?.[res.defaults[f.key] ?? ''] ?? '']))
    })
    .catch((e) => {
      ElMessage.error(e instanceof Error ? e.message : String(e))
    })
    .finally(() => {
      compareLoading.value = false
    })
}

/** 某字段有值的来源列表（下拉选项） */
function fieldSources(key: string): string[] {
  const values = preview.value?.fields[key]
  if (!preview.value || !values) return []
  return preview.value.sources.filter(s => values[s] != null)
}

function fieldValue(key: string, source: string | undefined): string | null {
  if (!preview.value || !source || source === MANUAL) return null
  return preview.value.fields[key]?.[source] ?? null
}

/** 当前生效的字段值（含用户在输入框里的修改），留空来源返回 '' */
function liveValue(key: string): string {
  return sel.value[key] ? (vals.value[key] ?? '') : ''
}

const currentCoverUrl = computed(() => liveValue('coverUrl').trim() || null)

const currentPreviews = computed(() =>
  liveValue('previewUrls')
    .split('\n')
    .map(s => s.trim())
    .filter(Boolean),
)

/** 翻译：对输入框中的标题/简介/类别做翻译或繁→简转换，译文回填输入框（不落库） */
async function translateFields() {
  if (!preview.value) return
  translating.value = true
  try {
    const res = await api.scrapeTranslate({
      title: liveValue('title').trim() || undefined,
      plot: liveValue('plot').trim() || undefined,
      genres: liveValue('genres').trim() || undefined,
    })
    for (const k of ['title', 'plot', 'genres'] as const) {
      if (res[k]) vals.value[k] = res[k]!
    }
    ElMessage.success('翻译完成')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    translating.value = false
  }
}

/** 保存：按逐字段选择与输入框当前内容落库；番号被编辑过时原记录改用新番号落库 */
async function applyCompare() {
  if (!selected.value || !preview.value) return
  const fields: Record<string, ScrapeFieldChoice> = {}
  for (const f of FIELDS) {
    const s = sel.value[f.key] ?? ''
    if (s === '') {
      fields[f.key] = { source: null }
    } else if (s === MANUAL) {
      fields[f.key] = { source: 'manual', value: vals.value[f.key] }
    } else {
      // 值取输入框当前内容（原文或翻译回填的译文，保存即所见）
      fields[f.key] = { source: s, value: vals.value[f.key] }
    }
  }
  const originalNumber = selected.value.number
  const renamed = preview.value.number !== originalNumber
  applying.value = true
  try {
    await api.scrapeApply({
      number: preview.value.number,
      recordNumber: originalNumber,
      fields,
    })
    ElMessage.success(renamed ? `${originalNumber} → ${preview.value.number} 已落库` : `${preview.value.number} 已落库`)
    emit('created')
    compareMode.value = false
    preview.value = null
    await loadLists()
    // 改番号后按新番号找回记录并选中展示
    selected.value = unscraped.value.find(m => m.number === preview.value?.number)
      ?? scraped.value.find(m => m.number === preview.value?.number)
      ?? null
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    applying.value = false
  }
}

async function scrapeAll() {
  scrapingAll.value = true
  scrapeBusy.value = true
  try {
    const job = await api.triggerScrape({})
    ElMessage.success(`刮削任务 #${job.jobId} 已创建`)
    emit('created')
    pollUntilDone(job.jobId)
  } catch (e) {
    scrapeBusy.value = false
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    scrapingAll.value = false
  }
}

/** 刮削完成后从最新列表按番号找回影片，保持右侧选中项并展示刮削内容 */
function refreshSelected() {
  const n = selected.value?.number
  if (!n) return
  const found = unscraped.value.find(m => m.number === n)
    ?? scraped.value.find(m => m.number === n)
  selected.value = found ?? null
}

/** 轮询任务状态，结束后刷新列表并同步右侧详情 */
function pollUntilDone(jobId: number) {
  stopPoll()
  pollTimer = window.setInterval(async () => {
    try {
      const job = await api.getJob(jobId)
      if (job.status === 'completed' || job.status === 'failed') {
        stopPoll()
        scrapeBusy.value = false
        await loadLists()
        refreshSelected()
        ElMessage({
          type: job.status === 'completed' ? 'success' : 'warning',
          message: job.status === 'completed'
            ? `刮削任务 #${jobId} 完成`
            : `刮削任务 #${jobId} 结束（存在失败项）`,
        })
      }
    } catch {
      stopPoll()
      scrapeBusy.value = false
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
  height: 62vh;
  min-height: 420px;
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
  border-radius: var(--glass-radius-sm);
  align-self: flex-start;
  flex-shrink: 0;
  box-shadow: var(--glass-shadow);
}
.cover :deep(img) {
  display: block;
  width: 100%;
  height: auto;
}
.thumb {
  height: 90px;
  width: auto;
  border-radius: 6px;
  flex-shrink: 0;
}
.zoomable {
  cursor: zoom-in;
}
.thumb :deep(img) {
  display: block;
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

/* 多源对比 */
.cmp-toolbar {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-shrink: 0;
}
.cmp-number-input {
  width: 170px;
  flex-shrink: 0;
  font-family: Consolas, monospace;
}
.cmp-toolbar :deep(.el-button) {
  flex-shrink: 0;
}
.cmp-scroll {
  flex: 1;
  min-height: 0;
}
/* el-scrollbar 的滚动条悬浮在内容之上，右侧留出空间避免输入框压到滚动条 */
.cmp-body {
  padding-right: 12px;
}
.cmp-main {
  display: flex;
  gap: 14px;
  min-width: 0;
}
.cmp-fields {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.cmp-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.cmp-row .k {
  width: 66px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  padding-top: 6px;
}
.cmp-select {
  width: 110px;
  flex-shrink: 0;
}
.cmp-value {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  line-height: 1.6;
  word-break: break-all;
  padding-top: 5px;
  white-space: pre-line;
}
.cmp-value.multiline {
  max-height: 110px;
  overflow: auto;
}
.cmp-manual {
  padding-top: 0;
}
/* 简介 textarea：禁用横向拖拽，避免拖宽后压到滚动条 */
.cmp-row :deep(.el-textarea__inner) {
  resize: none;
}
.cmp-thumbs {
  margin-top: 12px;
  border-top: 1px solid var(--glass-border-soft);
  padding-top: 10px;
}
.cmp-thumbs .k {
  display: block;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.thumbs-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.thumbs-row .more {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
