<template>
  <el-dialog v-model="visible" title="触发整理" width="560px" :close-on-click-modal="false">
    <el-form label-width="90px" label-position="left">
      <el-form-item label="目标目录">
        <el-input v-model="targetDir" placeholder="留空使用全局配置，如 E:\Library" />
      </el-form-item>
      <el-form-item label="整理方式">
        <el-select v-model="mode" clearable placeholder="留空使用全局配置" style="width: 100%">
          <el-option label="硬链接" value="hardlink" />
          <el-option label="软链接" value="symlink" />
          <el-option label="移动" value="move" />
          <el-option label="复制" value="copy" />
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
      <el-form-item label="指定番号">
        <el-input
          v-model="numbers"
          type="textarea"
          :rows="3"
          placeholder="每行一个番号；留空则整理全部「已刮削且待整理」的文件"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">开始整理</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import type { ConflictPolicy, LinkMode } from '../api/types'

const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{ created: [] }>()

const targetDir = ref('')
const mode = ref<LinkMode | ''>('')
const namingTemplate = ref('')
const conflictPolicy = ref<ConflictPolicy | ''>('')
const numbers = ref('')
const submitting = ref(false)

async function submit() {
  const nums = numbers.value
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
  submitting.value = true
  try {
    const job = await api.triggerOrganize({
      targetDir: targetDir.value.trim() || undefined,
      mode: mode.value || undefined,
      namingTemplate: namingTemplate.value.trim() || undefined,
      conflictPolicy: conflictPolicy.value || undefined,
      numbers: nums.length ? nums : undefined,
    })
    ElMessage.success(`整理任务 #${job.jobId} 已创建`)
    visible.value = false
    emit('created')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    submitting.value = false
  }
}
</script>
