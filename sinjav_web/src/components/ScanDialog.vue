<template>
  <el-dialog
    v-model="visible"
    title="触发扫描"
    width="min(1380px, 96vw)"
    top="3vh"
    :close-on-click-modal="false"
    class="scan-dialog"
  >
    <div class="scan-layout">
      <!-- 左栏：扫描目录配置 -->
      <div class="col col-left">
        <h4 class="col-title">扫描目录</h4>
        <DirTreeInput v-model="dirInput">
          <template #append>
            <el-button type="primary" :loading="previewing" @click.stop="doPreview">
              扫描
            </el-button>
          </template>
        </DirTreeInput>
        <div class="opt-row">
          <span>递归子目录</span>
          <el-switch v-model="recursive" size="small" />
        </div>
        <el-collapse class="advanced">
          <el-collapse-item title="高级设置">
            <div class="field">
              <span class="field-label">后缀名</span>
              <el-select
                v-model="extensions"
                multiple
                filterable
                allow-create
                default-first-option
                collapse-tags
                collapse-tags-tooltip
                :max-collapse-tags="2"
                size="small"
                placeholder="如 mp4 / mkv"
                class="w-full ext-select"
              />
            </div>
            <div class="field">
              <span class="field-label">最小文件大小（MB）</span>
              <el-input-number v-model="minFileSizeMb" :min="0" size="small" class="w-full" />
            </div>
            <div class="field">
              <span class="field-label">文件名匹配正则（空 = 不过滤）</span>
              <el-input
                v-model="filenamePattern"
                size="small"
                placeholder="如 (?i)(?:^|[^a-z0-9])[a-z]{2,6}[-_]?\d{2,5}"
              />
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>

      <!-- 中栏：扫描结果 -->
      <div class="col col-mid">
        <div class="col-title mid-title">
          <span>扫描结果</span>
          <span v-if="previewData" class="stat">
            共 {{ previewData.total }} 个 · 可识别 {{ recognizedCount }} · 已移除 {{ removedSet.size }}
          </span>
        </div>
        <div v-if="previewData && previewData.items.length" class="batch-bar">
          <el-checkbox
            :model-value="allSelected"
            :indeterminate="selectedSet.size > 0 && !allSelected"
            @change="toggleSelectAll"
          >
            全选
          </el-checkbox>
          <el-button icon="Delete" type="danger" size="small" :disabled="selectedSet.size === 0" @click="removeSelected">
            移除所选{{ selectedSet.size ? ` (${selectedSet.size})` : '' }}
          </el-button>
        </div>
        <el-alert
          v-if="previewError"
          :title="previewError"
          type="error"
          :closable="false"
        />
        <el-alert
          v-else-if="previewData?.truncated"
          title="结果过多，仅显示前 5000 条"
          type="warning"
          :closable="false"
        />
        <el-empty
          v-else-if="!previewData || previewData.items.length === 0"
          :description="previewData ? '没有符合条件的文件' : '暂无预览'"
          :image-size="70"
        />
        <el-scrollbar v-else class="result-list">
          <div
            v-for="item in previewData.items"
            :key="item.sourcePath"
            class="result-row"
            :class="{ removed: removedSet.has(item.sourcePath), active: selectedFile?.sourcePath === item.sourcePath }"
            @click="selectedFile = item"
          >
            <span class="row-check" @click.stop>
              <el-checkbox
                :model-value="selectedSet.has(item.sourcePath)"
                @change="toggleSelect(item.sourcePath)"
              />
            </span>
            <div class="row-main">
              <div class="row-name" :title="item.sourcePath">{{ item.fileName }}</div>
              <div class="row-meta">
                <span class="mono">{{ formatFileSize(item.fileSize) }}</span>
                <el-tag v-if="item.number" type="success" size="small">{{ item.number }}</el-tag>
                <el-tag v-else type="info" size="small">未识别</el-tag>
              </div>
            </div>
            <el-button
              v-if="!removedSet.has(item.sourcePath)"
              link
              type="danger"
              size="small"
              @click.stop="removedSet.add(item.sourcePath)"
            >
              移除
            </el-button>
            <el-button v-else link size="small" @click.stop="removedSet.delete(item.sourcePath)">
              撤销
            </el-button>
          </div>
        </el-scrollbar>
      </div>

      <!-- 右栏：详情与预览 -->
      <div class="col col-right">
        <h4 class="col-title">详情</h4>
        <el-empty v-if="!selectedFile" description="暂无详情" :image-size="70" />
        <template v-else>
          <div class="detail-rows">
            <div class="detail-row"><span class="k">文件名</span><span>{{ selectedFile.fileName }}</span></div>
            <div class="detail-row"><span class="k">路径</span><span class="mono">{{ selectedFile.sourcePath }}</span></div>
            <div class="detail-row"><span class="k">大小</span><span>{{ formatFileSize(selectedFile.fileSize) }}</span></div>
            <div class="detail-row"><span class="k">修改时间</span><span>{{ formatDateTime(selectedFile.fileMtime) }}</span></div>
            <div class="detail-row">
              <span class="k">识别番号</span>
              <el-tag v-if="selectedFile.number" type="success" size="small">{{ selectedFile.number }}</el-tag>
              <el-tag v-else type="info" size="small">未识别</el-tag>
            </div>
          </div>
          <div v-if="isPlayable(selectedFile.sourcePath)" class="player-wrap">
            <video
              :key="selectedFile.sourcePath"
              :src="videoUrl(selectedFile.sourcePath)"
              controls
              preload="metadata"
              class="player"
            />
          </div>
          <el-alert
            v-else
            title="该格式浏览器不支持在线预览"
            type="info"
            :closable="false"
          />
        </template>
      </div>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button icon="Check"
          type="success"
          :loading="committing"
          :disabled="!canConfirm"
          @click="doCommit"
        >
          确认
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, videoUrl } from '../api'
import type { ScanPreviewItem, ScanPreviewResponse } from '../api/types'
import { formatDateTime, formatFileSize } from '../utils'
import DirTreeInput from './DirTreeInput.vue'

const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{ created: [] }>()

// ---------- 每次打开重置为全新状态 ----------
watch(visible, (open) => {
  if (open) {
    resetAll()
    loadDefaults()
  }
})

/** 用全局配置回填高级选项默认值 */
async function loadDefaults() {
  try {
    const s = await api.getSettings()
    extensions.value = (s.videoExtensions ?? '')
      .split(',')
      .map((x) => x.trim())
      .filter(Boolean)
    minFileSizeMb.value = s.minFileSizeMb ?? 0
    filenamePattern.value = s.filenamePattern ?? ''
  } catch {
    // 获取失败保持空值，后端仍会回退全局配置
  }
}

function resetAll() {
  dirInput.value = ''
  recursive.value = true
  extensions.value = []
  minFileSizeMb.value = 0
  filenamePattern.value = ''
  previewing.value = false
  committing.value = false
  previewData.value = null
  previewError.value = ''
  removedSet.value = new Set()
  selectedSet.value = new Set()
  selectedFile.value = null
}

// ---------- 左栏 ----------
const dirInput = ref('')
const recursive = ref(true)
const extensions = ref<string[]>([])
const minFileSizeMb = ref(0)
const filenamePattern = ref('')

// ---------- 中栏 ----------
const previewing = ref(false)
const committing = ref(false)
const previewData = ref<ScanPreviewResponse | null>(null)
const previewError = ref('')
const removedSet = ref(new Set<string>())
const selectedSet = ref(new Set<string>())
const selectedFile = ref<ScanPreviewItem | null>(null)

const allSelected = computed(
  () =>
    previewData.value != null &&
    previewData.value.items.length > 0 &&
    previewData.value.items.every((i) => selectedSet.value.has(i.sourcePath)),
)

function toggleSelect(path: string) {
  if (selectedSet.value.has(path)) selectedSet.value.delete(path)
  else selectedSet.value.add(path)
}

function toggleSelectAll(checked: unknown) {
  selectedSet.value = checked
    ? new Set(previewData.value?.items.map((i) => i.sourcePath) ?? [])
    : new Set()
}

function removeSelected() {
  selectedSet.value.forEach((p) => removedSet.value.add(p))
  selectedSet.value = new Set()
}

const recognizedCount = computed(
  () => previewData.value?.items.filter((i) => i.number).length ?? 0,
)

/** 已扫描出结果且仍有保留的文件时才可确认 */
const canConfirm = computed(() => {
  if (!previewData.value) return false
  const kept = previewData.value.items.filter((i) => !removedSet.value.has(i.sourcePath))
  return kept.length > 0
})

async function doPreview() {
  const dir = dirInput.value.trim()
  if (!dir) {
    ElMessage.warning('请先在输入框填入扫描目录')
    return
  }
  previewing.value = true
  previewError.value = ''
  previewData.value = null
  removedSet.value = new Set()
  selectedSet.value = new Set()
  selectedFile.value = null
  try {
    previewData.value = await api.previewScan({
      sourceDirs: [dir],
      recursive: recursive.value,
      extensions: extensions.value.length ? extensions.value : undefined,
      minFileSizeMb: minFileSizeMb.value > 0 ? minFileSizeMb.value : 0,
      filenamePattern: filenamePattern.value.trim() || undefined,
    })
  } catch (e) {
    previewError.value = e instanceof Error ? e.message : String(e)
  } finally {
    previewing.value = false
  }
}

/** 确认：把保留的预览结果直接落库 */
async function doCommit() {
  if (!previewData.value) return
  const kept = previewData.value.items
    .filter((i) => !removedSet.value.has(i.sourcePath))
    .map((i) => ({
      sourcePath: i.sourcePath,
      fileName: i.fileName,
      fileSize: i.fileSize,
      fileMtime: i.fileMtime,
    }))
  if (kept.length === 0) {
    ElMessage.warning('没有保留的文件')
    return
  }
  committing.value = true
  try {
    const result = await api.commitScan(kept)
    ElMessage.success(`已入库 ${result.total} 个文件（识别 ${result.recognized} / 未识别 ${result.unrecognized}）`)
    visible.value = false
    emit('created')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    committing.value = false
  }
}

// ---------- 右栏 ----------
const PLAYABLE_EXTS = new Set(['mp4', 'm4v', 'webm', 'mov'])

function isPlayable(path: string): boolean {
  const ext = path.split('.').pop()?.toLowerCase() ?? ''
  return PLAYABLE_EXTS.has(ext)
}
</script>

<style scoped>
.scan-layout {
  display: grid;
  grid-template-columns: 300px 1fr 380px;
  gap: 12px;
  height: 68vh;
  min-height: 520px;
}
.col {
  display: flex;
  flex-direction: column;
  gap: 10px;
  border: 1px solid var(--glass-border-soft);
  border-radius: var(--glass-radius);
  padding: 14px;
  overflow: hidden;
  background: var(--glass-bg-soft);
  backdrop-filter: var(--glass-blur-light);
  -webkit-backdrop-filter: var(--glass-blur-light);
}
.col-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}
.col-left {
  width: 300px;
}
.opt-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
}
.advanced :deep(.el-collapse-item__header) {
  font-size: 13px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 10px;
}
.field-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.w-full {
  width: 100%;
}
/* 后缀名多选：左栏只有 300px，标签一多会换行把内容顶出输入框边框。
   折叠标签（collapse-tags）已限制为单行，这里再限制单个标签宽度并省略超长文本 */
.ext-select :deep(.el-tag) {
  max-width: 96px;
}
.ext-select :deep(.el-tag .el-tag__content) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mid-title {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.stat {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.batch-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.row-check {
  display: flex;
  align-items: center;
}
.result-list {
  flex: 1;
}
.result-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: var(--el-border-radius-small);
  cursor: pointer;
  transition: background 0.15s;
}
.result-row:hover {
  background: var(--row-hover-bg);
}
.result-row.active {
  background: var(--glass-accent);
  box-shadow: 0 0 0 1px var(--glass-accent-border) inset;
}
.result-row.removed {
  opacity: 0.45;
  text-decoration: line-through;
}
.row-main {
  flex: 1;
  min-width: 0;
}
.row-name {
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.row-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 2px;
}
.dialog-footer {
  display: flex;
  justify-content: flex-end;
}
.detail-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 13px;
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
.player-wrap {
  margin-top: auto;
}
.player {
  width: 100%;
  max-height: 240px;
  border-radius: var(--glass-radius-sm);
  background: rgba(28, 32, 52, 0.88);
  box-shadow: var(--glass-shadow);
}
@media (max-width: 1200px) {
  .scan-layout {
    grid-template-columns: 250px 1fr 300px;
    min-height: 440px;
  }
  .col-left {
    width: 250px;
  }
}
</style>
