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
            <el-select v-model="mode" placeholder="请选择整理方式" style="width: 100%">
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
          <el-form-item label="只整理视频">
            <el-switch v-model="videoOnly" />
            <div class="param-hint">开启后仅转移视频文件，不生成 nfo/图片（适配 Emby 已有该媒体的多分辨率入库）</div>
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
                <div class="path" :title="r.sourcePath">源：{{ r.sourcePath }}</div>
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

        <div class="lower-groups">
        <el-collapse v-model="failedOpen" class="organized-collapse">
          <el-collapse-item name="failed">
            <template #title>
              <span class="group-head-text">失败（{{ failedTotal }}）—— 上次整理失败，修复原因（如重新刮削）后可勾选重试</span>
            </template>
            <div v-loading="loadingFailed" class="card-list card-list-organized">
              <label v-for="r in failedRecords" :key="r.recordId" class="media-card">
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
                  <div class="path">
                    <el-tag size="small" type="danger">失败</el-tag>
                    {{ r.organizeError || '未知原因' }}
                  </div>
                </div>
              </label>
              <div v-if="failedLoaded && failedRecords.length < failedTotal" class="load-more">
                <el-button link type="primary" :loading="loadingFailed" @click="loadFailed(false)">
                  加载更多
                </el-button>
              </div>
              <el-empty v-if="failedLoaded && !failedRecords.length" description="没有整理失败的文件" :image-size="60" />
              <div v-if="!failedLoaded" class="load-more">展开后加载</div>
            </div>
          </el-collapse-item>
        </el-collapse>

        <el-collapse v-model="existsOpen" class="organized-collapse">
          <el-collapse-item name="exists">
            <template #title>
              <span class="group-head-text">已存在（{{ existsTotal }}）—— Emby 已有该番号、本地尚未整理</span>
            </template>
            <div v-loading="loadingExists" class="card-list card-list-organized">
              <label v-for="r in existsRecords" :key="r.recordId" class="media-card">
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
                  <div class="path" :title="r.fileName">
                    <el-tag size="small" type="info" effect="dark">Emby已有</el-tag>
                    {{ r.fileName }}
                  </div>
                  <div class="path" :title="r.sourcePath">源：{{ r.sourcePath }}</div>
                </div>
              </label>
              <div v-if="existsLoaded && existsRecords.length < existsTotal" class="load-more">
                <el-button link type="primary" :loading="loadingExists" @click="loadExists(false)">
                  加载更多
                </el-button>
              </div>
              <el-empty
                v-if="existsLoaded && !existsRecords.length"
                description="没有已存在于 Emby 的待整理文件（先在影片库点击「同步 Emby」）"
                :image-size="60"
              />
              <div v-if="!existsLoaded" class="load-more">展开后加载</div>
            </div>
          </el-collapse-item>
        </el-collapse>

        <el-collapse v-model="organizingOpen" class="organized-collapse">
          <el-collapse-item name="organizing">
            <template #title>
              <span class="group-head-text">整理中（{{ organizingTotal }}）</span>
            </template>
            <div v-loading="loadingOrganizing" class="card-list card-list-organized">
              <div v-for="r in organizingRecords" :key="r.recordId" class="media-card media-card-disabled">
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
                  <div class="path">
                    <el-tag size="small" type="warning">整理中</el-tag>
                  </div>
                </div>
              </div>
              <el-empty v-if="organizingLoaded && !organizingRecords.length" description="没有正在整理的文件" :image-size="60" />
              <div v-if="!organizingLoaded" class="load-more">展开后加载</div>
            </div>
          </el-collapse-item>
        </el-collapse>

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
        </div>
      </section>

      <!-- 右侧列：预览 -->
      <section class="col col-preview">
        <div class="col-title">预览（{{ previews.length }}）</div>
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
          <el-empty v-if="!previews.length" description="暂无预览" :image-size="60" />
        </div>
      </section>
    </div>

    <template #footer>
      <div class="footer-actions">
        <el-button icon="Close" @click="visible = false">关闭</el-button>
        <el-button icon="View" :disabled="!selected.size" :loading="previewing" @click="previewSelected">
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
} from '../api/types'
import { linkModeLabels } from '../utils'
import DirTreeInput from './DirTreeInput.vue'

const visible = defineModel<boolean>({ default: false })
/** 卡片快捷整理入口：打开弹窗时按番号预选待整理的文件记录 */
const props = defineProps<{ presetNumbers?: string[] }>()
const emit = defineEmits<{ created: [] }>()

const PAGE_SIZE = 60

// 左列：整理参数
const targetDir = ref('')
const mode = ref<LinkMode | ''>('')
const namingTemplate = ref('')
const conflictPolicy = ref<'' | 'skip' | 'overwrite' | 'rename'>('')
const videoOnly = ref(false)

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
const organizingRecords = ref<OrganizeRecordItem[]>([])
const organizingTotal = ref(0)
const organizingLoaded = ref(false)
const loadingOrganizing = ref(false)
const organizingOpen = ref<string[]>([])
// 已存在分组：Emby 已有该番号、本地尚未整理的文件记录
const existsRecords = ref<OrganizeRecordItem[]>([])
const existsTotal = ref(0)
const existsPage = ref(0)
const existsLoaded = ref(false)
const loadingExists = ref(false)
const existsOpen = ref<string[]>([])
// 失败分组：上次整理失败（未刮削/文件名超长等），可勾选重试
const failedRecords = ref<OrganizeRecordItem[]>([])
const failedTotal = ref(0)
const failedPage = ref(0)
const failedLoaded = ref(false)
const loadingFailed = ref(false)
const failedOpen = ref<string[]>([])
const selected = ref(new Set<number>())

// 右列：预览
const previews = ref<OrganizePreviewItem[]>([])
const previewing = ref(false)
const submitting = ref(false)

const allRecords = computed(() => [
  ...pendingRecords.value,
  ...existsRecords.value,
  ...failedRecords.value,
  ...organizedRecords.value,
])
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
  videoOnly.value = false
  selected.value = new Set()
  previews.value = []
  pendingRecords.value = []
  organizedRecords.value = []
  organizingRecords.value = []
  existsRecords.value = []
  failedRecords.value = []
  organizedTotal.value = 0
  organizingTotal.value = 0
  existsTotal.value = 0
  failedTotal.value = 0
  organizingLoaded.value = false
  existsLoaded.value = false
  failedLoaded.value = false
  organizedPage.value = 0
  existsPage.value = 0
  failedPage.value = 0
  pendingPage.value = 0
  organizedLoaded.value = false
  organizedOpen.value = []
  existsOpen.value = []
  failedOpen.value = []
  await loadPending(true)
  // 快捷入口：按番号预选该影片的待整理文件记录
  const preset = props.presetNumbers ?? []
  if (preset.length) {
    selected.value = new Set(
      pendingRecords.value
        .filter((r) => r.number && preset.includes(r.number))
        .map((r) => r.recordId),
    )
  }
  // 已整理/已存在/失败总数用于折叠标题展示（只取 total）
  try {
    const [p, e, f] = await Promise.all([
      api.listOrganizeRecords('organized', 1, 1),
      api.listOrganizeRecords('exists', 1, 1),
      api.listOrganizeRecords('failed', 1, 1),
    ])
    organizedTotal.value = p.total
    existsTotal.value = e.total
    failedTotal.value = f.total
  } catch {
    /* 打开时获取失败不阻塞 */
  }
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

async function loadExists(reset: boolean) {
  loadingExists.value = true
  try {
    const page = reset ? 1 : existsPage.value + 1
    const p = await api.listOrganizeRecords('exists', page, PAGE_SIZE)
    if (reset) {
      existsRecords.value = p.items
      existsPage.value = 1
    } else {
      existsRecords.value.push(...p.items)
      existsPage.value = page
    }
    existsTotal.value = p.total
    existsLoaded.value = true
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loadingExists.value = false
  }
}

async function loadOrganizing(reset: boolean) {
  loadingOrganizing.value = true
  try {
    const p = await api.listOrganizeRecords('organizing', 1, PAGE_SIZE)
    if (reset) organizingRecords.value = p.items
    organizingTotal.value = p.total
    organizingLoaded.value = true
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loadingOrganizing.value = false
  }
}

async function loadFailed(reset: boolean) {
  loadingFailed.value = true
  try {
    const page = reset ? 1 : failedPage.value + 1
    const p = await api.listOrganizeRecords('failed', page, PAGE_SIZE)
    if (reset) {
      failedRecords.value = p.items
      failedPage.value = 1
    } else {
      failedRecords.value.push(...p.items)
      failedPage.value = page
    }
    failedTotal.value = p.total
    failedLoaded.value = true
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loadingFailed.value = false
  }
}

// 已存在分组默认折叠，首次展开时才加载
watch(existsOpen, (v) => {
  if (v.includes('exists') && !existsLoaded.value) loadExists(true)
})

// 失败分组默认折叠，首次展开时才加载
watch(failedOpen, (v) => {
  if (v.includes('failed') && !failedLoaded.value) loadFailed(true)
})

// 整理中默认折叠，首次展开时才加载
watch(organizingOpen, (v) => {
  if (v.includes('organizing') && !organizingLoaded.value) loadOrganizing(true)
})

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
    targetDir: targetDir.value.trim(),
    mode: mode.value as LinkMode,
    namingTemplate: namingTemplate.value.trim() || undefined,
    conflictPolicy: (conflictPolicy.value || undefined) as 'skip' | 'overwrite' | 'rename' | undefined,
    videoOnly: videoOnly.value || undefined,
  }
}

/** 目标目录与整理方式每次整理必须指定（不再有全局配置兜底） */
function validateParams(): boolean {
  if (!targetDir.value.trim()) {
    ElMessage.warning('请先选择目标目录')
    return false
  }
  if (!mode.value) {
    ElMessage.warning('请先选择整理方式')
    return false
  }
  return true
}

async function previewSelected() {
  if (!validateParams()) return
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

/** 计算某条记录生效的整理方式（预览覆盖 > 左列参数） */
function effectiveMode(p?: OrganizePreviewItem): LinkMode | undefined {
  return p?.mode || (mode.value || undefined) || undefined
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

async function submitOrganize(
  body: Parameters<typeof api.triggerOrganize>[0],
  reorganizeCount: number,
  /** 整理选中：保持弹窗打开并刷新列表，方便连续整理下一条 */
  keepOpen = false,
) {
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
    if (keepOpen) {
      ElMessage.success(`整理任务 #${job.jobId} 已创建，可继续选择下一条`)
      // 已整理/待整理状态由后端异步更新，稍等后刷新列表并清空选择与预览
      selected.value = new Set()
      previews.value = []
      setTimeout(() => {
        loadPending(true)
        loadOrganizing(true)
        loadOrganized(true)
        loadExists(true)
        if (failedLoaded.value) loadFailed(true)
        organizingOpen.value = ['organizing']
      }, 800)
    } else {
      ElMessage.success(`整理任务 #${job.jobId} 已创建`)
      visible.value = false
      emit('created')
    }
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : String(e))
    }
  } finally {
    submitting.value = false
  }
}

async function organizeSelected() {
  if (!validateParams()) return
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
  await submitOrganize({ ...currentParams(), items }, reorganizeCount, true)
}

async function organizeAll() {
  if (!validateParams()) return
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
  border-left: 1px solid var(--glass-border);
  padding-left: 14px;
}
.col-title {
  font-weight: 600;
  margin-bottom: 10px;
  flex-shrink: 0;
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
.param-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
  margin-top: 4px;
}
/* 待整理列表下方的折叠分组（已存在/整理中/已整理）共用一个有界滚动容器，
   整体限高并内部滚动，避免内容溢出叠压到待整理列表上 */
.lower-groups {
  flex-shrink: 0;
  max-height: 50%;
  overflow-y: auto;
}
.organized-collapse {
  flex-shrink: 0;
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
  border: 1px solid var(--glass-border);
  border-radius: var(--el-border-radius-small);
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}
.media-card:hover {
  background: var(--el-color-primary-light-9);
}
.media-card:has(.el-checkbox__input.is-checked) {
  border-color: var(--el-color-primary);
  background: var(--glass-accent);
}
.cover {
  width: 44px;
  height: 66px;
  flex-shrink: 0;
  border-radius: var(--el-border-radius-small);
  overflow: hidden;
  background: var(--cover-placeholder-bg);
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
  font-size: 12px;
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
  border: 1px solid var(--glass-border);
  border-radius: var(--el-border-radius-small);
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
