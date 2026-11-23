<template>
  <div>
    <div class="page-header">
      <h2>任务管理</h2>
    </div>

    <div class="toolbar">
      <el-select v-model="type" clearable placeholder="任务类型" style="width: 130px" @change="search">
        <el-option label="扫描" value="scan" />
        <el-option label="刮削" value="scrape" />
        <el-option label="整理" value="organize" />
        <el-option label="流水线" value="pipeline" />
      </el-select>
      <el-select v-model="status" clearable placeholder="任务状态" style="width: 130px" @change="search">
        <el-option label="运行中" value="running" />
        <el-option label="已完成" value="completed" />
        <el-option label="失败" value="failed" />
        <el-option label="已取消" value="cancelled" />
      </el-select>
      <el-button :icon="SearchIcon" @click="search">查询</el-button>
      <span class="poll-hint">
        <el-tag v-if="polling" type="primary" size="small">自动刷新中</el-tag>
      </span>
    </div>

    <el-table empty-text="暂无数据" :data="items" v-loading="loading" border stripe>
      <el-table-column align="center" prop="id" label="ID" width="70" show-overflow-tooltip />
      <el-table-column label="类型" width="90" align="center">
        <template #default="{ row }">
          <el-tag size="small">{{ jobTypeLabels[row.type as JobType] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="92" align="center">
        <template #default="{ row }">
          <el-tag :type="jobStatusTagTypes[row.status as JobStatus]" size="small">
            {{ jobStatusLabels[row.status as JobStatus] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column align="center" label="进度" min-width="180">
        <template #default="{ row }">
          <el-progress
            :percentage="progressOf(row)"
            :status="row.status === 'failed' ? 'exception' : row.status === 'completed' ? 'success' : undefined"
          />
        </template>
      </el-table-column>
      <el-table-column align="center" label="明细" width="170">
        <template #default="{ row }">
          完成 {{ row.doneItems }} / {{ row.totalItems }}
          <span v-if="row.failItems > 0" class="fail">失败 {{ row.failItems }}</span>
        </template>
      </el-table-column>
      <el-table-column align="center" label="开始时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.startedAt) }}</template>
      </el-table-column>
      <el-table-column align="center" label="耗时" width="100">
        <template #default="{ row }">{{ formatDuration(row.startedAt, row.finishedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" align="center">
        <template #default="{ row }">
          <el-button icon="View" type="primary" plain size="small" class="op-btn" @click="openDetail(row)">
            详情
          </el-button>
          <el-button
            v-if="row.status === 'running'"
            type="danger"
            plain
            size="small"
            class="op-btn"
            :loading="stoppingId === row.id"
            @click="stop(row)"
          >
            停止
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        background
        @current-change="load"
      />
    </div>

    <el-drawer v-model="detailVisible" size="560px" :title="`任务 #${detail?.id ?? ''}`">
      <el-skeleton v-if="!detail" :rows="6" animated />
      <template v-else>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="类型">{{ jobTypeLabels[detail.type] }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="jobStatusTagTypes[detail.status]" size="small">
              {{ jobStatusLabels[detail.status] }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ formatDateTime(detail.startedAt) }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ formatDateTime(detail.finishedAt) }}</el-descriptions-item>
          <el-descriptions-item label="完成 / 总数">
            {{ detail.doneItems }} / {{ detail.totalItems }}
          </el-descriptions-item>
          <el-descriptions-item label="失败数">{{ detail.failItems }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="section-title">任务参数</h4>
        <pre class="params">{{ JSON.stringify(detail.params, null, 2) }}</pre>

        <h4 class="section-title">失败明细（{{ detail.errors.length }}）</h4>
        <el-empty v-if="detail.errors.length === 0" description="无失败记录" :image-size="60" />
        <el-table empty-text="暂无数据" v-else :data="detail.errors" size="small" border>
          <el-table-column align="center" prop="item" label="失败项" min-width="150" show-overflow-tooltip />
          <el-table-column align="center" prop="reason" label="原因" min-width="180" show-overflow-tooltip />
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Search as SearchIcon } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import type { Job, JobStatus, JobType } from '../api/types'
import {
  formatDateTime,
  formatDuration,
  jobStatusLabels,
  jobStatusTagTypes,
  jobTypeLabels,
} from '../utils'

const type = ref<JobType | ''>('')
const status = ref<JobStatus | ''>('')
const page = ref(1)
const size = ref(20)
const total = ref(0)
const items = ref<Job[]>([])
const loading = ref(false)

const detailVisible = ref(false)
const detail = ref<Job | null>(null)
const stoppingId = ref<number | null>(null)

const polling = computed(() => items.value.some((j) => j.status === 'running'))

function progressOf(row: Job): number {
  if (row.totalItems <= 0) return row.status === 'completed' ? 100 : 0
  return Math.min(100, Math.floor((row.doneItems / row.totalItems) * 100))
}

function search() {
  page.value = 1
  load()
}

async function load() {
  loading.value = true
  try {
    const result = await api.listJobs({
      page: page.value,
      size: size.value,
      type: type.value || undefined,
      status: status.value || undefined,
    })
    items.value = result.items
    total.value = result.total
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

function openDetail(job: Job) {
  detail.value = job
  detailVisible.value = true
}

/** 停止运行中的任务（协作式取消：当前条目执行完后终止） */
async function stop(job: Job) {
  try {
    await ElMessageBox.confirm(
      `停止任务 #${job.id}？正在执行的条目会执行完，后续条目不再处理。`,
      '停止任务',
      { type: 'warning', confirmButtonText: '停止', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  stoppingId.value = job.id
  try {
    await api.stopJob(job.id)
    ElMessage.success(`已发送停止请求，任务 #${job.id} 将在当前条目完成后终止`)
    load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    stoppingId.value = null
  }
}

let timer: number | undefined

onMounted(() => {
  load()
  timer = window.setInterval(() => {
    if (polling.value) load()
  }, 2000)
})

onUnmounted(() => {
  if (timer !== undefined) window.clearInterval(timer)
})
</script>

<style scoped>
.poll-hint {
  margin-left: 8px;
}
/* 操作栏按钮：plain 样式保证文字与底色有足够对比度（原 link 样式在玻璃主题下文字与底色几乎同色） */
.op-btn.el-button--primary {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9, rgba(91, 110, 225, 0.12));
  border-color: var(--el-color-primary-light-5, rgba(91, 110, 225, 0.4));
}
.op-btn.el-button--danger {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9, rgba(217, 72, 72, 0.12));
  border-color: var(--el-color-danger-light-5, rgba(217, 72, 72, 0.4));
}
.op-btn:hover {
  opacity: 1;
  filter: brightness(0.96);
}
.op-btn:active {
  filter: brightness(0.9);
}
.fail {
  color: var(--el-color-danger);
  margin-left: 6px;
}
.params {
  background: var(--glass-bg-soft);
  backdrop-filter: var(--glass-blur-light);
  -webkit-backdrop-filter: var(--glass-blur-light);
  border: 1px solid var(--glass-border-soft);
  border-radius: var(--glass-radius-sm);
  padding: 12px;
  font-size: 12px;
  line-height: 1.6;
  overflow: auto;
  max-height: 220px;
  margin: 0;
}
</style>
