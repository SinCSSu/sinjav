<template>
  <div>
    <div class="page-header">
      <h2>设置</h2>
      <el-button icon="Edit" type="primary" :loading="saving" @click="save">保存修改</el-button>
    </div>

    <el-skeleton v-if="loading" :rows="10" animated />
    <template v-else>
      <el-card shadow="never" class="settings-card">
        <template #header>刮削设置</template>
        <el-form label-width="140px" label-position="left">
          <el-form-item label="数据源优先顺序">
            <el-input v-model="form.sourceOrder" placeholder="javbus, javdb" style="max-width: 400px" />
            <div class="hint">默认顺序；番号按类型命中刮削链时按链执行</div>
          </el-form-item>
          <el-form-item label="数据源配置">
            <el-button @click="showSourceDialog = true">站点地址 / 鉴权 Cookie / 超时 / 访问间隔</el-button>
          </el-form-item>
          <el-form-item label="刮削链配置">
            <el-button @click="showChainDialog = true">按番号类型设置流水线</el-button>
          </el-form-item>
          <el-form-item label="User-Agent">
            <el-input v-model="form.userAgent" type="textarea" :rows="2" style="max-width: 600px" />
          </el-form-item>
          <el-form-item label="JavDB Cookie">
            <el-input
              v-model="form.javdbCookie"
              type="textarea"
              :rows="3"
              placeholder="JavDB 登录 Cookie"
              style="max-width: 600px"
            />
          </el-form-item>
          <el-form-item label="代理主机">
            <el-input v-model="form.proxyHost" placeholder="如 127.0.0.1" style="max-width: 300px" />
          </el-form-item>
          <el-form-item label="代理端口">
            <el-input-number v-model="form.proxyPort" :min="0" :max="65535" />
          </el-form-item>
          <el-form-item label="不走代理的域名">
            <el-input
              v-model="form.noProxyHosts"
              type="textarea"
              :rows="2"
              placeholder="逗号或换行分隔，如 dmm.co.jp, *.local；命中则直连（绕过上面与过盾代理）"
              style="max-width: 500px"
            />
          </el-form-item>
          <el-divider content-position="left">CF 过盾（trawl MITM 代理）</el-divider>
          <el-form-item label="过盾代理地址">
            <el-input
              v-model="form.cfProxyUrl"
              placeholder="如 http://127.0.0.1:8192；留空则全部走上方代理/直连"
              style="max-width: 500px"
            />
          </el-form-item>
          <el-form-item label="请求间隔 (ms)">
            <el-input-number v-model="form.requestIntervalMs" :min="0" :step="100" />
          </el-form-item>
          <el-form-item label="并发数">
            <el-input-number v-model="form.concurrency" :min="1" :max="32" />
          </el-form-item>
          <el-form-item label="请求超时 (ms)">
            <el-input-number v-model="form.timeoutMs" :min="1000" :max="120000" :step="1000" />
          </el-form-item>
          <el-form-item label="过盾超时 (ms)">
            <el-input-number v-model="form.cfTimeoutMs" :min="1000" :max="600000" :step="5000" />
          </el-form-item>
          <el-divider content-position="left">翻译设置</el-divider>
          <el-form-item label="自动翻译">
            <el-switch v-model="form.autoTranslate" />
            <span class="hint">开启后批量刮削时翻译标题/简介/类别标签；关闭则直接落源站内容</span>
          </el-form-item>
          <el-form-item label="DeepL API Key">
            <el-input
              v-model="form.deeplApiKey"
              placeholder="免费 key 以 :fx 结尾"
              style="max-width: 500px"
              show-password
            />
          </el-form-item>
          <el-form-item label="百度 APP ID">
            <el-input
              v-model="form.baiduTranslateAppId"
              placeholder="百度翻译开放平台 APP ID"
              style="max-width: 500px"
            />
          </el-form-item>
          <el-form-item label="百度密钥">
            <el-input
              v-model="form.baiduTranslateSecret"
              placeholder="百度翻译开放平台密钥"
              style="max-width: 500px"
              show-password
            />
          </el-form-item>
          <el-form-item>
            <span class="hint">未配置 API 时使用 Google 免费接口（国内需代理）</span>
          </el-form-item>
        </el-form>
      </el-card>

      <el-dialog v-model="showSourceDialog" title="数据源配置" width="1060px">
        <div class="src-toolbar">
          <el-input v-model="sourceFilter" placeholder="筛选数据源…" clearable size="small" style="width: 180px" />
          <span class="hint">{{ sourceSummary }}</span>
        </div>
        <el-table empty-text="暂无数据"
          :data="filteredSourceRows"
          size="small"
          max-height="520"
          :row-class-name="sourceRowClass"
          style="max-width: 1000px"
        >
          <el-table-column align="center" label="数据源" width="150">
            <template #default="{ row }">
              <span class="src-id">{{ row.id }}</span>
              <el-tag v-if="defaultEnabledSources.has(row.id)" size="small" type="success" effect="plain" class="src-tag">默认</el-tag>
              <el-tag v-else-if="sourceHasOverride(row.id)" size="small" type="primary" effect="plain" class="src-tag">自定义</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="过盾" width="55" align="center">
            <template #default="{ row }">
              <el-checkbox
                :model-value="cfProxySourceList.includes(row.id)"
                @change="(v: boolean | string | number) => setCfProxy(row.id, !!v)"
              />
            </template>
          </el-table-column>
          <el-table-column align="center" label="站点地址（镜像）">
            <template #default="{ row }">
              <el-input v-model="row.cfg.baseUrl" placeholder="内置默认" />
            </template>
          </el-table-column>
          <el-table-column align="center" label="鉴权 Cookie" width="200">
            <template #default="{ row }">
              <el-input v-model="row.cfg.cookie" placeholder="内置默认" show-password />
            </template>
          </el-table-column>
          <el-table-column align="center" label="超时(ms)" width="125">
            <template #default="{ row }">
              <el-input-number v-model="row.cfg.timeoutMs" :min="0" :max="600000" :step="1000" controls-position="right" style="width: 105px" />
            </template>
          </el-table-column>
          <el-table-column align="center" label="间隔(ms)" width="125">
            <template #default="{ row }">
              <el-input-number v-model="row.cfg.intervalMs" :min="0" :max="60000" :step="100" controls-position="right" style="width: 105px" />
            </template>
          </el-table-column>
        </el-table>
        <div class="hint" style="margin: 8px 0 0">
          <div>留空 / 0 = 使用内置默认与全局配置</div>
          <div>Cookie 留空自动携带内置年龄验证（mgstage/prestige/fanza），JavDB 回退刮削设置的全局 Cookie</div>
          <div>访问间隔按数据源独立限速（不同源并行，同源按间隔串行）</div>
          <div>勾选「过盾」的数据源走 CF 过盾代理（需先配置过盾代理地址）</div>
        </div>
        <template #footer>
          <el-button icon="Close" @click="showSourceDialog = false">取消</el-button>
          <el-button icon="Check" type="primary" :loading="saving" @click="saveAndClose('source')">保存</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="showChainDialog" title="刮削链配置" width="1040px">
        <el-form label-width="90px" label-position="left">
          <el-form-item v-for="chain in chains" :key="chain.key" :label="chain.label">
            <div class="chain-row">
              <div class="chain-scroll">
                <VueDraggable
                  v-model="chainLists[chain.key]"
                  :animation="180"
                  ghost-class="chain-ghost"
                  chosen-class="chain-chosen"
                  class="chain-list"
                  @end="syncChain(chain.key)"
                >
                  <div v-for="(id, i) in chainLists[chain.key]" :key="id" class="chain-block">
                    <span class="chain-block-src">{{ id }}</span>
                    <el-icon class="chain-block-close" title="移除" @click.stop="removeChainItem(chain.key, i)">
                      <CircleCloseFilled />
                    </el-icon>
                  </div>
                </VueDraggable>
                <el-dropdown
                  trigger="click"
                  class="chain-plus-wrap"
                  @command="(v: string) => addChainItem(chain.key, v)"
                >
                  <el-button
                    class="chain-plus"
                    :class="{ 'chain-plus-solo': !chainLists[chain.key]?.length }"
                    title="添加数据源"
                  >
                    <el-icon><Plus /></el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item
                        v-for="s in addableSources(chain.key)"
                        :key="s"
                        :command="s"
                        class="chain-plus-item"
                      >
                        {{ s }}
                      </el-dropdown-item>
                      <el-dropdown-item v-if="!addableSources(chain.key).length" disabled>
                        所有源都已在链中
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <span v-if="!chainLists[chain.key]?.length" class="hint" style="margin-left: 8px">
                  空 = 回退「数据源优先顺序」
                </span>
              </div>
              <el-button type="primary" size="small" @click="restoreChain(chain)">
                <el-icon style="margin-right: 4px"><RefreshLeft /></el-icon>恢复默认
              </el-button>
            </div>
            <div class="hint">{{ chain.hint }}</div>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button icon="Close" @click="showChainDialog = false">取消</el-button>
          <el-button icon="Check" type="primary" :loading="saving" @click="saveAndClose('chain')">保存</el-button>
        </template>
      </el-dialog>

      <el-card shadow="never" class="settings-card">
        <template #header>账号</template>
        <el-form label-width="140px" label-position="left">
          <el-form-item label="当前密码">
            <el-input
              v-model="cred.currentPassword"
              type="password"
              show-password
              style="max-width: 320px"
            />
          </el-form-item>
          <el-form-item label="用户名">
            <el-input v-model="cred.username" style="max-width: 320px" />
          </el-form-item>
          <el-form-item label="新密码">
            <el-input
              v-model="cred.newPassword"
              type="password"
              show-password
              placeholder="留空则不修改密码"
              style="max-width: 320px"
            />
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input
              v-model="cred.confirm"
              type="password"
              show-password
              style="max-width: 320px"
            />
          </el-form-item>
          <el-form-item label=" ">
            <el-button icon="Check" type="primary" :loading="savingCred" @click="saveCredentials">保存账号</el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card shadow="never" class="settings-card">
        <template #header>整理设置</template>
        <el-form label-width="140px" label-position="left">
          <el-form-item label="命名模板">
            <el-input
              v-model="form.namingTemplate"
              style="max-width: 500px"
              placeholder="{number}/{number}-{part}{version}"
            />
            <div class="hint">
              占位符：{number} {title} {date} {maker} {series} {actress} {part} {version}
            </div>
            <div class="hint">
              {part}：上下集分片后缀（-cd1/-cd2），如 <code>{number}/{number} - {title}-{part}</code>；
              无分片时自动省略
            </div>
            <div class="hint">
              {version}：多版本标记（1080p / 4K / 无码 / 中字），渲染为 <code> - 1080p</code>；
              同番号多分辨率靠它区分，无标记时自动省略
            </div>
          </el-form-item>
          <el-form-item label="冲突策略">
            <el-select v-model="form.conflictPolicy" style="width: 200px">
              <el-option label="跳过" value="skip" />
              <el-option label="覆盖" value="overwrite" />
              <el-option label="重命名" value="rename" />
            </el-select>
          </el-form-item>
          <el-form-item label="生成 NFO">
            <el-switch v-model="form.generateNfo" />
          </el-form-item>
          <el-form-item label="下载图片">
            <el-switch v-model="form.downloadImages" />
          </el-form-item>
          <el-divider content-position="left">图片缓存</el-divider>
          <el-form-item label="缓存有效期">
            <el-input-number v-model="form.imagesTtlDays" :min="0" :max="365" />
            <span class="hint">天；0 = 永久保留</span>
          </el-form-item>
          <el-form-item label=" ">
            <el-button :loading="cleaning" @click="cleanupCache">清理缓存</el-button>
            <span v-if="cleanResult" class="hint">{{ cleanResult }}</span>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card shadow="never" class="settings-card">
        <template #header>Emby 设置</template>
        <el-form label-width="140px" label-position="left">
          <el-form-item label="服务地址">
            <el-input v-model="embyBaseUrlModel" placeholder="如 http://127.0.0.1:8096" style="max-width: 500px" />
          </el-form-item>
          <el-form-item label="API Key">
            <el-input
              v-model="embyApiKeyModel"
              placeholder="Emby 控制台「API 密钥」中生成"
              style="max-width: 500px"
              show-password
            />
          </el-form-item>
          <el-form-item label="代理主机">
            <el-input v-model="embyProxyHostModel" placeholder="留空直连，如 127.0.0.1" style="max-width: 300px" />
          </el-form-item>
          <el-form-item label="代理端口">
            <el-input-number v-model="embyProxyPortModel" :min="0" :max="65535" />
          </el-form-item>
          <el-form-item>
            <el-button :loading="testing" @click="testConnection">测试连接</el-button>
            <span v-if="testResult" class="hint">{{ testResult }}</span>
          </el-form-item>
          <el-form-item>
            <span class="hint">需先保存设置再测试连接</span>
          </el-form-item>
        </el-form>
      </el-card>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { VueDraggable } from 'vue-draggable-plus'
import { api } from '../api'
import type { SettingsUpdate } from '../api/types'

const loading = ref(true)
const saving = ref(false)
const testing = ref(false)
const testResult = ref('')
const cleaning = ref(false)
const cleanResult = ref('')
const original = ref<SettingsUpdate>({})

// 弹窗：数据源配置 / 刮削链配置（保存后关闭）
const showSourceDialog = ref(false)
const showChainDialog = ref(false)

/**
 * 保存并关闭弹窗。
 *
 * 注意：模板里的 ref 会自动解包成布尔值，所以这里**不能**收 ref（`dialog.value = false` 写的是原始值，
 * 严格模式下会抛 TypeError、弹窗关不掉）。改为按目标开关关闭。
 */
async function saveAndClose(target: 'source' | 'chain') {
  await save()
  if (target === 'source') showSourceDialog.value = false
  else showChainDialog.value = false
}

// 账号管理（改用户名/密码，需验证当前密码）
const cred = reactive({ currentPassword: '', username: '', newPassword: '', confirm: '' })
const credUsername = ref('')
const savingCred = ref(false)

async function loadAccount() {
  try {
    const st = await api.authStatus()
    credUsername.value = st.username ?? ''
    cred.username = credUsername.value
  } catch {
    // 未登录由守卫处理
  }
}

async function saveCredentials() {
  if (!cred.currentPassword) {
    ElMessage.warning('请输入当前密码')
    return
  }
  if (cred.newPassword && cred.newPassword !== cred.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  savingCred.value = true
  try {
    const res = await api.authUpdateCredentials({
      currentPassword: cred.currentPassword,
      username: cred.username.trim() !== credUsername.value ? cred.username.trim() : undefined,
      newPassword: cred.newPassword || undefined,
    })
    credUsername.value = res.username
    cred.currentPassword = ''
    cred.newPassword = ''
    cred.confirm = ''
    ElMessage.success('账号信息已更新')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    savingCred.value = false
  }
}

const form = reactive<SettingsUpdate>({})

/** 刮削链：按番号类型 5 条流水线（复刻 JavSP crawler.selection） */
const JAVSP_CHAIN_DEFAULTS: Record<string, string> = {
  chainNormal: 'airav, avsox, javbus, javdb, javlib, jav321, mgstage, prestige',
  chainFc2: 'fc2, avsox, javdb, javmenu, fc2ppvdb',
  chainCid: 'fanza',
  chainGetchu: 'dl_getchu',
  chainGyutto: 'gyutto',
}

const chains = [
  {
    key: 'chainNormal' as keyof SettingsUpdate,
    label: '有码常规',
    placeholder: JAVSP_CHAIN_DEFAULTS.chainNormal,
    hint: '标准番号（ABP-123 等）与无码站番号（HEYZO/CARIB）走此链；顺序即字段优先级',
  },
  {
    key: 'chainFc2' as keyof SettingsUpdate,
    label: 'FC2',
    placeholder: JAVSP_CHAIN_DEFAULTS.chainFc2,
    hint: 'FC2-PPV 系番号走此链',
  },
  {
    key: 'chainCid' as keyof SettingsUpdate,
    label: 'CID',
    placeholder: JAVSP_CHAIN_DEFAULTS.chainCid,
    hint: '纯数字内容 ID（DMM cid 形态）走此链；FANZA 需日本 IP',
  },
  {
    key: 'chainGetchu' as keyof SettingsUpdate,
    label: 'Getchu',
    placeholder: JAVSP_CHAIN_DEFAULTS.chainGetchu,
    hint: 'GETCHU- 前缀番号走此链',
  },
  {
    key: 'chainGyutto' as keyof SettingsUpdate,
    label: 'Gyutto',
    placeholder: JAVSP_CHAIN_DEFAULTS.chainGyutto,
    hint: 'GYUTTO- 前缀番号走此链',
  },
]

function restoreChain(chain: { key: keyof SettingsUpdate }) {
  const def = JAVSP_CHAIN_DEFAULTS[chain.key as string] ?? ''
  // chain.key 是 keyof SettingsUpdate 的联合类型（还含 sourceConfigs 等非字符串字段），
  // 直接用下标赋值会被 TS 判为不可写；chains 里的 key 只会是 chain* 这几个字符串字段，故用 Object.assign 写回
  Object.assign(form, { [chain.key]: def })
  chainLists[chain.key] = def.split(',').map((s) => s.trim()).filter(Boolean)
}

const embyBaseUrlModel = computed({
  get: () => form.embyBaseUrl ?? '',
  set: (v: string) => {
    form.embyBaseUrl = v
  },
})

const embyApiKeyModel = computed({
  get: () => form.embyApiKey ?? '',
  set: (v: string) => {
    form.embyApiKey = v
  },
})

const embyProxyHostModel = computed({
  get: () => form.embyProxyHost ?? '',
  set: (v: string) => {
    form.embyProxyHost = v
  },
})

const embyProxyPortModel = computed({
  get: () => form.embyProxyPort ?? 0,
  set: (v: number) => {
    form.embyProxyPort = v
  },
})

/** cfProxySources 逗号串 <-> 数组（数据源配置表的「过盾」勾选列） */
const cfProxySourceList = computed(() =>
  (form.cfProxySources ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean),
)

/** 数据源配置表「过盾」列勾选回调：增删单个源后回写逗号串 */
function setCfProxy(id: string, checked: boolean) {
  const set = new Set(cfProxySourceList.value)
  if (checked) set.add(id)
  else set.delete(id)
  form.cfProxySources = [...set].join(', ')
}

/** 已注册数据源 id（后端返回；用于数据源配置表行与刮削链下拉选项） */
const knownSources = ref<string[]>([])

/** 默认启用的源（sourceOrder 内置默认），其余为 JavSP 移植源 */
const defaultEnabledSources = new Set(['javbus', 'javdb', 'missav'])

/** 该源是否配置了任意覆盖项（用于「自定义」标签与行高亮） */
function sourceHasOverride(id: string): boolean {
  const c = form.sourceConfigs?.[id]
  return !!c && (!!c.baseUrl || !!c.cookie || !!c.timeoutMs || !!c.intervalMs)
}

/** 数据源配置表行：每源一行，未配置过的源补空对象（引用 form.sourceConfigs 以保持响应式） */
const sourceRows = computed(() =>
  knownSources.value.map((id) => {
    const map = (form.sourceConfigs ??= {})
    if (!map[id]) map[id] = {}
    return { id, cfg: map[id]! }
  }),
)

// 数据源配置弹窗：筛选 + 统计
const sourceFilter = ref('')

const filteredSourceRows = computed(() => {
  const kw = sourceFilter.value.trim().toLowerCase()
  return kw ? sourceRows.value.filter((r) => r.id.includes(kw)) : sourceRows.value
})

const sourceSummary = computed(() => {
  const customized = sourceRows.value.filter((r) => sourceHasOverride(r.id)).length
  return `共 ${sourceRows.value.length} 个源 · 自定义配置 ${customized} · 过盾 ${cfProxySourceList.value.length}`
})

function sourceRowClass({ row }: { row: { id: string } }): string {
  return sourceHasOverride(row.id) ? 'src-customized' : ''
}

/**
 * 刮削链积木卡片：VueDraggable（SortableJS）要求可变数组作 v-model，
 * 因此编辑期间以 chainLists 为工作副本，弹窗打开时从 form 逗号串初始化，
 * 拖拽/增删后 syncChain 回写 form（保存时仍走逗号串，后端零改动）。
 */
const chainLists = reactive<Record<string, string[]>>({})

function chainItemsOf(key: keyof SettingsUpdate): string[] {
  return ((form[key] as string) ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
}

function ensureChainLists() {
  chains.forEach((c) => {
    chainLists[c.key] = chainItemsOf(c.key)
  })
}
watch(showChainDialog, (open) => {
  if (open) ensureChainLists()
})

function writeChain(key: keyof SettingsUpdate, items: string[]) {
  ;(form as Record<string, unknown>)[key as string] = items.join(', ')
}

/** VueDraggable @end：工作副本回写 form 逗号串 */
function syncChain(key: keyof SettingsUpdate) {
  writeChain(key, chainLists[key] ?? [])
}

/** 该链下拉框可选内容（未注册 id 保留可选，避免编辑时丢数据） */
function chainOptions(key: keyof SettingsUpdate): string[] {
  const all = new Set(knownSources.value)
  ;(chainLists[key] ?? chainItemsOf(key)).forEach((id) => all.add(id))
  return [...all]
}

/** 「添加源」下拉框：排除链中已有项 */
function addableSources(key: keyof SettingsUpdate): string[] {
  const items = new Set(chainLists[key] ?? [])
  return chainOptions(key).filter((s) => !items.has(s))
}

function removeChainItem(key: keyof SettingsUpdate, index: number) {
  chainLists[key]?.splice(index, 1)
  syncChain(key)
}

/** 末尾「+」下拉选择后追加到链尾 */
function addChainItem(key: keyof SettingsUpdate, v: string) {
  if (!v) return
  const list = (chainLists[key] ??= [])
  if (!list.includes(v)) list.push(v)
  syncChain(key)
}

async function load() {
  loading.value = true
  try {
    const settings = await api.getSettings()
    Object.assign(original.value, settings)
    Object.assign(form, settings)
    knownSources.value = settings.knownSources ?? []
    // 确保每源都有配置对象（未配置过的源补空）
    const map = (form.sourceConfigs ??= {})
    knownSources.value.forEach((id) => {
      if (!map[id]) map[id] = {}
    })
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loading.value = false
  }
}

function buildUpdate(): SettingsUpdate {
  // 仅提交发生变化或与初始值不同的键
  const update: Record<string, unknown> = {}
  const originalRecord = original.value as Record<string, unknown>
  const formRecord = form as Record<string, unknown>
  for (const key of Object.keys(formRecord)) {
    const value = formRecord[key]
    if (value === undefined || value === null) continue
    if (value === '' && (originalRecord[key] === undefined || originalRecord[key] === null)) continue
    update[key] = value
  }
  return update as SettingsUpdate
}

async function save() {
  saving.value = true
  testResult.value = ''
  try {
    const saved = await api.updateSettings(buildUpdate())
    Object.assign(original.value, saved)
    Object.assign(form, saved)
    ElMessage.success('设置已保存并立即生效')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    saving.value = false
  }
}

/** 测试 Emby 连接：先保存，再调 /emby/status 验证 */
async function testConnection() {
  testResult.value = ''
  testing.value = true
  try {
    await save()
    const info = await api.getEmbyStatus()
    testResult.value = `连接成功：${info.serverName || 'Emby'}（v${info.version}）`
  } catch (e) {
    testResult.value = e instanceof Error ? e.message : String(e)
  } finally {
    testing.value = false
  }
}

/** 手动清理过期图片缓存（按当前保存的 TTL） */
function formatSize(bytes: number): string {
  if (bytes >= 1 << 30) return `${(bytes / 1e9).toFixed(1)} GB`
  if (bytes >= 1 << 20) return `${(bytes / 1e6).toFixed(1)} MB`
  if (bytes >= 1 << 10) return `${(bytes / 1e3).toFixed(1)} KB`
  return `${bytes} B`
}

async function cleanupCache() {
  cleanResult.value = ''
  cleaning.value = true
  try {
    const res = await api.cleanupImageCache()
    cleanResult.value = res.files > 0
      ? `已删除 ${res.files} 个过期文件（${formatSize(res.bytes)}）`
      : '没有过期缓存'
  } catch (e) {
    cleanResult.value = e instanceof Error ? e.message : String(e)
  } finally {
    cleaning.value = false
  }
}

onMounted(() => {
  load()
  loadAccount()
})
</script>

<style scoped>
.settings-card {
  margin-bottom: 16px;
  max-width: 960px;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-left: 8px;
  line-height: 1.6;
}

/* ---- 数据源配置弹窗 ---- */
.src-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.src-id {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-weight: 600;
  font-size: 13px;
}
.src-tag {
  margin-left: 6px;
}
/* 有自定义配置的行：淡色底，一眼区分「动过」的源 */
:deep(.el-table .src-customized td.el-table__cell) {
  background-color: var(--el-color-primary-light-9);
}

/* ---- 刮削链配置弹窗（链式选择框） ---- */
.chain-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  width: 100%;
}
.chain-arrow {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
/* 积木链区：单行横向排列，过长时横向滚动，「+」始终紧跟末卡不换行 */
.chain-scroll {
  display: flex;
  align-items: center;
  overflow-x: auto;
  overflow-y: hidden;
  max-width: 100%;
  padding: 2px;
}
/* 积木卡片容器（VueDraggable 排序列表）：卡片紧挨着，靠凹凸咬合拼接 */
.chain-list {
  display: inline-flex;
  flex-wrap: nowrap;
  align-items: center;
}
/*
 * 积木卡片（Scratch 式咬合，配色沿用主题）：
 * ::before = 左侧凹口（弹窗底色圆盘在左缘挖出半圆咬口，承接上一张卡的凸榫）
 * ::after  = 右侧凸榫（半圆榫头伸出右缘，嵌进下一张卡的凹口）
 * 卡片盒子间距为 0；凸榫 z-index 高于下一张卡的凹口圆盘 → 榫头盖住咬口正好卡紧。
 */
/* 卡片/榫头/「+」统一用同一底色变量，保证榫卯处颜色完全一致 */
.chain-row {
  --block-bg: var(--el-color-primary-light-8);
}
.chain-block {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  padding: 0 12px;
  border: 1px solid var(--el-color-primary-light-5);
  border-radius: var(--el-border-radius-small);
  background: var(--block-bg);
  user-select: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}
/* 末尾「+」块：用左缘凹口承接最后一张卡的凸榫，点击弹出可添加数据源 */
.chain-plus-wrap {
  display: inline-flex;
}
.chain-plus {
  position: relative;
  height: 26px;
  padding: 0 9px;
  margin-left: 0;
  border: 1px solid var(--el-color-primary-light-5);
  border-left: none;
  border-radius: 0 6px 6px 0;
  background: var(--block-bg);
  color: var(--el-text-color-regular);
}
/* 「+」块左缘凹口：承接末卡的凸榫（-7px 对准视觉边框线，裁掉溢出左半圆） */
.chain-plus::before {
  content: '';
  position: absolute;
  left: -7px;
  top: 50%;
  transform: translateY(-50%);
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--el-dialog-bg-color, var(--el-bg-color));
  clip-path: inset(0 0 0 50%);
  z-index: 1;
}
.chain-plus:hover,
.chain-plus:focus {
  background: var(--el-color-primary-light-7);
  border-color: var(--el-color-primary-light-5);
  color: var(--el-color-primary);
}
/* 链为空时「+」独立成块，无凸榫 */
.chain-plus-solo {
  margin-left: 0;
  border-left: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-small);
}
.chain-plus-solo::before {
  display: none;
}
.chain-plus-item {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-weight: 600;
}
/* 左侧凹口：弹窗底色圆盘在左缘挖出半圆咬口，承接上一张卡的凸榫。
   定位基准是 padding 盒（边框内侧 1px），故 -7px 才能对准视觉边框线；
   clip-path 裁掉圆盘溢出到上一张卡上的左半圆（否则会显出白色圆点） */
.chain-block::before {
  content: '';
  position: absolute;
  left: -7px;
  top: 50%;
  transform: translateY(-50%);
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--el-dialog-bg-color, var(--el-bg-color));
  clip-path: inset(0 0 0 50%);
  z-index: 1;
}
/* 右侧凸榫：半圆榫头伸出右缘，嵌进下一张卡的凹口（z 高于凹口圆盘 → 卡紧）。
   right -7px + 宽 7px：左端盖住矩形边框在榫根的残留、右端凸出 6px，描边沿咬合轮廓连续 */
.chain-block::after {
  content: '';
  position: absolute;
  right: -7px;
  top: 50%;
  transform: translateY(-50%);
  width: 7px;
  height: 12px;
  background: var(--block-bg);
  border: 1px solid var(--el-color-primary-light-5);
  border-left: none;
  border-radius: 0 6px 6px 0;
  z-index: 2;
}
.chain-block:first-child::before {
  display: none;
}
/* SortableJS 状态：被拖起的原位残影 / 手中的卡片 */
.chain-ghost {
  opacity: 0.35;
  border-style: dashed;
}
.chain-chosen {
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.18);
}
.chain-block-src {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-weight: 600;
  font-size: 13px;
}
.chain-block-close {
  color: var(--el-text-color-placeholder);
  font-size: 13px;
  cursor: pointer;
}
.chain-block-close:hover {
  color: var(--el-color-danger);
}
/* 恢复默认按钮：type=primary，直接沿用全局主按钮渐变样式 */
</style>
