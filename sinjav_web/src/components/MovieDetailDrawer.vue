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
              :src="localImg(detail.number, 'poster.jpg')"
              alt=""
              @error="imgFallback($event, imgProxy(detail.coverUrl))"
            />
            <div v-else class="cover-placeholder">{{ detail.number }}</div>
          </div>
          <div class="hero-info">
            <div class="hero-top">
              <span class="num mono">{{ detail.number }}</span>
              <el-tag size="small" :type="movieStatusTagTypes[detail.status]">
                {{ movieStatusLabels[detail.status] }}
              </el-tag>
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
          <el-image
            v-for="(url, i) in detail.previewUrls"
            :key="url"
            :src="localImg(detail.number, `preview${i + 1}.jpg`)"
            :initial-index="i"
            fit="cover"
            :preview-src-list="detail.previewUrls.map(imgProxy)"
            :hide-on-click-modal="true"
            loading="lazy"
            class="preview-img"
          />
        </div>

        <div class="section-title">文件记录（{{ detail.fileRecords.length }}）</div>
        <el-table :data="detail.fileRecords" size="small" border>
          <el-table-column prop="fileName" label="文件名" min-width="160" show-overflow-tooltip />
          <el-table-column label="大小" width="80">
            <template #default="{ row }">{{ formatFileSize(row.fileSize) }}</template>
          </el-table-column>
          <el-table-column label="识别" width="90">
            <template #default="{ row }: { row: FileRecord }">
              <el-tag :type="recognizeStatusTagTypes[row.recognizeStatus]" size="small">
                {{ recognizeStatusLabels[row.recognizeStatus] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="整理" width="90">
            <template #default="{ row }: { row: FileRecord }">
              <el-tag :type="organizeStatusTagTypes[row.organizeStatus]" size="small">
                {{ organizeStatusLabels[row.organizeStatus] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="targetPath" label="目标路径" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.targetPath ?? '-' }}</template>
          </el-table-column>
        </el-table>
      </template>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { api, imgFallback, imgProxy, localImg } from '../api'
import type { FileRecord, MovieDetail } from '../api/types'
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
const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

const detail = ref<MovieDetail | null>(null)
const error = ref('')

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
  background: linear-gradient(160deg, rgba(255, 255, 255, 0.6), rgba(200, 214, 255, 0.35));
}
.cover-wrap img {
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
  font-size: 15px;
  font-weight: 700;
  color: var(--el-color-primary);
  letter-spacing: 0.5px;
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

/* ---------- 区块标题 ---------- */
.section-title {
  margin: 20px 0 10px;
  padding-left: 10px;
  font-size: 14px;
  font-weight: 600;
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
  aspect-ratio: 16 / 9;
  border-radius: var(--glass-radius-sm);
  cursor: pointer;
  box-shadow: var(--glass-shadow);
}
</style>
