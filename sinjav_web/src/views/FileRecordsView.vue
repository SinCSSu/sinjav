<template>
  <div>
    <div class="page-header">
      <h2>文件记录</h2>
    </div>

    <div class="toolbar">
      <el-input
        v-model="number"
        placeholder="按番号过滤，如 ABP-123"
        clearable
        style="width: 220px"
        @keyup.enter="search"
        @clear="search"
      />
      <el-select
        v-model="recognizeStatus"
        clearable
        placeholder="识别状态"
        style="width: 130px"
        @change="search"
      >
        <el-option label="已识别" value="recognized" />
        <el-option label="未识别" value="unrecognized" />
        <el-option label="已过滤" value="filtered" />
      </el-select>
      <el-select
        v-model="organizeStatus"
        clearable
        placeholder="整理状态"
        style="width: 130px"
        @change="search"
      >
        <el-option label="待整理" value="pending" />
        <el-option label="已整理" value="organized" />
        <el-option label="整理失败" value="failed" />
        <el-option label="已跳过" value="skipped" />
      </el-select>
      <el-button :icon="SearchIcon" @click="search">查询</el-button>
    </div>

    <el-table :data="items" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="fileName" label="文件名" min-width="200" show-overflow-tooltip />
      <el-table-column prop="sourcePath" label="源路径" min-width="240" show-overflow-tooltip />
      <el-table-column label="大小" width="90" align="right">
        <template #default="{ row }">{{ formatFileSize(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column label="番号" width="140">
        <template #default="{ row }">
          <span v-if="row.number" class="mono">{{ row.number }}</span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="识别" width="92" align="center">
        <template #default="{ row }: { row: FileRecord }">
          <el-tag :type="recognizeStatusTagTypes[row.recognizeStatus]" size="small">
            {{ recognizeStatusLabels[row.recognizeStatus] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="整理" width="92" align="center">
        <template #default="{ row }: { row: FileRecord }">
          <el-tag :type="organizeStatusTagTypes[row.organizeStatus]" size="small">
            {{ organizeStatusLabels[row.organizeStatus] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="targetPath" label="目标路径" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.targetPath ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="入库时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="load"
        @size-change="search"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Search as SearchIcon } from '@element-plus/icons-vue'
import { api } from '../api'
import type { FileRecord, OrganizeStatus, RecognizeStatus } from '../api/types'
import {
  formatDateTime,
  formatFileSize,
  organizeStatusLabels,
  organizeStatusTagTypes,
  recognizeStatusLabels,
  recognizeStatusTagTypes,
} from '../utils'

const number = ref('')
const recognizeStatus = ref<RecognizeStatus | ''>('')
const organizeStatus = ref<OrganizeStatus | ''>('')
const page = ref(1)
const size = ref(20)
const total = ref(0)
const items = ref<FileRecord[]>([])
const loading = ref(false)

function search() {
  page.value = 1
  load()
}

async function load() {
  loading.value = true
  try {
    const result = await api.listFileRecords({
      page: page.value,
      size: size.value,
      number: number.value.trim() || undefined,
      recognizeStatus: recognizeStatus.value || undefined,
      organizeStatus: organizeStatus.value || undefined,
    })
    items.value = result.items
    total.value = result.total
  } catch (e) {
    items.value = []
    total.value = 0
    console.error(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>
