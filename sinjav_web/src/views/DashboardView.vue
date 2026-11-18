<template>
  <div>
    <div class="page-header">
      <h2>仪表盘</h2>
      <div class="toolbar" style="margin-bottom: 0">
        <el-button type="primary" @click="showScan = true">
          <el-icon><Search /></el-icon>扫描
        </el-button>
        <el-button type="primary" @click="showScrape = true">
          <el-icon><Download /></el-icon>刮削
        </el-button>
        <el-button type="primary" @click="showOrganize = true">
          <el-icon><FolderChecked /></el-icon>整理
        </el-button>
        <el-button type="warning" @click="showPipeline = true">
          <el-icon><VideoPlay /></el-icon>流水线
        </el-button>
      </div>
    </div>

    <el-row :gutter="16">
      <el-col v-for="card in statCards" :key="card.label" :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-value">{{ card.value }}</div>
          <div class="stat-label">{{ card.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <h3 class="section-title">
      运行中任务
      <el-tag v-if="runningJobs.length" type="primary" size="small">{{ runningJobs.length }}</el-tag>
    </h3>
    <el-empty v-if="runningJobs.length === 0" description="暂无运行中的任务" :image-size="80" />
    <el-row v-else :gutter="16">
      <el-col v-for="job in runningJobs" :key="job.id" :span="8">
        <el-card shadow="never" class="job-card" @click="router.push('/jobs')">
          <div class="job-head">
            <span>{{ jobTypeLabels[job.type] }} #{{ job.id }}</span>
            <el-tag type="primary" size="small">运行中</el-tag>
          </div>
          <el-progress
            :percentage="job.totalItems > 0 ? Math.floor((job.doneItems / job.totalItems) * 100) : 0"
          />
          <div class="job-meta">
            {{ job.doneItems }} / {{ job.totalItems }}
            <span v-if="job.failItems > 0" class="fail">（失败 {{ job.failItems }}）</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <ScanDialog v-model="showScan" @created="loadAll" />
    <ScrapeDialog v-model="showScrape" @created="loadAll" />
    <OrganizeDialog v-model="showOrganize" @created="loadAll" />
    <PipelineDialog v-model="showPipeline" @created="loadAll" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import type { Job } from '../api/types'
import { jobTypeLabels } from '../utils'
import ScanDialog from '../components/ScanDialog.vue'
import ScrapeDialog from '../components/ScrapeDialog.vue'
import OrganizeDialog from '../components/OrganizeDialog.vue'
import PipelineDialog from '../components/PipelineDialog.vue'

const router = useRouter()

const stats = reactive({ pending: 0, scraped: 0, failed: 0, total: 0 })
const runningJobs = ref<Job[]>([])
const showScan = ref(false)
const showScrape = ref(false)
const showOrganize = ref(false)
const showPipeline = ref(false)

const statCards = computed(() => [
  { label: '影片总数', value: stats.total },
  { label: '待刮削', value: stats.pending },
  { label: '已刮削', value: stats.scraped },
  { label: '刮削失败', value: stats.failed },
])

async function loadStats() {
  try {
    const [pending, scraped, failed] = await Promise.all([
      api.listMovies({ page: 1, size: 1, status: 'pending' }),
      api.listMovies({ page: 1, size: 1, status: 'scraped' }),
      api.listMovies({ page: 1, size: 1, status: 'failed' }),
    ])
    stats.pending = pending.total
    stats.scraped = scraped.total
    stats.failed = failed.total
    stats.total = pending.total + scraped.total + failed.total
  } catch (e) {
    console.error(e)
  }
}

async function loadRunning() {
  try {
    const page = await api.listJobs({ page: 1, size: 20, status: 'running' })
    runningJobs.value = page.items
  } catch (e) {
    console.error(e)
  }
}

function loadAll() {
  loadStats()
  loadRunning()
}

let timer: number | undefined

onMounted(() => {
  loadAll()
  timer = window.setInterval(loadAll, 3000)
})

onUnmounted(() => {
  if (timer !== undefined) window.clearInterval(timer)
})
</script>

<style scoped>
.stat-card {
  text-align: center;
}
.stat-value {
  font-size: 28px;
  font-weight: 600;
}
.stat-label {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  margin: 24px 0 12px;
}
.job-card {
  cursor: pointer;
}
.job-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.job-meta {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.fail {
  color: var(--el-color-danger);
}
</style>
