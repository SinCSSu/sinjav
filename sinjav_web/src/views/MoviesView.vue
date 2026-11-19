<template>
  <div>
    <div class="page-header">
      <h2>影片库</h2>
    </div>

    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="按番号或标题搜索"
        clearable
        style="width: 260px"
        @keyup.enter="search"
        @clear="search"
      >
        <template #append>
          <el-button :icon="SearchIcon" @click="search" />
        </template>
      </el-input>
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
    </div>

    <el-empty v-if="items.length === 0" description="暂无影片数据，请先执行扫描" />
    <div v-else class="movie-grid">
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
        </div>
      </div>
    </div>

    <div v-if="totalPages > 1" class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next, jumper"
        background
        @current-change="load"
      />
    </div>

    <MovieDetailDrawer v-model="detailVisible" :number="detailNumber" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Delete as DeleteIcon, Search as SearchIcon, SortDown as SortDownIcon, SortUp as SortUpIcon } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, imgFallback, imgProxy, localImg } from '../api'
import type { MovieSort, MovieStatus, MovieSummary } from '../api/types'
import { movieStatusLabels, movieStatusTagTypes } from '../utils'
import MovieDetailDrawer from '../components/MovieDetailDrawer.vue'

const keyword = ref('')
const status = ref<MovieStatus | ''>('')
const sort = ref<MovieSort>('created_at')
const order = ref<'asc' | 'desc'>('desc')
const page = ref(1)
const size = ref(24)
const total = ref(0)
const items = ref<MovieSummary[]>([])

const totalPages = computed(() => Math.ceil(total.value / size.value))

const detailVisible = ref(false)
const detailNumber = ref('')

function search() {
  page.value = 1
  load()
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
</style>
