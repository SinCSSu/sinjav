<template>
  <el-drawer
    :model-value="modelValue"
    size="720px"
    :title="detail?.name ?? 'Emby 条目'"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <template #default>
      <el-empty v-if="error" :description="error" />
      <el-skeleton v-else-if="!detail" :rows="8" animated />
      <template v-else>
        <!-- 头部：封面 + 标题区 -->
        <div class="hero">
          <div class="cover-wrap">
            <img v-if="detail.hasImage" :src="embyPosterUrl(detail.id)" alt="" />
            <div v-else class="cover-placeholder">{{ detail.name }}</div>
          </div>
          <div class="hero-info">
            <div class="hero-top">
              <span class="mono year">{{ detail.productionYear ?? '' }}</span>
              <el-tag v-if="detail.officialRating" size="small" type="info">{{ detail.officialRating }}</el-tag>
              <el-tag v-if="detail.communityRating" size="small" type="warning">
                ★ {{ detail.communityRating.toFixed(1) }}
              </el-tag>
            </div>
            <h3 class="title">{{ detail.name || '—' }}</h3>
            <div v-if="detail.originalTitle && detail.originalTitle !== detail.name" class="orig-title">
              原名：{{ detail.originalTitle }}
            </div>
            <div class="chip-row">
              <span v-if="detail.premiereDate" class="chip">{{ detail.premiereDate.slice(0, 10) }}</span>
              <span v-if="detail.runtimeMin" class="chip">{{ detail.runtimeMin }} 分钟</span>
              <span v-for="s in detail.studios" :key="s" class="chip">{{ s }}</span>
            </div>
            <div v-if="detail.genres.length" class="tag-row">
              <el-tag v-for="g in detail.genres" :key="g" size="small" type="primary" effect="plain">{{ g }}</el-tag>
            </div>
            <div v-if="detail.path" class="path" :title="detail.path">{{ detail.path }}</div>
          </div>
        </div>

        <!-- 简介与标签（只读态） -->
        <template v-if="!editing">
          <div v-if="detail.overview" class="plot-card">
            <div class="row-label">简介</div>
            <p class="plot-text">{{ detail.overview }}</p>
          </div>
          <div v-if="detail.tags.length" class="genre-row">
            <span class="row-label">标签</span>
            <el-tag v-for="t in detail.tags" :key="t" size="small" type="info" effect="plain">{{ t }}</el-tag>
          </div>
          <div v-if="Object.keys(detail.providerIds).length" class="provider-row">
            <span v-for="(v, k) in detail.providerIds" :key="k" class="mono provider-chip">{{ k }}: {{ v }}</span>
          </div>
        </template>

        <!-- 编辑态 -->
        <template v-else>
          <el-form label-width="90px" label-position="left" class="edit-form">
            <el-form-item label="标题">
              <el-input v-model="form.name" />
            </el-form-item>
            <el-form-item label="原标题">
              <el-input v-model="form.originalTitle" />
            </el-form-item>
            <el-form-item label="简介">
              <el-input v-model="form.overview" type="textarea" :rows="5" />
            </el-form-item>
            <el-form-item label="类别">
              <el-input v-model="genresText" placeholder="逗号分隔，如 剧情, 爱情" />
            </el-form-item>
            <el-form-item label="标签">
              <el-input v-model="tagsText" placeholder="逗号分隔" />
            </el-form-item>
            <el-form-item label="工作室">
              <el-input v-model="studiosText" placeholder="逗号分隔" />
            </el-form-item>
            <el-form-item label="发行日期">
              <el-input v-model="form.premiereDate" placeholder="如 2024-05-01" style="max-width: 200px" />
            </el-form-item>
            <el-form-item label="年份">
              <el-input-number v-model="form.productionYear" :min="1900" :max="2100" />
            </el-form-item>
            <el-form-item label="分级">
              <el-input v-model="form.officialRating" style="max-width: 200px" />
            </el-form-item>
            <el-form-item label="评分">
              <el-input-number v-model="form.communityRating" :min="0" :max="10" :step="0.1" :precision="1" />
            </el-form-item>
          </el-form>
          <div class="edit-actions">
            <el-button type="primary" :loading="saving" @click="save">保存</el-button>
            <el-button @click="cancelEdit">取消</el-button>
          </div>
        </template>

        <!-- 只读态操作按钮 -->
        <div v-if="!editing && !preview" class="actions">
          <el-button @click="startEdit">编辑元数据</el-button>
          <el-button type="primary" :loading="refreshing" @click="rescrape">重新刮削</el-button>
        </div>

        <!-- 刮削结果对比（确认后才回写 Emby） -->
        <div v-if="preview && !editing" class="diff-panel">
          <div class="diff-header">
            <span>
              识别番号 <b class="mono">{{ preview.matchedNumber }}</b>
              （来源 {{ preview.source }}）
            </span>
            <el-button size="small" text @click="preview = null">关闭</el-button>
          </div>
          <div v-for="row in diffRows" :key="row.key" class="diff-row">
            <el-checkbox v-model="row.checked" :disabled="!row.changed" class="diff-check" />
            <span class="diff-label">{{ row.label }}</span>
            <div class="diff-values">
              <div class="diff-old">{{ row.current }}</div>
              <div v-if="row.changed" class="diff-arrow">→</div>
              <div v-if="row.changed" class="diff-new">{{ row.proposed }}</div>
              <div v-else class="diff-same">（无变化）</div>
            </div>
          </div>
          <div v-if="!hasChanges" class="diff-none">刮削结果与当前元数据一致，无需应用</div>
          <div class="diff-actions">
            <el-button @click="preview = null">取消</el-button>
            <el-button type="primary" :loading="applying" :disabled="!hasChanges" @click="applyPreview">
              应用勾选字段到 Emby
            </el-button>
          </div>
        </div>
      </template>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, embyPosterUrl } from '../api'
import type { EmbyItemDetail, EmbyMetadataUpdate, EmbyRescrapePreview } from '../api/types'

const props = defineProps<{ modelValue: boolean; itemId: string }>()
const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

const detail = ref<EmbyItemDetail | null>(null)
const error = ref('')
const editing = ref(false)
const saving = ref(false)
const refreshing = ref(false)
const applying = ref(false)

/** 刮削预览结果与差异行 */
const preview = ref<EmbyRescrapePreview | null>(null)

interface DiffRow {
  key: keyof EmbyMetadataUpdate
  label: string
  current: string
  proposed: string
  changed: boolean
  checked: boolean
}
const diffRows = ref<DiffRow[]>([])
const hasChanges = ref(false)

const form = reactive<EmbyMetadataUpdate>({})
const genresText = ref('')
const studiosText = ref('')
const tagsText = ref('')

watch(
  () => [props.modelValue, props.itemId] as const,
  ([visible, id]) => {
    if (visible && id) load(id)
    if (!visible) {
      detail.value = null
      error.value = ''
      editing.value = false
      preview.value = null
      diffRows.value = []
    }
  },
)

async function load(id: string) {
  detail.value = null
  error.value = ''
  editing.value = false
  try {
    detail.value = await api.getEmbyItem(id)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

function toList(text: string): string[] {
  return text.split(/[,，]/).map((s) => s.trim()).filter(Boolean)
}

function startEdit() {
  if (!detail.value) return
  Object.assign(form, {
    name: detail.value.name,
    originalTitle: detail.value.originalTitle ?? '',
    overview: detail.value.overview ?? '',
    premiereDate: detail.value.premiereDate?.slice(0, 10) ?? '',
    productionYear: detail.value.productionYear ?? undefined,
    officialRating: detail.value.officialRating ?? '',
    communityRating: detail.value.communityRating ?? undefined,
  })
  genresText.value = detail.value.genres.join(', ')
  studiosText.value = detail.value.studios.join(', ')
  tagsText.value = detail.value.tags.join(', ')
  editing.value = true
}

function cancelEdit() {
  editing.value = false
}

async function save() {
  saving.value = true
  try {
    detail.value = await api.updateEmbyMetadata(props.itemId, {
      name: form.name,
      originalTitle: form.originalTitle,
      overview: form.overview,
      genres: toList(genresText.value),
      studios: toList(studiosText.value),
      tags: toList(tagsText.value),
      premiereDate: form.premiereDate,
      productionYear: form.productionYear,
      officialRating: form.officialRating,
      communityRating: form.communityRating,
    })
    editing.value = false
    ElMessage.success('元数据已保存到 Emby')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    saving.value = false
  }
}

/** 重新刮削：刮削并展示前后差异，不直接推送 */
async function rescrape() {
  refreshing.value = true
  preview.value = null
  diffRows.value = []
  try {
    await doPreview()
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e)
    if (msg.includes('识别番号')) {
      await promptManualNumber()
    } else {
      ElMessage.error(msg)
    }
  } finally {
    refreshing.value = false
  }
}

async function doPreview(manualNumber?: string) {
  const result = await api.previewEmbyRescrape(props.itemId, manualNumber)
  preview.value = result
  buildDiffRows(result)
  if (!hasChanges.value) ElMessage.info('刮削结果与当前元数据一致')
}

/** 自动识别失败时，弹出对话框手动输入番号重试 */
async function promptManualNumber() {
  let value: string
  try {
    const { value: v } = await ElMessageBox.prompt(
      '未能从文件名、原标题或标题识别番号，请手动输入后重试（如 ABC-123、FC2-PPV-1234567）',
      '手动输入番号',
      {
        confirmButtonText: '刮削',
        cancelButtonText: '取消',
        inputPattern: /\S+/,
        inputErrorMessage: '番号不能为空',
      },
    )
    value = v
  } catch {
    return
  }
  if (!value?.trim()) return
  try {
    await doPreview(value.trim())
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  }
}

function fmtList(v?: string[] | null): string {
  return (v ?? []).join('、')
}

function buildDiffRows(result: EmbyRescrapePreview) {
  const cur = result.current
  const prop = result.proposed
  const empty = '（空）'
  const mk = (key: keyof EmbyMetadataUpdate, label: string, current: string, proposed: string): DiffRow => {
    const changed = current !== proposed
    return { key, label, current: current || empty, proposed: proposed || empty, changed, checked: changed }
  }
  diffRows.value = [
    mk('name', '标题', cur.name ?? '', prop.name ?? ''),
    mk('originalTitle', '原标题', cur.originalTitle ?? '', prop.originalTitle ?? ''),
    mk('overview', '简介', cur.overview ?? '', prop.overview ?? ''),
    mk('genres', '类别', fmtList(cur.genres), fmtList(prop.genres)),
    mk('studios', '工作室', fmtList(cur.studios), fmtList(prop.studios)),
    mk('tags', '标签', fmtList(cur.tags), fmtList(prop.tags)),
    mk('premiereDate', '发行日期', cur.premiereDate?.slice(0, 10) ?? '', prop.premiereDate ?? ''),
    mk('productionYear', '年份', cur.productionYear?.toString() ?? '', prop.productionYear?.toString() ?? ''),
  ]
  hasChanges.value = diffRows.value.some((r) => r.changed)
}

/** 把勾选的字段回写到 Emby */
async function applyPreview() {
  const prop = preview.value?.proposed
  if (!prop) return
  const payload: EmbyMetadataUpdate = {}
  for (const row of diffRows.value) {
    if (!row.checked || !row.changed) continue
    if (row.key === 'name') payload.name = prop.name
    else if (row.key === 'originalTitle') payload.originalTitle = prop.originalTitle
    else if (row.key === 'overview') payload.overview = prop.overview
    else if (row.key === 'genres') payload.genres = prop.genres
    else if (row.key === 'studios') payload.studios = prop.studios
    else if (row.key === 'tags') payload.tags = prop.tags
    else if (row.key === 'premiereDate') payload.premiereDate = prop.premiereDate
    else if (row.key === 'productionYear') payload.productionYear = prop.productionYear
  }
  applying.value = true
  try {
    detail.value = await api.updateEmbyMetadata(props.itemId, payload)
    preview.value = null
    diffRows.value = []
    ElMessage.success('已回写 Emby')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    applying.value = false
  }
}
</script>

<style scoped>
/* ---------- 头部 ---------- */
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
  padding: 8px;
  text-align: center;
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
  gap: 8px;
}
.year {
  font-size: 14px;
  font-weight: 700;
  color: var(--el-color-primary);
}
.mono {
  font-family: Consolas, monospace;
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
.path {
  margin-top: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---------- 简介 / 标签 / 外部 ID ---------- */
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
.provider-row {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.provider-chip {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: var(--el-border-radius-round);
  background: var(--glass-bg-soft);
  color: var(--el-text-color-secondary);
  border: 1px solid var(--glass-border-soft);
}

/* ---------- 编辑 / 操作 ---------- */
.edit-form {
  margin-top: 18px;
}
.edit-actions {
  margin-top: 8px;
  display: flex;
  justify-content: flex-end;
}
.actions {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
}

/* ---------- 刮削差异面板 ---------- */
.diff-panel {
  margin-top: 20px;
  padding: 12px 14px;
  border-radius: var(--glass-radius-sm);
  background: var(--glass-bg-soft);
  border: 1px solid var(--glass-border-soft);
}
.diff-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  margin-bottom: 10px;
}
.diff-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 6px 0;
  border-top: 1px solid var(--glass-border-soft);
}
.diff-check {
  margin-top: 2px;
}
.diff-label {
  width: 60px;
  flex-shrink: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 22px;
}
.diff-values {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  line-height: 1.6;
}
.diff-old,
.diff-new,
.diff-same {
  white-space: pre-line;
  word-break: break-all;
  max-height: 96px;
  overflow: auto;
  padding: 2px 6px;
  border-radius: 4px;
}
.diff-old {
  flex: 1;
  color: var(--el-text-color-secondary);
  text-decoration: line-through;
  text-decoration-color: var(--el-text-color-placeholder);
}
.diff-arrow {
  color: var(--el-text-color-placeholder);
  flex-shrink: 0;
}
.diff-new {
  flex: 1.2;
  color: var(--el-color-primary);
  background: var(--glass-accent);
}
.diff-same {
  color: var(--el-text-color-placeholder);
}
.diff-none {
  margin-top: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
.diff-actions {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
