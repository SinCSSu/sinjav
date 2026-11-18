<template>
  <el-container class="layout">
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
    <el-main class="main">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<style scoped>
.layout {
  height: 100vh;
}
.sidebar {
  margin: 12px 0 12px 12px;
  border-radius: 16px;
  border: 1px solid rgba(255, 255, 255, 0.65);
  background: rgba(255, 255, 255, 0.55);
  backdrop-filter: blur(18px) saturate(150%);
  -webkit-backdrop-filter: blur(18px) saturate(150%);
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.12);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 56px;
  padding: 0 20px;
  font-size: 18px;
  font-weight: 600;
  color: var(--el-color-primary);
}
.menu {
  border-right: none;
  flex: 1;
  background: transparent;
}
.main {
  overflow-y: auto;
  background: transparent;
}
</style>
