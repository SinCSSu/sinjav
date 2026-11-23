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
            <img
              v-if="detail.hasImage"
              :src="coverBust ? `${embyPosterUrl(detail.id)}?t=${coverBust}` : embyPosterUrl(detail.id)"
              alt=""
            />
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

        <!-- 重新刮削：多源对比逐字段选择来源（确认后回写 Emby） -->
        <div v-if="preview && !editing" class="diff-panel">
          <div class="diff-header">
            <span>
              番号
              <el-input
                v-model="numberInput"
                size="small"
                class="num-input mono"
                placeholder="番号"
                @keyup.enter="rescrapeByNumber"
              />
              <el-button size="small" :loading="refreshing" @click="rescrapeByNumber">重新刮削</el-button>
            </span>
            <el-button size="small" text @click="preview = null">关闭</el-button>
          </div>
          <div class="cmp-fields">
            <div v-for="f in EMBY_FIELDS" :key="f.key" class="cmp-row">
              <span class="k">{{ f.label }}</span>
              <el-select v-model="sel[f.key]" size="small" class="cmp-select" @change="onSelChange(f.key)">
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
                :placeholder="sel[f.key] === MANUAL ? '输入自定义值' : '留空不回写'"
                class="cmp-value cmp-manual"
              />
            </div>
          </div>
          <div class="diff-actions">
            <el-button :loading="pushingCover" :disabled="!preview" @click="pushCover">推送封面</el-button>
            <el-button :loading="pushingBackdrops" :disabled="!preview" @click="pushBackdrops">推送剧照</el-button>
            <el-button @click="preview = null">取消</el-button>
            <el-button :loading="translating" :disabled="!preview" @click="translateFields">翻译</el-button>
            <el-button type="primary" :loading="applying" :disabled="allEmpty" @click="applyPreview">
              保存
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

const MANUAL = '__manual__'
/** 重新刮削可选来源的字段（与后端多源对比同一套字段键，图片字段不回写 Emby 元数据故不含） */
const EMBY_FIELDS = [
  { key: 'title', label: '标题', multiline: false },
  { key: 'plot', label: '简介', multiline: true },
  { key: 'releaseDate', label: '发行日期', multiline: false },
  { key: 'director', label: '导演', multiline: false },
  { key: 'maker', label: '厂商', multiline: false },
  { key: 'label', label: '发行商', multiline: false },
  { key: 'series', label: '系列', multiline: false },
  { key: 'actresses', label: '女优', multiline: false },
  { key: 'genres', label: '类别', multiline: false },
] as const

const detail = ref<EmbyItemDetail | null>(null)
const error = ref('')
const editing = ref(false)
const saving = ref(false)
const refreshing = ref(false)
const applying = ref(false)
const translating = ref(false)
const pushingCover = ref(false)
const pushingBackdrops = ref(false)
/** 封面推送成功后的 cache-bust 时间戳（代理图有 86400 缓存头，需换 URL 强制刷新） */
const coverBust = ref(0)

/** 刮削预览结果 */
const preview = ref<EmbyRescrapePreview | null>(null)

// 逐字段来源选择：key → 来源 id / ''(留空=不回写) / MANUAL(手动输入)
const sel = ref<Record<string, string>>({})
/** key → 可编辑的最终值：切换来源时自动填入该来源原文，可直接编辑或点「翻译」回填译文后再保存 */
const vals = ref<Record<string, string>>({})
/** 对比面板内可编辑的番号（识别错了可改后重新刮削） */
const numberInput = ref('')

const allEmpty = ref(false)

function syncAllEmpty() {
  allEmpty.value = EMBY_FIELDS.every(f => (sel.value[f.key] ?? '') === '')
}

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
      sel.value = {}
      vals.value = {}
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

/** 重新刮削：并发抓全源并展示逐字段来源选择，确认后回写 Emby */
async function rescrape() {
  refreshing.value = true
  preview.value = null
  sel.value = {}
  vals.value = {}
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
  numberInput.value = result.matchedNumber
  buildSel(result)
}

/** 对比面板内编辑番号后重新刮削 */
async function rescrapeByNumber() {
  const n = numberInput.value.trim()
  if (!n) return
  refreshing.value = true
  try {
    await doPreview(n)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    refreshing.value = false
  }
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

/** 打开对比面板：默认按合并规则选来源（source-order 靠前的源优先），各字段预填默认来源原文；无值字段留空 */
function buildSel(result: EmbyRescrapePreview) {
  sel.value = Object.fromEntries(EMBY_FIELDS.map(f => [f.key, result.defaults[f.key] ?? '']))
  vals.value = Object.fromEntries(EMBY_FIELDS.map(f => [f.key, result.fields[f.key]?.[result.defaults[f.key] ?? ''] ?? '']))
  syncAllEmpty()
}

/** 切换字段来源时重填该字段的值（选中来源原文 / 留空清空），并同步「全部留空」状态 */
function onSelChange(key: string) {
  const src = sel.value[key]
  vals.value[key] = src && src !== MANUAL ? (fieldValue(key, src) ?? '') : ''
  syncAllEmpty()
}

/** 某字段有值的来源列表（下拉选项） */
function fieldSources(key: string): string[] {
  const values = preview.value?.fields[key]
  if (!preview.value || !values) return []
  return (preview.value.sources ?? []).filter(s => values[s] != null)
}

function fieldValue(key: string, source: string | undefined): string | null {
  if (!preview.value || !source || source === MANUAL) return null
  return preview.value.fields[key]?.[source] ?? null
}

/** 翻译：对输入框中的标题/简介/类别做翻译或繁→简转换，译文回填输入框（不回写 Emby） */
async function translateFields() {
  if (!preview.value) return
  translating.value = true
  try {
    const res = await api.scrapeTranslate({
      title: (vals.value.title ?? '').trim() || undefined,
      plot: (vals.value.plot ?? '').trim() || undefined,
      genres: (vals.value.genres ?? '').trim() || undefined,
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

/** 取预览中某图片字段的默认来源值（coverUrl 为单条 URL；previewUrls 为换行分隔的多条 URL） */
function defaultImageValue(key: 'coverUrl' | 'previewUrls'): string {
  const p = preview.value
  if (!p) return ''
  const def = p.defaults[key] ?? ''
  return p.fields[key]?.[def] ?? ''
}

/** 推送封面：用预览的封面 URL（后端下载并裁竖版后上传 Emby Primary；无 URL 时后端回退本地缓存） */
async function pushCover() {
  const p = preview.value
  if (!p) return
  pushingCover.value = true
  try {
    await api.pushEmbyCover(props.itemId, {
      number: p.matchedNumber,
      coverUrl: defaultImageValue('coverUrl') || undefined,
    })
    detail.value = await api.getEmbyItem(props.itemId)
    coverBust.value = Date.now()
    ElMessage.success('封面已推送到 Emby')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    pushingCover.value = false
  }
}

/** 推送剧照：用预览的剧照 URL 列表（后端清空已有 Backdrop 后逐张上传；无 URL 时回退本地缓存） */
async function pushBackdrops() {
  const p = preview.value
  if (!p) return
  pushingBackdrops.value = true
  try {
    const urls = defaultImageValue('previewUrls')
      .split(/\r?\n/)
      .map((s) => s.trim())
      .filter(Boolean)
    const r = await api.pushEmbyBackdrops(props.itemId, {
      number: p.matchedNumber,
      previewUrls: urls.length ? urls : undefined,
    })
    ElMessage.success(`已推送 ${r.pushed} 张剧照到 Emby`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    pushingBackdrops.value = false
  }
}

/** 保存：把逐字段选择与输入框当前内容回写 Emby（留空字段不回写，不自动翻译） */
async function applyPreview() {
  const p = preview.value
  if (!p) return
  const fields: Record<string, { source: string | null; value?: string | null }> = {}
  for (const f of EMBY_FIELDS) {
    const s = sel.value[f.key] ?? ''
    if (s === '') {
      fields[f.key] = { source: null }
    } else {
      // 值取输入框当前内容（原文或翻译回填的译文，保存即所见）
      fields[f.key] = { source: s === MANUAL ? 'manual' : s, value: vals.value[f.key] }
    }
  }
  applying.value = true
  try {
    await api.applyEmbyRescrape(props.itemId, { number: p.matchedNumber, fields })
    detail.value = await api.getEmbyItem(props.itemId)
    preview.value = null
    sel.value = {}
    vals.value = {}
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
  background: var(--cover-placeholder-bg);
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
  font-size: 12px;
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

/* ---------- 重新刮削：多源对比面板 ---------- */
.diff-panel {
  margin-top: 20px;
  padding: 12px 14px;
  border-radius: var(--glass-radius-sm);
  background: var(--glass-bg-soft);
  border: 1px solid var(--glass-border-soft);
}
.diff-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  font-size: 13px;
  margin-bottom: 10px;
}

.num-input {
  width: 170px;
  margin: 0 6px;
  font-family: Consolas, monospace;
}
.diff-header .el-button + .el-button {
  margin-left: 0;
}
.cmp-fields {
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
  width: 56px;
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
.cmp-fields :deep(.el-textarea__inner) {
  resize: none;
}
.diff-actions {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
