<template>
  <div>
    <div class="page-header">
      <h2>影片库</h2>
    </div>

    <div class="toolbar">
      <el-radio-group v-model="source" @change="switchSource">
        <el-radio-button value="local">本地</el-radio-button>
        <el-radio-button value="emby">Emby</el-radio-button>
      </el-radio-group>
      <el-select
        v-if="source === 'emby'"
        v-model="libraryId"
        placeholder="选择 Emby 媒体库"
        style="width: 200px"
        @change="search"
      >
        <el-option v-for="lib in libraries" :key="lib.id" :label="lib.name" :value="lib.id" />
      </el-select>
      <el-input
        v-model="keyword"
        :placeholder="source === 'emby' ? '按标题搜索' : '按番号或标题搜索'"
        clearable
        style="width: 260px"
        @keyup.enter="search"
        @clear="search"
      >
        <template #append>
          <el-button :icon="SearchIcon" @click="search" />
        </template>
      </el-input>
      <template v-if="source === 'local'">
        <el-select v-model="status" clearable placeholder="刮削状态" style="width: 130px" @change="search">
          <el-option label="待刮削" value="pending" />
          <el-option label="已刮削" value="scraped" />
          <el-option label="刮削失败" value="failed" />
        </el-select>
        <el-select v-model="sort" style="width: 130px" @change="search">
          <el-option label="入库时间" value="created_at" />
          <el-option label="发行日期" value="release_date" />
          <el-option label="番号" value="number" />
        </el-select>
        <el-button :icon="order === 'desc' ? SortDownIcon : SortUpIcon" @click="toggleOrder">
          {{ order === 'desc' ? '降序' : '升序' }}
        </el-button>
        <el-tooltip content="遍历 Emby 媒体库识别番号并写入本地快照，用于标记「已存在于emby」与整理弹窗「已存在」分组" placement="top">
          <el-button :loading="syncingEmby" @click="syncEmby">同步 Emby</el-button>
        </el-tooltip>
      </template>
    </div>

    <el-alert
      v-if="source === 'emby' && embyError"
      :title="embyError"
      type="error"
      show-icon
      :closable="false"
      class="emby-error"
    />

    <el-empty v-if="!embyError && (source === 'emby' ? embyItems : items).length === 0" :description="emptyText" />
    <template v-else-if="!embyError">
      <!-- 本地影片卡片 -->
      <div v-if="source === 'local'" class="movie-grid">
        <div v-for="m in items" :key="m.number" class="movie-card" @click="openDetail(m.number)">
          <div class="cover">
            <img
              v-if="m.coverUrl"
              :src="localImg(m.number, 'poster.jpg')"
              loading="lazy"
              alt=""
              @error="imgFallback($event, imgProxy(m.coverUrl))"
            />
            <div v-else class="cover-placeholder">{{ m.number }}</div>
            <el-tag
              class="status-badge"
              :type="movieStatusTagTypes[m.status]"
              size="small"
            >
              {{ movieStatusLabels[m.status] }}
            </el-tag>
            <el-tag
              v-if="m.embyExists"
              class="emby-badge"
              size="small"
              type="warning"
              effect="dark"
            >
              Emby已有
            </el-tag>
            <el-button
              class="delete-btn"
              :icon="DeleteIcon"
              circle
              size="small"
              type="danger"
              title="删除记录"
              @click.stop="removeMovie(m)"
            />
          </div>
          <div class="info">
            <div class="number">{{ m.number }}</div>
            <div class="title" :title="m.title ?? ''">{{ m.title || '—' }}</div>
            <div v-if="m.titleOrig && m.titleOrig !== m.title" class="title-orig" :title="m.titleOrig">
              {{ m.titleOrig }}
            </div>
            <div class="meta">
              <span class="ellipsis">{{ m.actresses.join(' / ') }}</span>
              <span v-if="m.releaseDate">{{ m.releaseDate }}</span>
            </div>
            <div v-if="m.plot" class="plot" :title="m.plot">{{ m.plot }}</div>
            <div class="card-actions">
              <template v-if="m.status === 'scraped'">
                <el-button size="small" @click.stop="quickScrape(m.number)">重新刮削</el-button>
                <el-button size="small" type="primary" @click.stop="quickOrganize(m.number)">整理</el-button>
              </template>
              <template v-else>
                <el-button size="small" type="primary" @click.stop="quickScrape(m.number)">刮削</el-button>
                <el-button size="small" @click.stop="quickManual(m.number)">手动落库</el-button>
              </template>
            </div>
          </div>
        </div>
      </div>

      <!-- Emby 条目卡片 -->
      <div v-else class="movie-grid">
        <div v-for="item in embyItems" :key="item.id" class="movie-card" @click="openEmbyDetail(item.id)">
          <div class="cover">
            <img v-if="item.hasImage" :src="embyPosterUrl(item.id)" loading="lazy" alt="" />
            <div v-else class="cover-placeholder">{{ item.name }}</div>
            <el-tag class="status-badge" size="small" type="primary" effect="dark">Emby</el-tag>
          </div>
          <div class="info">
            <div class="number ellipsis" :title="item.name">{{ item.name }}</div>
            <div v-if="item.originalTitle && item.originalTitle !== item.name" class="title-orig" :title="item.originalTitle">
              {{ item.originalTitle }}
            </div>
            <div class="meta">
              <span v-if="item.productionYear">{{ item.productionYear }}</span>
              <span v-if="item.premiereDate">{{ item.premiereDate.slice(0, 10) }}</span>
            </div>
          </div>
        </div>
      </div>
    </template>

    <div v-if="totalPages > 1" class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next, jumper"
        background
        @current-change="source === 'emby' ? loadEmby() : load()"
      />
    </div>

    <MovieDetailDrawer
      v-model="detailVisible"
      :number="detailNumber"
      @scrape="quickScrape(detailNumber)"
      @organize="quickOrganize(detailNumber)"
      @manual="quickManual(detailNumber)"
    />
    <EmbyItemDrawer v-model="embyDetailVisible" :item-id="embyDetailId" />
    <ScrapeDialog v-model="showScrape" :preset-number="scrapePreset || undefined" @created="load" />
    <ManualEntryDialog v-model="showManual" :preset-number="manualPreset || undefined" @created="load" />
    <OrganizeDialog
      v-model="showOrganize"
      :preset-numbers="organizePreset.length ? organizePreset : undefined"
      @created="load"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Delete as DeleteIcon, Search as SearchIcon, SortDown as SortDownIcon, SortUp as SortUpIcon } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, embyPosterUrl, imgFallback, imgProxy, localImg } from '../api'
import type { EmbyItemSummary, EmbyLibrary, MovieSort, MovieStatus, MovieSummary } from '../api/types'
import { movieStatusLabels, movieStatusTagTypes } from '../utils'
import MovieDetailDrawer from '../components/MovieDetailDrawer.vue'
import EmbyItemDrawer from '../components/EmbyItemDrawer.vue'
import ScrapeDialog from '../components/ScrapeDialog.vue'
import ManualEntryDialog from '../components/ManualEntryDialog.vue'
import OrganizeDialog from '../components/OrganizeDialog.vue'

const source = ref<'local' | 'emby'>('local')

const keyword = ref('')
const status = ref<MovieStatus | ''>('')
const sort = ref<MovieSort>('created_at')
const order = ref<'asc' | 'desc'>('desc')
const page = ref(1)
const size = ref(24)
const total = ref(0)
const items = ref<MovieSummary[]>([])
const embyItems = ref<EmbyItemSummary[]>([])

// Emby 数据源（实时代理，不落库）
const libraries = ref<EmbyLibrary[]>([])
const libraryId = ref('')
const embyError = ref('')
const syncingEmby = ref(false)

/** 同步 Emby 媒体库：识别番号写入本地快照，完成后刷新列表（卡片标记随之更新） */
async function syncEmby() {
  syncingEmby.value = true
  try {
    const r = await api.syncEmby()
    ElMessage.success(`Emby 同步完成：扫描 ${r.scanned} 条，识别番号 ${r.matched} 条`)
    load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    syncingEmby.value = false
  }
}

const totalPages = computed(() => Math.ceil(total.value / size.value))
const emptyText = computed(() =>
  source.value === 'emby'
    ? 'Emby 媒体库暂无条目，请选择其他媒体库'
    : '暂无影片数据，请先执行扫描',
)

const detailVisible = ref(false)
const detailNumber = ref('')
const embyDetailVisible = ref(false)
const embyDetailId = ref('')

// 卡片快捷刮削/整理
const showScrape = ref(false)
const scrapePreset = ref('')
const showManual = ref(false)
const manualPreset = ref('')
const showOrganize = ref(false)
const organizePreset = ref<string[]>([])

/** 卡片/详情抽屉刮削、重新刮削：打开刮削弹窗并直接进入该影片的多源对比界面 */
function quickScrape(number: string) {
  if (!number) return
  scrapePreset.value = number
  showScrape.value = true
}

/** 卡片/详情抽屉手动落库：打开手动录入弹窗（预填番号） */
function quickManual(number: string) {
  if (!number) return
  manualPreset.value = number
  showManual.value = true
}

/** 卡片/详情抽屉整理：打开整理弹窗并预选该影片的待整理文件记录 */
function quickOrganize(number: string) {
  if (!number) return
  organizePreset.value = [number]
  showOrganize.value = true
}

// 弹窗关闭后清空预选目标，避免下次手动打开时残留
watch(showScrape, (v) => {
  if (!v) scrapePreset.value = ''
})
watch(showManual, (v) => {
  if (!v) manualPreset.value = ''
})
watch(showOrganize, (v) => {
  if (!v) organizePreset.value = []
})

function search() {
  page.value = 1
  if (source.value === 'emby') loadEmby()
  else load()
}

function switchSource() {
  page.value = 1
  keyword.value = ''
  embyError.value = ''
  items.value = []
  embyItems.value = []
  total.value = 0
  if (source.value === 'emby') {
    loadLibraries()
  } else {
    load()
  }
}

async function loadLibraries() {
  embyError.value = ''
  try {
    libraries.value = await api.listEmbyLibraries()
    if (libraries.value.length === 0) {
      embyError.value = 'Emby 中没有可用媒体库'
      return
    }
    if (!libraryId.value || !libraries.value.some((l) => l.id === libraryId.value)) {
      libraryId.value = libraries.value[0].id
    }
    loadEmby()
  } catch (e) {
    embyError.value = e instanceof Error ? e.message : String(e)
    items.value = []
    total.value = 0
  }
}

async function loadEmby() {
  if (!libraryId.value) return
  try {
    const result = await api.listEmbyItems({
      libraryId: libraryId.value,
      page: page.value,
      size: size.value,
      keyword: keyword.value.trim() || undefined,
    })
    embyItems.value = result.items
    total.value = result.total
    embyError.value = ''
  } catch (e) {
    items.value = []
    total.value = 0
    embyError.value = e instanceof Error ? e.message : String(e)
  }
}

function toggleOrder() {
  order.value = order.value === 'desc' ? 'asc' : 'desc'
  search()
}

async function load() {
  try {
    const result = await api.listMovies({
      page: page.value,
      size: size.value,
      keyword: keyword.value.trim() || undefined,
      status: status.value || undefined,
      sort: sort.value,
      order: order.value,
    })
    items.value = result.items
    total.value = result.total
  } catch (e) {
    items.value = []
    total.value = 0
    console.error(e)
  }
}

function openDetail(number: string) {
  detailNumber.value = number
  detailVisible.value = true
}

function openEmbyDetail(id: string) {
  embyDetailId.value = id
  embyDetailVisible.value = true
}

async function removeMovie(m: MovieSummary) {
  try {
    await ElMessageBox.confirm(
      `确定删除 ${m.number} 的记录？将删除影片记录、关联的文件记录与本地刮削图片缓存；不删除任何视频文件。`,
      '删除记录',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' },
    )
  } catch {
    return
  }
  try {
    await api.deleteMovie(m.number)
    ElMessage.success(`已删除 ${m.number}`)
    load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

onMounted(load)
</script>

<style scoped>
.emby-error {
  margin-bottom: 14px;
}
.movie-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 16px;
}
.movie-card {
  cursor: pointer;
  border: 1px solid var(--glass-border);
  border-radius: var(--glass-radius);
  overflow: hidden;
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  box-shadow: var(--glass-shadow);
  transition: transform 0.2s ease, box-shadow 0.2s ease;
  display: flex;
  flex-direction: column;
}
.movie-card:hover {
  transform: translateY(-3px);
  box-shadow: var(--glass-shadow-hover);
}
.cover {
  position: relative;
  aspect-ratio: 2 / 3;
  background: linear-gradient(160deg, rgba(255, 255, 255, 0.6), rgba(200, 214, 255, 0.35));
}
.cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  font-family: Consolas, monospace;
  font-size: 16px;
}
.status-badge {
  position: absolute;
  top: 8px;
  left: 8px;
}
.emby-badge {
  position: absolute;
  top: 32px;
  left: 8px;
}
.delete-btn {
  position: absolute;
  top: 8px;
  right: 8px;
  opacity: 0;
  transition: opacity 0.15s;
}
.movie-card:hover .delete-btn {
  opacity: 1;
}
.info {
  padding: 8px 10px 10px;
  flex: 1;
  display: flex;
  flex-direction: column;
}
.number {
  font-family: Consolas, monospace;
  font-weight: 600;
  color: var(--el-color-primary);
}
.title {
  font-size: 12px;
  margin-top: 4px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 2em;
}
.title-orig {
  margin-top: 2px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  display: flex;
  justify-content: space-between;
  gap: 8px;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.plot {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
  white-space: pre-line;
  word-break: break-all;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.card-actions {
  margin-top: auto;
  padding-top: 8px;
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}
</style>
