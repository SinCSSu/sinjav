<template>
  <div>
    <div class="page-header">
      <h2>设置</h2>
      <el-button type="primary" :loading="saving" @click="save">保存修改</el-button>
    </div>

    <el-skeleton v-if="loading" :rows="10" animated />
    <template v-else>
      <el-card shadow="never" class="settings-card">
        <template #header>刮削设置</template>
        <el-form label-width="140px" label-position="left">
          <el-form-item label="数据源优先顺序">
            <el-input v-model="form.sourceOrder" placeholder="javbus, javdb" style="max-width: 400px" />
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
          <el-divider content-position="left">CF 过盾（trawl MITM 代理）</el-divider>
          <el-form-item label="过盾代理地址">
            <el-input
              v-model="form.cfProxyUrl"
              placeholder="如 http://127.0.0.1:8192；留空则全部走上方代理/直连"
              style="max-width: 500px"
            />
          </el-form-item>
          <el-form-item label="过盾数据源">
            <el-select
              v-model="cfProxySourceList"
              multiple
              collapse-tags
              placeholder="不勾选则不过盾"
              style="width: 300px"
            >
              <el-option v-for="s in sourceOptions" :key="s" :label="s" :value="s" />
            </el-select>
            <span class="hint">勾选的数据源请求与图片走 trawl 过盾代理，其余走全局代理/直连</span>
          </el-form-item>
          <el-form-item label="请求间隔 (ms)">
            <el-input-number v-model="form.requestIntervalMs" :min="0" :step="100" />
            <span class="hint">限速防风控</span>
          </el-form-item>
          <el-form-item label="并发数">
            <el-input-number v-model="form.concurrency" :min="1" :max="32" />
            <span class="hint">同时刮削的影片数；每个影片会并发请求所有数据源</span>
          </el-form-item>
          <el-form-item label="请求超时 (ms)">
            <el-input-number v-model="form.timeoutMs" :min="1000" :max="120000" :step="1000" />
            <span class="hint">普通请求（直连/全局代理）的超时时间</span>
          </el-form-item>
          <el-form-item label="过盾超时 (ms)">
            <el-input-number v-model="form.cfTimeoutMs" :min="1000" :max="600000" :step="5000" />
            <span class="hint">走过盾代理的请求超时，需等待 CF 挑战，建议比普通超时长</span>
          </el-form-item>
          <el-divider content-position="left">翻译设置（DeepL &gt; 百度 &gt; Google 兜底）</el-divider>
          <el-form-item label="DeepL API Key">
            <el-input
              v-model="form.deeplApiKey"
              placeholder="配置后翻译优先使用 DeepL；免费 key 以 :fx 结尾"
              style="max-width: 500px"
              show-password
            />
          </el-form-item>
          <el-form-item label="百度 APP ID">
            <el-input
              v-model="form.baiduTranslateAppId"
              placeholder="百度翻译开放平台 APP ID；未配置 DeepL 时使用"
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
            <span class="hint">
              未配置任何 API 时使用 Google 免费接口（国内网络需在上方配置代理）；翻译失败的条目保留原文
            </span>
          </el-form-item>
        </el-form>
      </el-card>

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
            <el-button type="primary" :loading="savingCred" @click="saveCredentials">保存账号</el-button>
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
              placeholder="{number}/{number}"
            />
            <div class="hint">
              占位符：{number} {title} {date} {maker} {series} {actress}；最后一段为文件基名，之前为子目录
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
            <span class="hint">天；过期缓存图片每日 04:00 自动清理，重新刮削的影片自动续期；0 = 永久保留</span>
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
            <span class="hint">Emby 专用代理，与刮削代理互不影响</span>
          </el-form-item>
          <el-form-item>
            <el-button :loading="testing" @click="testConnection">测试连接</el-button>
            <span v-if="testResult" class="hint">{{ testResult }}</span>
          </el-form-item>
          <el-form-item>
            <span class="hint">
              配置后可在影片库页切换到 Emby 数据源：浏览指定媒体库、查看/修改条目元数据、触发重新刮削。
              需先保存设置再测试连接。
            </span>
          </el-form-item>
        </el-form>
      </el-card>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import type { SettingsUpdate } from '../api/types'

const loading = ref(true)
const saving = ref(false)
const testing = ref(false)
const testResult = ref('')
const cleaning = ref(false)
const cleanResult = ref('')
const original = ref<SettingsUpdate>({})

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

/** 数据源下拉选项：从 sourceOrder 解析 */
const sourceOptions = computed(() =>
  (form.sourceOrder ?? '')
    .split(',')
    .map((s) => s.trim().toLowerCase())
    .filter(Boolean)
)

/** cfProxySources 逗号串 <-> 多选数组 */
const cfProxySourceList = computed({
  get: () =>
    (form.cfProxySources ?? '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean),
  set: (v: string[]) => {
    form.cfProxySources = v.join(', ')
  },
})

async function load() {
  loading.value = true
  try {
    const settings = await api.getSettings()
    Object.assign(original.value, settings)
    Object.assign(form, settings)
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
</style>
