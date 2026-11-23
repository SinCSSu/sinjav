<template>
  <el-drawer
    :model-value="modelValue"
    size="720px"
    :title="detail?.number ?? '详情'"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <template #default>
      <el-empty v-if="error" :description="error" />
      <el-skeleton v-else-if="!detail" :rows="8" animated />
      <template v-else>
        <!-- 头部：封面 + 标题区 -->
        <div class="hero">
          <div class="cover-wrap">
            <img
              v-if="detail.coverUrl"
              class="zoomable"
              :src="localImg(detail.number, 'poster.jpg')"
              alt=""
              @error="imgFallback($event, imgProxy(detail.coverUrl))"
              @click="openViewer([imgProxy(detail.coverUrl)], 0)"
            />
            <div v-else class="cover-placeholder">{{ detail.number }}</div>
          </div>
          <div class="hero-info">
            <div class="hero-top">
              <span class="num mono">{{ detail.number }}</span>
              <el-tag size="small" :type="movieStatusTagTypes[detail.status]">
                {{ movieStatusLabels[detail.status] }}
              </el-tag>
              <span class="hero-actions">
                <template v-if="detail.status === 'scraped'">
                  <el-button size="small" :loading="retryingImages" @click="retryImages">重下图片</el-button>
                  <el-button size="small" @click="emit('scrape')">重新刮削</el-button>
                  <el-button size="small" type="primary" @click="emit('organize')">整理</el-button>
                </template>
                <template v-else>
                  <el-button size="small" type="primary" @click="emit('scrape')">刮削</el-button>
                  <el-button size="small" @click="emit('manual')">手动落库</el-button>
                </template>
              </span>
            </div>
            <h3 class="title">{{ detail.title || '—' }}</h3>
            <div
              v-if="detail.titleZh && detail.titleZh !== detail.title"
              class="orig-title"
            >
              {{ detail.titleZh }}
            </div>
            <div
              v-else-if="detail.titleOrig && detail.titleOrig !== detail.title"
              class="orig-title"
            >
              原名：{{ detail.titleOrig }}
            </div>
            <div class="chip-row">
              <span v-if="detail.releaseDate" class="chip">{{ detail.releaseDate }}</span>
              <span v-if="detail.durationMin" class="chip">{{ detail.durationMin }} 分钟</span>
              <span v-if="detail.maker" class="chip">{{ detail.maker }}</span>
              <span v-if="detail.label" class="chip">{{ detail.label }}</span>
              <span v-if="detail.series" class="chip">系列 · {{ detail.series }}</span>
              <span v-if="detail.director" class="chip">导演 · {{ detail.director }}</span>
            </div>
            <div v-if="detail.actresses.length" class="tag-row">
              <el-tag v-for="a in detail.actresses" :key="a" size="small" type="primary" effect="plain">
                {{ a }}
              </el-tag>
            </div>
            <div class="meta-row">
              <span>数据源 {{ detail.source ?? '—' }}</span>
              <span v-if="detail.coverSource">封面来源 {{ detail.coverSource }}</span>
              <span v-if="(detail.coverCandidates?.length ?? 0) > 1">封面候选 {{ detail.coverCandidates!.length }} 个</span>
              <el-tag v-if="detail.status === 'failed'" type="danger" size="small">刮削失败</el-tag>
            </div>
          </div>
        </div>

        <!-- 类别 -->
        <div v-if="detail.genres?.length" class="genre-row">
          <span class="row-label">类别</span>
          <el-tag
            v-for="g in detail.genres"
            :key="g"
            size="small"
            type="info"
            effect="plain"
          >
            {{ g }}
          </el-tag>
        </div>

        <el-alert
          v-if="detail.failReason"
          :title="`失败原因：${detail.failReason}`"
          type="error"
          :closable="false"
          class="block"
        />

        <!-- 简介 -->
        <div v-if="detail.plot" class="plot-card">
          <div class="row-label">简介</div>
          <p class="plot-text">{{ detail.plot }}</p>
        </div>

        <div class="section-title">剧照（{{ detail.previewUrls.length }}）</div>
        <el-empty
          v-if="detail.previewUrls.length === 0"
          description="无剧照"
          :image-size="60"
        />
        <div v-else class="preview-grid">
          <img
            v-for="(url, i) in detail.previewUrls"
            :key="url"
            :src="localImg(detail.number, `preview${i + 1}.jpg`)"
            loading="lazy"
            alt=""
            class="preview-img zoomable"
            @error="imgFallback($event, imgProxy(url))"
            @click="openViewer(detail.previewUrls.map(imgProxy), i)"
          />
        </div>

        <div class="section-title">文件记录（{{ detail.fileRecords.length }}）</div>
        <el-table empty-text="暂无数据" :data="detail.fileRecords" size="small" border>
          <el-table-column align="center" prop="fileName" label="文件名" min-width="160" show-overflow-tooltip />
          <el-table-column align="center" label="大小" width="80">
            <template #default="{ row }">{{ formatFileSize(row.fileSize) }}</template>
          </el-table-column>
          <el-table-column align="center" label="识别" width="90">
            <template #default="{ row }: { row: FileRecord }">
              <el-tag :type="recognizeStatusTagTypes[row.recognizeStatus]" size="small">
                {{ recognizeStatusLabels[row.recognizeStatus] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column align="center" label="整理" width="90">
            <template #default="{ row }: { row: FileRecord }">
              <el-tag :type="organizeStatusTagTypes[row.organizeStatus]" size="small">
                {{ organizeStatusLabels[row.organizeStatus] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column align="center" prop="sourcePath" label="本地路径" min-width="180" show-overflow-tooltip />
          <el-table-column align="center" prop="targetPath" label="目标路径" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.targetPath ?? '-' }}</template>
          </el-table-column>
        </el-table>
      </template>
    </template>
  </el-drawer>
  <ImagePreviewViewer
    :visible="viewerVisible"
    :urls="viewerUrls"
    :initial-index="viewerIndex"
    @close="viewerVisible = false"
  />
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, imgFallback, imgProxy, localImg } from '../api'
import type { FileRecord, MovieDetail } from '../api/types'
import ImagePreviewViewer from './ImagePreviewViewer.vue'
import {
  formatFileSize,
  movieStatusLabels,
  movieStatusTagTypes,
  organizeStatusLabels,
  organizeStatusTagTypes,
  recognizeStatusLabels,
  recognizeStatusTagTypes,
} from '../utils'

const props = defineProps<{ modelValue: boolean; number: string }>()
const emit = defineEmits<{
  'update:modelValue': [boolean]
  /** 快捷刮削/重新刮削：打开刮削弹窗并进入该影片的多源对比 */
  scrape: []
  /** 快捷手动落库：打开手动录入弹窗 */
  manual: []
  /** 快捷整理：打开整理弹窗并预选该影片的文件记录 */
  organize: []
}>()

const detail = ref<MovieDetail | null>(null)
const error = ref('')
const retryingImages = ref(false)

/** 重下图片：按持久化封面候选逐个重试（下载失败自动换源），完成后刷新详情 */
async function retryImages() {
  if (!detail.value) return
  retryingImages.value = true
  try {
    const res = await api.retryMovieImages(detail.value.number)
    ElMessage.success(`图片已重新下载${res.coverSource ? `（封面来源 ${res.coverSource}）` : '（封面下载失败，请检查数据源）'}`)
    await load(detail.value.number)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    retryingImages.value = false
  }
}

// 大图预览
const viewerVisible = ref(false)
const viewerUrls = ref<string[]>([])
const viewerIndex = ref(0)

function openViewer(urls: string[], index: number) {
  viewerUrls.value = urls
  viewerIndex.value = index
  viewerVisible.value = true
}

watch(
  () => [props.modelValue, props.number] as const,
  ([visible, number]) => {
    if (visible && number) load(number)
    if (!visible) {
      detail.value = null
      error.value = ''
    }
  },
)

async function load(number: string) {
  detail.value = null
  error.value = ''
  try {
    detail.value = await api.getMovie(number)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}
</script>

<style scoped>
/* ---------- 头部：封面 + 标题区 ---------- */
.hero {
  display: flex;
  gap: 18px;
}
.cover-wrap {
  width: 168px;
  flex-shrink: 0;
  aspect-ratio: 2 / 3;
  border-radius: var(--glass-radius);
  overflow: hidden;
  box-shadow: var(--glass-shadow);
  background: var(--cover-placeholder-bg);
}
.cover-wrap img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.zoomable {
  cursor: zoom-in;
}
.cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  font-family: Consolas, monospace;
}
.hero-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.hero-top {
  display: flex;
  align-items: center;
  gap: 10px;
}
.hero-top .num {
  font-size: 14px;
  font-weight: 700;
  color: var(--el-color-primary);
  letter-spacing: 0.5px;
}
.hero-actions {
  margin-left: auto;
  display: flex;
  gap: 6px;
}
.title {
  margin: 0;
  font-size: 16px;
  font-weight: 650;
  line-height: 1.5;
  word-break: break-all;
}
.orig-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}
.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chip {
  font-size: 12px;
  padding: 3px 10px;
  border-radius: var(--el-border-radius-round);
  background: var(--glass-accent);
  color: var(--el-text-color-regular);
  border: 1px solid var(--glass-border-soft);
}
.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.meta-row {
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* ---------- 类别 / 简介 ---------- */
.row-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.genre-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 16px;
  padding: 10px 12px;
  border-radius: var(--glass-radius-sm);
  background: var(--glass-bg-soft);
  border: 1px solid var(--glass-border-soft);
}
.plot-card {
  margin-top: 14px;
  padding: 12px 14px;
  border-radius: var(--glass-radius-sm);
  background: var(--glass-bg-soft);
  border: 1px solid var(--glass-border-soft);
  border-left: 3px solid var(--el-color-primary-light-5);
}
.plot-text {
  margin: 6px 0 0;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-line;
  word-break: break-all;
  color: var(--el-text-color-regular);
}

/* ---------- 区块标题：间距/字号走全局基线，仅保留左侧主色装饰 ---------- */
.section-title {
  padding-left: 10px;
  font-size: 14px;
  border-left: 3px solid var(--el-color-primary);
  line-height: 1.4;
}
.block {
  margin-top: 14px;
}

/* ---------- 剧照 ---------- */
.preview-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.preview-img {
  width: 100%;
  height: auto;
  display: block;
  border-radius: var(--glass-radius-sm);
  cursor: pointer;
  box-shadow: var(--glass-shadow);
}
</style>
