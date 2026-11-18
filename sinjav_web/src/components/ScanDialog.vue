<template>
  <el-dialog v-model="visible" title="触发扫描" width="520px" :close-on-click-modal="false">
    <el-form label-width="90px" label-position="left">
      <el-form-item label="源目录" required>
        <el-input
          v-model="sourceDirs"
          type="textarea"
          :rows="4"
          placeholder="每行一个绝对路径，如 D:\Movies"
        />
      </el-form-item>
      <el-form-item label="递归子目录">
        <el-switch v-model="recursive" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">开始扫描</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{ created: [] }>()

const sourceDirs = ref('')
const recursive = ref(true)
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
    const job = await api.triggerScan({ sourceDirs: dirs, recursive: recursive.value })
    ElMessage.success(`扫描任务 #${job.jobId} 已创建`)
    visible.value = false
    emit('created')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    submitting.value = false
  }
}
</script>
