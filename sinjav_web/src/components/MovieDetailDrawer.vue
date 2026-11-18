<template>
  <el-drawer
    :model-value="modelValue"
    size="640px"
    :title="detail?.number ?? '详情'"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <template #default>
      <el-empty v-if="error" :description="error" />
      <el-skeleton v-else-if="!detail" :rows="8" animated />
      <template v-else>
        <div class="detail-top">
          <div class="detail-cover">
            <img
              v-if="detail.coverUrl"
              :src="localImg(detail.number, 'poster.jpg')"
              alt=""
              @error="imgFallback($event, imgProxy(detail.coverUrl))"
            />
            <div v-else class="cover-placeholder">{{ detail.number }}</div>
          </div>
          <el-descriptions :column="1" class="detail-info">
            <el-descriptions-item label="标题">{{ detail.title || '-' }}</el-descriptions-item>
            <el-descriptions-item label="女优">
              {{ detail.actresses.join(' / ') || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="厂商">{{ detail.maker || '-' }}</el-descriptions-item>
            <el-descriptions-item label="系列">{{ detail.series || '-' }}</el-descriptions-item>
            <el-descriptions-item label="发行日期">{{ detail.releaseDate || '-' }}</el-descriptions-item>
            <el-descriptions-item label="时长">
              {{ detail.durationMin ? `${detail.durationMin} 分钟` : '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="数据源">
              {{ detail.source ?? '-' }}
              <el-tag v-if="detail.status === 'failed'" type="danger" size="small">
                刮削失败
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <el-alert
          v-if="detail.failReason"
          :title="`失败原因：${detail.failReason}`"
          type="error"
          :closable="false"
          class="block"
        />
        <el-alert
          v-if="detail.plot"
          :title="detail.plot"
          type="info"
          :closable="false"
          class="block"
        />

        <h4 class="section-title">剧照（{{ detail.previewUrls.length }}）</h4>
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

        <h4 class="section-title">文件记录（{{ detail.fileRecords.length }}）</h4>
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
.detail-top {
  display: flex;
  gap: 16px;
}
.detail-cover {
  width: 150px;
  flex-shrink: 0;
  aspect-ratio: 2 / 3;
  background: linear-gradient(160deg, rgba(255, 255, 255, 0.6), rgba(200, 214, 255, 0.35));
  border: 1px solid var(--glass-border-soft);
  border-radius: var(--glass-radius-sm);
  box-shadow: var(--glass-shadow);
  overflow: hidden;
}
.detail-cover img {
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
.detail-info {
  flex: 1;
}
.block {
  margin: 12px 0;
}
.section-title {
  margin: 16px 0 8px;
}
.preview-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.preview-img {
  width: 100%;
  aspect-ratio: 16 / 9;
  border-radius: 4px;
  cursor: pointer;
}
</style>
