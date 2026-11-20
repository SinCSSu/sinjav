<template>
  <!-- 公开页（登录）：整页渲染，无侧栏/顶栏 -->
  <el-container v-if="route.meta.public" class="layout">
    <el-main class="main public-main">
      <router-view />
    </el-main>
  </el-container>
  <el-container v-else class="layout">
    <el-aside width="200px" class="sidebar">
      <div class="logo">
        <el-icon :size="22"><VideoCamera /></el-icon>
        <span>sinJav</span>
      </div>
      <el-menu :default-active="route.path" router class="menu">
        <el-menu-item index="/dashboard">
          <el-icon><Odometer /></el-icon>
          <span>仪表盘</span>
        </el-menu-item>
        <el-menu-item index="/movies">
          <el-icon><Film /></el-icon>
          <span>影片库</span>
        </el-menu-item>
        <el-menu-item index="/file-records">
          <el-icon><Document /></el-icon>
          <span>文件记录</span>
        </el-menu-item>
        <el-menu-item index="/jobs">
          <el-icon><List /></el-icon>
          <span>任务管理</span>
        </el-menu-item>
        <el-menu-item index="/settings">
          <el-icon><Setting /></el-icon>
          <span>设置</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container class="body-col">
      <el-header class="topbar" height="52px">
        <span />
        <el-dropdown v-if="username" trigger="click">
          <span class="user-chip">
            <el-icon :size="18"><User /></el-icon>
            {{ username }}
            <el-icon :size="14" class="caret"><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api } from './api'
import { isLoggedIn, setLoggedOut } from './auth'

const route = useRoute()
const username = ref<string | null>(null)

// 进入受保护页面时加载/刷新当前用户名（登录成功后从公开页切回时也会触发）
watch(
  () => route.meta.public,
  async (pub) => {
    if (pub || !isLoggedIn()) return
    try {
      const st = await api.authStatus()
      username.value = st.username ?? null
    } catch {
      // 未登录由路由守卫处理
    }
  },
  { immediate: true },
)

async function logout() {
  try {
    await api.authLogout()
  } catch {
    // 会话可能已失效，忽略
  }
  setLoggedOut()
  location.hash = '#/login'
}
</script>

<style scoped>
.layout {
  height: 100vh;
}
.body-col {
  min-width: 0;
}
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 8px;
}
.user-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  padding: 7px 14px;
  border-radius: var(--el-border-radius-round);
  background: var(--glass-bg-soft);
  border: 1px solid var(--glass-border-soft);
  transition: background 0.2s, box-shadow 0.2s;
}
.user-chip:hover {
  background: var(--glass-accent);
  box-shadow: var(--glass-shadow);
}
.user-chip .caret {
  color: var(--el-text-color-secondary);
}
.public-main {
  padding: 0;
}
.sidebar {
  margin: 12px 0 12px 12px;
  padding: 8px;
  border-radius: var(--glass-radius);
  border: 1px solid var(--glass-border);
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  box-shadow: var(--glass-shadow);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 60px;
  padding: 0 16px;
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 0.4px;
  color: var(--el-color-primary);
}
.menu {
  border-right: none;
  flex: 1;
  background: transparent;
}
.menu :deep(.el-menu-item) {
  border-radius: var(--glass-radius-sm);
  margin: 3px 0;
  height: 44px;
  transition: background 0.18s ease;
}
.menu :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.55);
}
.menu :deep(.el-menu-item.is-active) {
  background: var(--glass-accent);
  font-weight: 600;
}
.main {
  overflow-y: auto;
  background: transparent;
  padding: 20px 22px;
}
@media (max-width: 900px) {
  .main {
    padding: 12px;
  }
}
</style>
