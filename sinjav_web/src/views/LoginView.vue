<template>
  <div class="login-wrap">
    <div class="login-card">
      <div class="logo">
        <el-icon :size="26"><VideoCamera /></el-icon>
        <span>sinJav</span>
      </div>
      <p class="title">{{ mode === 'init' ? '初始化管理员账号' : '登录' }}</p>
      <p v-if="mode === 'init'" class="sub">首次使用：请设置管理员用户名与密码</p>
      <el-form @submit.prevent>
        <el-form-item>
          <el-input v-model="username" placeholder="用户名" size="large" :prefix-icon="User" />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="password"
            type="password"
            show-password
            :placeholder="mode === 'init' ? '密码（至少 6 位）' : '密码'"
            size="large"
            :prefix-icon="Lock"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-form-item v-if="mode === 'init'">
          <el-input
            v-model="confirm"
            type="password"
            show-password
            placeholder="确认密码"
            size="large"
            :prefix-icon="Lock"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" size="large" class="submit" :loading="busy" @click="submit">
          {{ mode === 'init' ? '创建账号' : '登录' }}
        </el-button>
      </el-form>
    </div>
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { User, Lock, VideoCamera } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { setLoggedIn } from '../auth'

const route = useRoute()
const mode = ref<'login' | 'init'>('login')
const username = ref('')
const password = ref('')
const confirm = ref('')
const busy = ref(false)

onMounted(async () => {
  try {
    const st = await api.authStatus()
    mode.value = st.initialized ? 'login' : 'init'
    if (st.initialized && st.username) username.value = st.username
  } catch {
    mode.value = 'login'
  }
})

async function submit() {
  if (!username.value.trim() || !password.value) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  if (mode.value === 'init' && password.value !== confirm.value) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }
  busy.value = true
  try {
    const res = mode.value === 'init'
      ? await api.authInit(username.value.trim(), password.value)
      : await api.authLogin(username.value.trim(), password.value)
    setLoggedIn(res.username)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    location.hash = `#${redirect.startsWith('/') ? redirect : `/${redirect}`}`
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
.login-card {
  width: 360px;
  padding: 36px 32px;
  border-radius: var(--glass-radius);
  border: 1px solid var(--glass-border);
  background: var(--glass-bg);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  box-shadow: var(--glass-shadow);
}
.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 22px;
  font-weight: 700;
  color: var(--el-color-primary);
  justify-content: center;
}
.title {
  text-align: center;
  font-size: 16px;
  margin: 18px 0 6px;
}
.sub {
  text-align: center;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 0 0 8px;
}
.submit {
  width: 100%;
}
</style>
