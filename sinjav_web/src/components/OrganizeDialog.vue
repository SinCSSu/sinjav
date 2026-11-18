<template>
  <el-dialog
    v-model="visible"
    title="触发整理"
    width="1300px"
    top="3vh"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <div class="organize-layout">
      <!-- 第一列：整理参数 -->
      <aside class="col col-params">
        <div class="col-title">整理参数</div>
        <el-form label-position="top" class="param-form">
          <el-form-item label="目标目录">
            <DirTreeInput v-model="targetDir" />
          </el-form-item>
          <el-form-item label="整理方式">
            <el-select v-model="mode" clearable placeholder="留空使用全局配置" style="width: 100%">
              <el-option
                v-for="(label, value) in linkModeLabels"
                :key="value"
                :label="label"
                :value="value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="命名模板">
            <el-input v-model="namingTemplate" placeholder="留空使用全局配置，如 {number}/{number} - {title}" />
          </el-form-item>
          <el-form-item label="冲突策略">
            <el-select v-model="conflictPolicy" clearable placeholder="留空使用全局配置" style="width: 100%">
              <el-option label="跳过" value="skip" />
              <el-option label="覆盖" value="overwrite" />
              <el-option label="重命名" value="rename" />
            </el-select>
          </el-form-item>
        </el-form>
      </aside>

      <!-- 中间列：媒体列表 -->
      <section class="col col-list">
        <div class="list-group">
          <div class="group-head">
            <el-checkbox
              :model-value="pendingAllSelected"
              :indeterminate="pendingSomeSelected && !pendingAllSelected"
              @change="toggleAllPending"
            >
              待整理（{{ pendingTotal }}）
            </el-checkbox>
          </div>
          <div v-loading="loadingPending" class="card-list">
            <label v-for="r in pendingRecords" :key="r.recordId" class="media-card">
              <el-checkbox
                :model-value="selected.has(r.recordId)"
                @change="toggleSelect(r.recordId)"
                @click.stop
              />
              <div class="cover">
                <img
                  v-if="r.coverUrl"
                  :src="localImg(r.number || '', 'poster.jpg')"
                  loading="lazy"
                  alt=""
                  @error="imgFallback($event, imgProxy(r.coverUrl))"
                />
                <div v-else class="cover-placeholder">{{ r.number || '?' }}</div>
              </div>
              <div class="info">
                <div class="num">{{ r.number || '未识别' }}</div>
                <div class="title" :title="r.title || r.fileName">{{ r.title || r.fileName }}</div>
                <div class="path" :title="r.fileName">{{ r.fileName }}</div>
              </div>
            </label>
            <div v-if="pendingRecords.length < pendingTotal" class="load-more">
              <el-button link type="primary" :loading="loadingPending" @click="loadPending(false)">
                加载更多
              </el-button>
            </div>
            <el-empty v-if="!loadingPending && !pendingRecords.length" description="暂无待整理文件" :image-size="60" />
          </div>
        </div>

        <el-collapse v-model="organizedOpen" class="organized-collapse">
          <el-collapse-item name="organized">
            <template #title>
              <span class="group-head-text">已整理（{{ organizedTotal }}）—— 选中后重整将删除原整理结果</span>
            </template>
            <div v-loading="loadingOrganized" class="card-list card-list-organized">
              <label v-for="r in organizedRecords" :key="r.recordId" class="media-card">
                <el-checkbox
                  :model-value="selected.has(r.recordId)"
                  @change="toggleSelect(r.recordId)"
                  @click.stop
                />
                <div class="cover">
                  <img
                    v-if="r.coverUrl"
                    :src="localImg(r.number || '', 'poster.jpg')"
                    loading="lazy"
                    alt=""
                    @error="imgFallback($event, imgProxy(r.coverUrl))"
                  />
                  <div v-else class="cover-placeholder">{{ r.number || '?' }}</div>
                </div>
                <div class="info">
                  <div class="num">{{ r.number || '未识别' }}</div>
                  <div class="title" :title="r.title || r.fileName">{{ r.title || r.fileName }}</div>
                  <div class="path" :title="r.targetPath || ''">
                    <el-tag size="small" type="success">已整理</el-tag>
                    {{ shortPath(r.targetPath) }}
                  </div>
                </div>
              </label>
              <div v-if="organizedLoaded && organizedRecords.length < organizedTotal" class="load-more">
                <el-button link type="primary" :loading="loadingOrganized" @click="loadOrganized(false)">
                  加载更多
                </el-button>
              </div>
              <el-empty v-if="organizedLoaded && !organizedRecords.length" description="暂无已整理文件" :image-size="60" />
              <div v-if="!organizedLoaded" class="load-more">展开后加载</div>
            </div>
          </el-collapse-item>
        </el-collapse>
      </section>

      <!-- 右侧列：预览 -->
      <section class="col col-preview">
        <div class="col-title">
          预览（{{ previews.length }}）
          <span v-if="previews.length" class="col-title-tip">可修改目标路径 / 整理方式，按修改后的内容整理</span>
        </div>
        <div class="card-list preview-list">
          <div v-for="p in previews" :key="p.recordId" class="preview-card">
            <div class="preview-top">
              <div class="cover cover-sm">
                <img
                  v-if="p.coverUrl"
                  :src="localImg(p.number || '', 'poster.jpg')"
                  loading="lazy"
                  alt=""
                  @error="imgFallback($event, imgProxy(p.coverUrl))"
                />
                <div v-else class="cover-placeholder">{{ p.number || '?' }}</div>
              </div>
              <div class="info">
                <div class="num">
                  {{ p.number || '未识别' }}
                  <el-tag v-if="p.reorganize" size="small" type="warning">重整</el-tag>
                </div>
                <div class="title" :title="p.title || p.fileName">{{ p.title || p.fileName }}</div>
              </div>
            </div>
            <el-alert v-if="p.error" :title="p.error" type="error" :closable="false" class="preview-error" />
            <template v-else>
              <el-form label-position="top" class="preview-form" size="small">
                <el-form-item label="目标路径">
                  <el-input v-model="p.targetPath" type="textarea" :rows="2" />
                </el-form-item>
                <el-form-item label="整理方式">
                  <el-select v-model="p.mode" style="width: 100%">
                    <el-option
                      v-for="opt in modeOptionsFor(p)"
                      :key="opt"
                      :label="linkModeLabels[opt]"
                      :value="opt"
                    />
                  </el-select>
                </el-form-item>
              </el-form>
            </template>
          </div>
          <el-empty v-if="!previews.length" description="选中媒体后点击「预览选中」" :image-size="60" />
        </div>
      </section>
    </div>

    <template #footer>
      <div class="footer-actions">
        <el-button @click="visible = false">关闭</el-button>
        <el-button :disabled="!selected.size" :loading="previewing" @click="previewSelected">
          预览选中
        </el-button>
        <el-button type="primary" :disabled="!selected.size" :loading="submitting" @click="organizeSelected">
          整理选中
        </el-button>
        <el-button type="success" :loading="submitting" @click="organizeAll">整理全部</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, imgFallback, imgProxy, localImg } from '../api'
import type {
  LinkMode,
  OrganizePreviewItem,
  OrganizeRecordItem,
  Settings,
} from '../api/types'
import { linkModeLabels } from '../utils'
import DirTreeInput from './DirTreeInput.vue'

const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{ created: [] }>()

const PAGE_SIZE = 60

// 左列：整理参数
const targetDir = ref('')
const mode = ref<LinkMode | ''>('')
const namingTemplate = ref('')
const conflictPolicy = ref<'' | 'skip' | 'overwrite' | 'rename'>('')
const globalSettings = ref<Settings | null>(null)

// 中列：媒体列表
const pendingRecords = ref<OrganizeRecordItem[]>([])
const pendingTotal = ref(0)
const pendingPage = ref(0)
const loadingPending = ref(false)
const organizedRecords = ref<OrganizeRecordItem[]>([])
const organizedTotal = ref(0)
const organizedPage = ref(0)
const organizedLoaded = ref(false)
const loadingOrganized = ref(false)
const organizedOpen = ref<string[]>([])
const selected = ref(new Set<number>())

// 右列：预览
const previews = ref<OrganizePreviewItem[]>([])
const previewing = ref(false)
const submitting = ref(false)

const allRecords = computed(() => [...pendingRecords.value, ...organizedRecords.value])
const recordMap = computed(() => new Map(allRecords.value.map((r) => [r.recordId, r])))

const pendingAllSelected = computed(
  () => pendingRecords.value.length > 0 && pendingRecords.value.every((r) => selected.value.has(r.recordId)),
)
const pendingSomeSelected = computed(() => pendingRecords.value.some((r) => selected.value.has(r.recordId)))

async function onOpen() {
  targetDir.value = ''
  mode.value = ''
  namingTemplate.value = ''
  conflictPolicy.value = ''
  selected.value = new Set()
  previews.value = []
  pendingRecords.value = []
  organizedRecords.value = []
  organizedTotal.value = 0
  organizedPage.value = 0
  pendingPage.value = 0
  organizedLoaded.value = false
  organizedOpen.value = []
  loadPending(true)
  // 已整理总数用于折叠标题展示（只取 total）
  try {
    const p = await api.listOrganizeRecords('organized', 1, 1)
    organizedTotal.value = p.total
  } catch {
    /* 打开时获取失败不阻塞 */
  }
  api
    .getSettings()
    .then((s) => (globalSettings.value = s))
    .catch(() => (globalSettings.value = null))
}

async function loadPending(reset: boolean) {
  loadingPending.value = true
  try {
    const page = reset ? 1 : pendingPage.value + 1
    const p = await api.listOrganizeRecords('pending', page, PAGE_SIZE)
    if (reset) {
      pendingRecords.value = p.items
      pendingPage.value = 1
    } else {
      pendingRecords.value.push(...p.items)
      pendingPage.value = page
    }
    pendingTotal.value = p.total
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loadingPending.value = false
  }
}

async function loadOrganized(reset: boolean) {
  loadingOrganized.value = true
  try {
    const page = reset ? 1 : organizedPage.value + 1
    const p = await api.listOrganizeRecords('organized', page, PAGE_SIZE)
    if (reset) {
      organizedRecords.value = p.items
      organizedPage.value = 1
    } else {
      organizedRecords.value.push(...p.items)
      organizedPage.value = page
    }
    organizedTotal.value = p.total
    organizedLoaded.value = true
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loadingOrganized.value = false
  }
}

// 已整理默认折叠，首次展开时才加载
watch(organizedOpen, (v) => {
  if (v.includes('organized') && !organizedLoaded.value) loadOrganized(true)
})

function toggleSelect(recordId: number) {
  const next = new Set(selected.value)
  if (next.has(recordId)) next.delete(recordId)
  else next.add(recordId)
  selected.value = next
}

function toggleAllPending(checked: unknown) {
  const next = new Set(selected.value)
  if (checked) pendingRecords.value.forEach((r) => next.add(r.recordId))
  else pendingRecords.value.forEach((r) => next.delete(r.recordId))
  selected.value = next
}

function shortPath(path?: string | null): string {
  if (!path) return '-'
  return path.length > 60 ? `…${path.slice(-59)}` : path
}

/** 已整理为移动的记录重整时不能选链接模式（文件已不在源位置） */
function modeOptionsFor(p: OrganizePreviewItem): LinkMode[] {
  const all: LinkMode[] = ['hardlink', 'symlink', 'move', 'copy']
  return p.currentLinkMode === 'move' ? all.filter((m) => !['hardlink', 'symlink'].includes(m)) : all
}

function currentParams() {
  return {
    targetDir: targetDir.value.trim() || undefined,
    mode: (mode.value || undefined) as LinkMode | undefined,
    namingTemplate: namingTemplate.value.trim() || undefined,
    conflictPolicy: (conflictPolicy.value || undefined) as 'skip' | 'overwrite' | 'rename' | undefined,
  }
}

async function previewSelected() {
  previewing.value = true
  try {
    const res = await api.previewOrganize({ recordIds: [...selected.value], ...currentParams() })
    previews.value = res.items
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    previewing.value = false
  }
}

/** 计算某条记录生效的整理方式（预览覆盖 > 左列参数 > 全局配置） */
function effectiveMode(p?: OrganizePreviewItem): LinkMode | undefined {
  return p?.mode || (mode.value || undefined) || globalSettings.value?.mode || undefined
}

function validateReorganize(ids: number[], previewMap: Map<number, OrganizePreviewItem>): string | null {
  for (const id of ids) {
    const rec = recordMap.value.get(id)
    if (rec?.organizeStatus === 'organized' && rec.linkMode === 'move') {
      const m = effectiveMode(previewMap.get(id))
      if (m && ['hardlink', 'symlink'].includes(m)) {
        return `${rec.number || rec.fileName}：原整理为移动，文件已不在源位置，重整不能使用链接模式`
      }
    }
  }
  return null
}

async function submitOrganize(body: Parameters<typeof api.triggerOrganize>[0], reorganizeCount: number) {
  submitting.value = true
  try {
    if (reorganizeCount > 0) {
      await ElMessageBox.confirm(
        `${reorganizeCount} 条已整理记录将删除原整理结果后重新整理（链接/复制会删除旧目标文件及同目录 nfo/图片；移动记录以旧位置为源重新移动）`,
        '重整确认',
        { type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消' },
      )
    }
    const job = await api.triggerOrganize(body)
    ElMessage.success(`整理任务 #${job.jobId} 已创建`)
    visible.value = false
    emit('created')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : String(e))
    }
  } finally {
    submitting.value = false
  }
}

async function organizeSelected() {
  const ids = [...selected.value]
  const previewMap = new Map(previews.value.map((p) => [p.recordId, p]))
  const invalid = validateReorganize(ids, previewMap)
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  const items = ids.map((id) => {
    const p = previewMap.get(id)
    // 预览栏有内容则按预览栏整理（可修改的目标路径/方式），否则按左侧规则整理
    if (p && !p.error && p.targetPath) {
      return { recordId: id, targetPath: p.targetPath, mode: p.mode || undefined }
    }
    return { recordId: id }
  })
  const reorganizeCount = ids.filter((id) => recordMap.value.get(id)?.organizeStatus === 'organized').length
  await submitOrganize({ ...currentParams(), items }, reorganizeCount)
}

async function organizeAll() {
  await submitOrganize(currentParams(), 0)
}
</script>

<style scoped>
.organize-layout {
  display: flex;
  gap: 14px;
  height: 66vh;
  min-height: 420px;
}
.col {
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
}
.col-params {
  width: 250px;
  flex-shrink: 0;
  overflow-y: auto;
}
.col-list {
  flex: 1.25;
}
.col-preview {
  flex: 1;
  border-left: 1px solid var(--glass-border, rgba(120, 135, 180, 0.25));
  padding-left: 14px;
}
.col-title {
  font-weight: 600;
  margin-bottom: 10px;
  flex-shrink: 0;
}
.col-title-tip {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
  margin-left: 6px;
}
.param-form :deep(.el-form-item) {
  margin-bottom: 14px;
}
.list-group {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.group-head {
  flex-shrink: 0;
  padding-bottom: 6px;
}
.group-head-text {
  font-weight: 600;
}
.organized-collapse {
  flex-shrink: 0;
  max-height: 45%;
  display: flex;
  flex-direction: column;
}
.organized-collapse :deep(.el-collapse-item__wrap) {
  max-height: 320px;
  overflow-y: auto;
}
.card-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-right: 4px;
}
.media-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--glass-border, rgba(120, 135, 180, 0.25));
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}
.media-card:hover {
  background: rgba(91, 110, 225, 0.06);
}
.media-card:has(.el-checkbox__input.is-checked) {
  border-color: var(--el-color-primary);
  background: rgba(91, 110, 225, 0.1);
}
.cover {
  width: 44px;
  height: 66px;
  flex-shrink: 0;
  border-radius: 4px;
  overflow: hidden;
  background: rgba(120, 135, 180, 0.15);
}
.cover-sm {
  width: 40px;
  height: 60px;
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
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
.info {
  flex: 1;
  min-width: 0;
}
.num {
  font-weight: 600;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.title {
  font-size: 12px;
  color: var(--el-text-color-regular);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.path {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  display: flex;
  align-items: center;
  gap: 6px;
}
.load-more {
  text-align: center;
  padding: 2px 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.preview-list {
  flex: 1;
  overflow-y: auto;
}
.preview-card {
  border: 1px solid var(--glass-border, rgba(120, 135, 180, 0.25));
  border-radius: 8px;
  padding: 10px;
}
.preview-top {
  display: flex;
  gap: 10px;
  margin-bottom: 8px;
}
.preview-form :deep(.el-form-item) {
  margin-bottom: 8px;
}
.preview-form :deep(.el-form-item__label) {
  font-size: 12px;
  padding-bottom: 2px;
}
.preview-error {
  margin-top: 4px;
}
.footer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>
