<template>
  <el-dialog
    v-model="visible"
    title="手动落库"
    width="660px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <el-form label-width="72px" size="small" @submit.prevent>
      <el-row :gutter="12">
        <el-col :span="10">
          <el-form-item label="番号" required>
            <el-input v-model="form.number" placeholder="如 ABC-123" class="mono" />
          </el-form-item>
        </el-col>
        <el-col :span="14">
          <el-form-item label="标题">
            <el-input v-model="form.title" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="12">
        <el-col :span="14">
          <el-form-item label="发行日期">
            <el-date-picker
              v-model="form.releaseDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="yyyy-MM-dd"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
        <el-col :span="10">
          <el-form-item label="时长(分)">
            <el-input-number v-model="form.durationMin" :min="1" :max="6000" style="width: 100%" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="导演">
            <el-input v-model="form.director" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="系列">
            <el-input v-model="form.series" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="厂商">
            <el-input v-model="form.maker" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="发行商">
            <el-input v-model="form.label" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="女优">
        <el-input v-model="form.actresses" placeholder="多个用逗号 / 分隔" />
      </el-form-item>
      <el-form-item label="类别">
        <el-input v-model="form.genres" placeholder="多个用逗号 / 分隔" />
      </el-form-item>
      <el-form-item label="简介">
        <el-input v-model="form.plot" type="textarea" :rows="3" />
      </el-form-item>
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="封面">
            <el-upload
              :auto-upload="false"
              :limit="1"
              accept="image/*"
              :on-change="onCoverChange"
              :on-remove="() => (coverFile = null)"
              :on-exceed="onCoverExceed"
            >
              <el-button size="small">选择图片</el-button>
              <template #tip><div class="upload-tip">保存为 poster.jpg</div></template>
            </el-upload>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="剧照">
            <el-upload
              :auto-upload="false"
              multiple
              accept="image/*"
              v-model:file-list="previewFiles"
            >
              <el-button size="small">选择图片（可多选）</el-button>
              <template #tip><div class="upload-tip">按选择顺序保存为 previewN.jpg</div></template>
            </el-upload>
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>
    <template #footer>
      <el-button icon="Close" @click="visible = false">取消</el-button>
      <el-button icon="Check" type="primary" :loading="saving" :disabled="!form.number.trim()" @click="save">
        保存并落库
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadFile, UploadUserFile } from 'element-plus'
import { api } from '../api'

const visible = defineModel<boolean>({ default: false })
/** 快捷入口：打开时预填番号 */
const props = defineProps<{ presetNumber?: string }>()
const emit = defineEmits<{ created: [] }>()

const saving = ref(false)
const coverFile = ref<File | null>(null)
const previewFiles = ref<UploadUserFile[]>([])

const emptyForm = () => ({
  number: '',
  title: '',
  titleOrig: '',
  releaseDate: '',
  durationMin: undefined as number | undefined,
  director: '',
  maker: '',
  label: '',
  series: '',
  actresses: '',
  genres: '',
  plot: '',
})
const form = reactive(emptyForm())

function onOpen() {
  Object.assign(form, emptyForm())
  form.number = props.presetNumber ?? ''
  coverFile.value = null
  previewFiles.value = []
}

function onCoverChange(file: UploadFile) {
  coverFile.value = (file.raw as File) ?? null
}

/** limit=1 时重复选择替换已有文件 */
function onCoverExceed(files: FileList) {
  coverFile.value = files[0] ?? null
}

async function save() {
  const number = form.number.trim()
  if (!number) return
  const fd = new FormData()
  fd.append('number', number)
  const text = (v: string) => v.trim() || ''
  fd.append('title', text(form.title))
  fd.append('titleOrig', text(form.titleOrig))
  fd.append('plot', form.plot.trim())
  fd.append('releaseDate', text(form.releaseDate))
  if (form.durationMin) fd.append('durationMin', String(form.durationMin))
  fd.append('director', text(form.director))
  fd.append('maker', text(form.maker))
  fd.append('label', text(form.label))
  fd.append('series', text(form.series))
  fd.append('actresses', text(form.actresses))
  fd.append('genres', text(form.genres))
  if (coverFile.value) fd.append('cover', coverFile.value)
  for (const f of previewFiles.value) {
    const raw = f.raw as File | undefined
    if (raw) fd.append('previews', raw)
  }
  saving.value = true
  try {
    const res = await api.scrapeManual(fd)
    ElMessage.success(`${res.number} 已手动落库`)
    emit('created')
    visible.value = false
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.mono {
  font-family: Consolas, monospace;
}
.upload-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
</style>
