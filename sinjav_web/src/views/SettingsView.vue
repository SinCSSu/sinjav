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
          <el-form-item label="合并策略">
            <el-radio-group v-model="form.strategy">
              <el-radio value="fallback">主备回退</el-radio>
              <el-radio value="merge">字段合并</el-radio>
            </el-radio-group>
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
          <el-form-item label="请求间隔 (ms)">
            <el-input-number v-model="form.requestIntervalMs" :min="0" :step="100" />
            <span class="hint">限速防风控</span>
          </el-form-item>
          <el-form-item label="并发数">
            <el-input-number v-model="form.concurrency" :min="1" :max="32" />
          </el-form-item>
        </el-form>
      </el-card>

      <el-card shadow="never" class="settings-card">
        <template #header>整理设置</template>
        <el-form label-width="140px" label-position="left">
          <el-form-item label="目标目录">
            <el-input v-model="form.targetDir" placeholder="如 E:\Library" style="max-width: 500px" />
          </el-form-item>
          <el-form-item label="整理方式">
            <el-select v-model="form.mode" style="width: 200px">
              <el-option label="硬链接" value="hardlink" />
              <el-option label="软链接" value="symlink" />
              <el-option label="移动" value="move" />
              <el-option label="复制" value="copy" />
            </el-select>
          </el-form-item>
          <el-form-item label="命名模板">
            <el-input
              v-model="form.namingTemplate"
              style="max-width: 500px"
              placeholder="{number}/{number} - {title}"
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
        </el-form>
      </el-card>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import type { SettingsUpdate } from '../api/types'

const loading = ref(true)
const saving = ref(false)
const original = ref<SettingsUpdate>({})

const form = reactive<SettingsUpdate>({})

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

onMounted(load)
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
