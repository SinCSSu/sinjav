<template>
  <el-dialog
    :model-value="visible"
    title="演员详情"
    width="min(800px, 94vw)"
    :close-on-click-modal="false"
    @update:model-value="onVisibleChange"
  >
    <!-- 加载失败：只显示错误提示，不渲染空壳布局 -->
    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" />

    <el-skeleton v-else-if="!detail" :rows="6" animated />

    <el-row v-else :gutter="20">
      <!-- 左：大图（300 x 400，无头像显示灰色占位） -->
      <el-col :span="10" :xs="24">
        <div class="photo-wrap">
          <el-image v-if="photoSrc" :key="photoSrc" :src="photoSrc" fit="cover" class="photo-img">
            <template #placeholder>
              <div class="photo-fallback">加载中…</div>
            </template>
            <template #error>
              <div class="photo-fallback">暂无头像</div>
            </template>
          </el-image>
          <div v-else class="photo-fallback">暂无头像</div>
        </div>
      </el-col>

      <!-- 右：演员信息 + 别名编辑 + 操作按钮 + 所属影片 -->
      <el-col :span="14" :xs="24">
        <div class="info-head">
          <el-text size="large" tag="div" class="actor-name">{{ detail.name }}</el-text>
          <div v-if="detail.alias" class="actor-alias">规范名：{{ detail.alias }}</div>
          <div class="hint">共 {{ detail.movieCount }} 部影片</div>
        </div>

        <!-- 别名编辑：主别名（规范名）用 primary 色，其余默认色，均可关闭删除 -->
        <div class="section">
          <div class="section-head">
            <span class="section-label">别名</span>
            <span class="hint">回车添加，× 删除，保存后生效</span>
          </div>
          <div class="alias-tags">
            <el-tag
              v-if="canonicalAlias"
              type="primary"
              closable
              class="alias-tag"
              title="主别名（规范名）：Gfriends 匹配名 + 头像文件名，关闭即清除"
              @close="clearCanonical"
            >
              {{ canonicalAlias }}
            </el-tag>
            <el-tag
              v-for="(tag, i) in aliasTags"
              :key="tag"
              closable
              class="alias-tag"
              @close="removeAlias(i)"
            >
              {{ tag }}
            </el-tag>
            <span v-if="!canonicalAlias && aliasTags.length === 0" class="hint">暂无别名</span>
          </div>
          <div class="alias-actions">
            <el-input
              v-model="aliasInput"
              class="alias-input"
              size="small"
              placeholder="新增别名，回车确认"
              maxlength="60"
              @keyup.enter="addAlias"
            />
            <el-button type="primary" size="small" :loading="saving" :disabled="!dirty" @click="saveAliases">
              保存
            </el-button>
          </div>
        </div>

        <!-- 操作按钮组 -->
        <div class="actions">
          <el-tooltip
            content="按规范名 + 别名匹配 Gfriends 重新下载头像；规范名改过之后重抓可让头像文件名跟随"
            placement="top"
          >
            <el-button size="small" :icon="PictureIcon" :loading="scraping" @click="doScrapePhoto">
              重新抓取头像
            </el-button>
          </el-tooltip>
          <el-tooltip content="把演员头像推送到 Emby Person 主图（后端接口开发中）" placement="top">
            <el-button size="small" :icon="UploadIcon" :loading="pushing" @click="doPushEmby">
              推送到 Emby
            </el-button>
          </el-tooltip>
        </div>

        <!-- 所属影片（封面 100 x 140，点击跳转影片详情） -->
        <div class="section">
          <div class="section-head">
            <span class="section-label">所属影片</span>
            <span class="hint">{{ detail.movieCount }} 部</span>
          </div>
          <el-empty v-if="detail.movies.length === 0" description="暂无关联影片" :image-size="56" />
          <el-scrollbar v-else max-height="290px">
            <div class="movie-grid">
              <div
                v-for="m in detail.movies"
                :key="m.id"
                class="movie-card"
                role="button"
                tabindex="0"
                :title="`${m.number} ${m.title ?? ''}`"
                @click="goMovie(m)"
                @keyup.enter="goMovie(m)"
              >
                <div class="movie-cover">
                  <el-image :src="m.coverPath" fit="cover" class="movie-cover-img" lazy>
                    <template #placeholder>
                      <div class="movie-fallback">加载中…</div>
                    </template>
                    <template #error>
                      <div class="movie-fallback">暂无封面</div>
                    </template>
                  </el-image>
                </div>
                <div class="movie-number">{{ m.number }}</div>
                <div class="movie-title">{{ m.title || '—' }}</div>
              </div>
            </div>
          </el-scrollbar>
        </div>
      </el-col>
    </el-row>

    <template #footer>
      <el-button @click="onVisibleChange(false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Picture as PictureIcon, Upload as UploadIcon } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { ApiError, actorPhotoUrl, api } from '../api'
import type { ActorDetail, MovieBrief } from '../api/types'

const props = defineProps<{
  /** 要展示的演员 ID（null = 未选中，不加载） */
  actorId: number | null
  /** 弹窗显隐（受控；关闭请求通过 close 事件交父组件置 false） */
  visible: boolean
}>()

const emit = defineEmits<{
  /** 请求关闭（点 ×、点遮罩、按 ESC 或点「关闭」） */
  close: []
  /** 详情有变更（别名保存 / 头像重抓成功）：父页面刷新列表 */
  updated: []
}>()

const router = useRouter()

const detail = ref<ActorDetail | null>(null)
const loadError = ref('')
/** 头像缓存击穿：重抓后文件名可能不变，加时间戳强制浏览器重新拉取 */
const photoBust = ref(0)
/** 别名草稿：其他别名（不含规范名） */
const aliasTags = ref<string[]>([])
/** 别名草稿：规范名（null = 未设置，提交空串清空） */
const canonicalAlias = ref<string | null>(null)
const aliasInput = ref('')
const saving = ref(false)
const scraping = ref(false)
const pushing = ref(false)

/** 请求序号：快速切换演员时丢弃过期响应 */
let reqSeq = 0

const photoSrc = computed(() => {
  const path = detail.value?.photoPath
  if (!path) return ''
  const url = actorPhotoUrl(path)
  return photoBust.value ? `${url}?t=${photoBust.value}` : url
})

/** 是否有未保存改动：无改动时禁用保存，避免无意义的 PATCH */
const dirty = computed(() => {
  const d = detail.value
  if (!d) return false
  if ((canonicalAlias.value ?? '') !== (d.alias ?? '')) return true
  if (aliasTags.value.length !== d.aliases.length) return true
  return aliasTags.value.some((t, i) => t !== d.aliases[i])
})

/** 抓取结果 → toast 提示（与后端 ScrapeResult 四态对应） */
const SCRAPE_TIPS: Record<string, { type: 'success' | 'info' | 'warning' | 'error'; text: string }> = {
  SUCCESS: { type: 'success', text: '头像已更新' },
  SKIPPED: { type: 'info', text: '已有头像，未重新抓取' },
  NOT_FOUND: { type: 'warning', text: 'Gfriends 头像库未命中该演员' },
  DOWNLOAD_FAILED: { type: 'error', text: '头像下载失败，请稍后重试' },
}

function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/** 详情与编辑草稿一起落地：草稿必须跟随 detail，否则 dirty 判定会漂移 */
function applyDetail(d: ActorDetail) {
  detail.value = d
  canonicalAlias.value = d.alias
  aliasTags.value = [...d.aliases]
  aliasInput.value = ''
}

async function load(id: number) {
  const seq = ++reqSeq
  loadError.value = ''
  detail.value = null
  try {
    const d = await api.getActorDetail(id)
    if (seq !== reqSeq) return
    applyDetail(d)
  } catch (e) {
    if (seq !== reqSeq) return
    loadError.value = errMsg(e)
  }
}

function onVisibleChange(v: boolean) {
  if (!v) emit('close')
}

/** 打开或切换演员时加载；关闭时清空，避免下次打开闪现上一位演员 */
watch(
  () => [props.visible, props.actorId] as const,
  ([visible, id]) => {
    if (!visible) {
      reqSeq++
      detail.value = null
      loadError.value = ''
      return
    }
    if (id === null) {
      loadError.value = '未选择演员'
      return
    }
    load(id)
  },
  { immediate: true },
)

/* ---------------------------------------------------------------- 别名编辑 */

function addAlias() {
  const name = aliasInput.value.trim()
  if (!name) return
  if (name === canonicalAlias.value || aliasTags.value.includes(name)) {
    ElMessage.warning(`别名「${name}」已存在`)
    return
  }
  aliasTags.value.push(name)
  aliasInput.value = ''
}

function removeAlias(index: number) {
  aliasTags.value.splice(index, 1)
}

function clearCanonical() {
  canonicalAlias.value = null
}

async function saveAliases() {
  const id = props.actorId
  if (id === null || !detail.value) return
  saving.value = true
  try {
    // alias 传空串 = 清空规范名（后端 null 语义是「不修改」）；aliases 为全量替换
    const d = await api.updateActorAliases(id, {
      alias: canonicalAlias.value ?? '',
      aliases: [...aliasTags.value],
    })
    applyDetail(d)
    ElMessage.success('别名已保存')
    emit('updated')
  } catch (e) {
    // 别名被其他演员占用时后端返回 400，ProblemDetail.detail 里是具体冲突名
    ElMessage.error(errMsg(e))
  } finally {
    saving.value = false
  }
}

/* ---------------------------------------------------------------- 操作按钮 */

/**
 * 重新抓取头像（同步接口）：force = true 表示忽略本地已有文件强制重下
 * （按钮语义是「换一张」，且规范名改过后重抓才能让文件名跟随）。
 * SUCCESS 时重拉详情刷新头像，其余三态只提示。
 */
async function doScrapePhoto() {
  const id = props.actorId
  if (id === null) return
  scraping.value = true
  try {
    const result = await api.scrapeActorPhoto(id, true)
    const tip = SCRAPE_TIPS[result] ?? { type: 'info' as const, text: `抓取结果：${result}` }
    ElMessage({ type: tip.type, message: tip.text })
    if (result === 'SUCCESS') {
      photoBust.value = Date.now()
      const d = await api.getActorDetail(id)
      if (props.actorId === id) applyDetail(d)
      emit('updated')
    }
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    scraping.value = false
  }
}

/** 推送到 Emby：后端批次 5 未实现，404 时按「功能开发中」提示 */
async function doPushEmby() {
  const id = props.actorId
  if (id === null) return
  pushing.value = true
  try {
    const r = await api.pushActorToEmby(id)
    ElMessage.success(r?.message || '已推送到 Emby')
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      ElMessage.warning('推送 Emby 功能开发中，敬请期待')
    } else {
      ElMessage.error(errMsg(e))
    }
  } finally {
    pushing.value = false
  }
}

/* ---------------------------------------------------------------- 影片跳转 */

function goMovie(m: MovieBrief) {
  emit('close')
  router.push({ name: 'movies', query: { number: m.number } })
}
</script>

<style scoped>
/* ---------- 左列大图 ---------- */
.photo-wrap {
  width: 100%;
  max-width: 300px;
  aspect-ratio: 3 / 4;
  border-radius: var(--glass-radius-sm);
  overflow: hidden;
  background: var(--cover-placeholder-bg);
  /* 用内阴影代替边框，保证 300 x 400 的实际尺寸不被吃掉 */
  box-shadow: inset 0 0 0 1px var(--glass-border);
}
.photo-img {
  width: 100%;
  height: 100%;
  display: block;
}
.photo-img :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
/* 无头像占位：灰色底 + 居中提示文字（与演员列表页占位一致） */
.photo-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  background: linear-gradient(160deg, rgba(158, 168, 192, 0.22), rgba(140, 152, 182, 0.14));
}

/* ---------- 右列信息 ---------- */
.info-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.actor-name {
  font-size: 18px;
  font-weight: 600;
  line-height: 1.3;
  word-break: break-word;
}
.actor-alias {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}

.section {
  margin-top: 16px;
}
.section-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}
.section-label {
  font-size: 13px;
  font-weight: 600;
}

.alias-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  min-height: 24px;
}
.alias-tag {
  max-width: 100%;
}
.alias-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}
.alias-input {
  max-width: 200px;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}

/* ---------- 影片卡片（100 x 140，hover 与影片库列表页一致） ---------- */
.movie-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  /* 留白：hover 位移与阴影不被滚动容器裁掉 */
  padding: 2px;
}
.movie-card {
  width: 100px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 4px;
  border-radius: var(--glass-radius-sm);
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}
.movie-card:hover,
.movie-card:focus-visible {
  transform: translateY(-3px);
  box-shadow: var(--glass-shadow-hover);
  z-index: 2;
}
.movie-cover {
  width: 100px;
  height: 140px;
  border-radius: var(--glass-radius-sm);
  overflow: hidden;
  background: var(--cover-placeholder-bg);
  box-shadow: inset 0 0 0 1px var(--glass-border);
}
.movie-cover-img {
  width: 100%;
  height: 100%;
  display: block;
}
.movie-cover-img :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
/* 封面缺失占位：与演员头像占位同款灰底，避免卡片区域整片发白 */
.movie-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 4px;
  text-align: center;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  background: linear-gradient(160deg, rgba(158, 168, 192, 0.22), rgba(140, 152, 182, 0.14));
}
.movie-number {
  padding: 0 4px;
  font-family: Consolas, monospace;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.movie-title {
  padding: 0 4px;
  font-size: 11px;
  line-height: 1.4;
  color: var(--el-text-color-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* ---------- 响应式 ---------- */
@media (max-width: 767px) {
  .photo-wrap {
    margin: 0 auto 16px;
  }
}
/* 触屏无 hover：不做位移，避免误触后卡片保持浮起态 */
@media (hover: none) {
  .movie-card:hover {
    transform: none;
    box-shadow: none;
  }
}
</style>
