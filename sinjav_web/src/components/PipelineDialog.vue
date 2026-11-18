<template>
  <el-dialog v-model="visible" title="触发流水线（扫描 → 刮削 → 整理）" width="560px" :close-on-click-modal="false">
    <el-form label-width="90px" label-position="left">
      <el-divider content-position="left">扫描</el-divider>
      <el-form-item label="源目录" required>
        <el-input
          v-model="sourceDirs"
          type="textarea"
          :rows="3"
          placeholder="每行一个绝对路径，如 D:\Movies"
        />
      </el-form-item>
      <el-form-item label="递归子目录">
        <el-switch v-model="recursive" />
      </el-form-item>

      <el-divider content-position="left">刮削</el-divider>
      <el-form-item label="执行刮削">
        <el-switch v-model="doScrape" />
      </el-form-item>
      <el-form-item v-if="doScrape" label="合并策略">
        <el-radio-group v-model="strategy">
          <el-radio value="fallback">使用全局配置</el-radio>
          <el-radio value="merge">字段合并</el-radio>
        </el-radio-group>
      </el-form-item>

      <el-divider content-position="left">整理</el-divider>
      <el-form-item label="执行整理">
        <el-switch v-model="doOrganize" />
      </el-form-item>
      <template v-if="doOrganize">
        <el-form-item label="目标目录">
          <el-input v-model="targetDir" placeholder="留空使用全局配置" />
        </el-form-item>
        <el-form-item label="整理方式">
          <el-select v-model="mode" clearable placeholder="留空使用全局配置" style="width: 100%">
            <el-option label="硬链接" value="hardlink" />
            <el-option label="软链接" value="symlink" />
            <el-option label="移动" value="move" />
            <el-option label="复制" value="copy" />
          </el-select>
        </el-form-item>
      </template>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">开始执行</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import type { LinkMode, ScraperStrategy } from '../api/types'

const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{ created: [] }>()

const sourceDirs = ref('')
const recursive = ref(true)
const doScrape = ref(true)
const strategy = ref<ScraperStrategy>('fallback')
const doOrganize = ref(true)
const targetDir = ref('')
const mode = ref<LinkMode | ''>('')
const submitting = ref(false)

async function submit() {
  const dirs = sourceDirs.value
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
  if (dirs.length === 0) {
    ElMessage.warning('请输入至少一个源目录')
    return
  }
  submitting.value = true
  try {
    const job = await api.triggerPipeline({
      scan: { sourceDirs: dirs, recursive: recursive.value },
      scrape: doScrape.value
        ? { strategy: strategy.value === 'fallback' ? undefined : strategy.value }
        : undefined,
      organize: doOrganize.value
        ? { targetDir: targetDir.value.trim() || undefined, mode: mode.value || undefined }
        : undefined,
    })
    ElMessage.success(`流水线任务 #${job.jobId} 已创建`)
    visible.value = false
    emit('created')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    submitting.value = false
  }
}
</script>
