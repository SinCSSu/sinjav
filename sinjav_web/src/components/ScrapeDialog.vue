<template>
  <el-dialog v-model="visible" title="触发刮削" width="520px" :close-on-click-modal="false">
    <el-form label-width="90px" label-position="left">
      <el-form-item label="番号列表">
        <el-input
          v-model="numbers"
          type="textarea"
          :rows="4"
          placeholder="每行一个番号；留空则刮削全部「未刮削」番号"
        />
      </el-form-item>
      <el-form-item label="数据源">
        <el-checkbox-group v-model="sources">
          <el-checkbox value="javbus">JavBus</el-checkbox>
          <el-checkbox value="javdb">JavDB</el-checkbox>
        </el-checkbox-group>
        <div class="hint">留空使用全局配置的数据源顺序</div>
      </el-form-item>
      <el-form-item label="合并策略">
        <el-radio-group v-model="strategy">
          <el-radio value="fallback">主备回退</el-radio>
          <el-radio value="merge">字段合并</el-radio>
        </el-radio-group>
        <div class="hint">留空使用全局配置</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">开始刮削</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import type { ScraperSource, ScraperStrategy } from '../api/types'

const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{ created: [] }>()

const numbers = ref('')
const sources = ref<ScraperSource[]>([])
const strategy = ref<ScraperStrategy | ''>('')
const submitting = ref(false)

async function submit() {
  const nums = numbers.value
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
  submitting.value = true
  try {
    const job = await api.triggerScrape({
      numbers: nums.length ? nums : undefined,
      sources: sources.value.length ? sources.value : undefined,
      strategy: strategy.value || undefined,
    })
    ElMessage.success(`刮削任务 #${job.jobId} 已创建`)
    visible.value = false
    emit('created')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
</style>
