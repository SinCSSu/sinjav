<template>
  <div class="auth-page" data-wl-ui-skin="off">
    <div class="ambient ambient-a" />
    <div class="ambient ambient-b" />
    <div class="ambient-ring" />

    <div class="auth-shell">
      <!-- 品牌侧：纯装饰渐变栏，桌面端展示，移动端隐藏 -->
      <section class="brand-panel">
        <div class="brand-orb" />
      </section>

      <!-- 表单侧 -->
      <section class="form-panel">
        <div class="auth-card">
          <div class="card-glow" />
          <div class="mode-badge" :class="mode">
            <el-icon :size="20"><component :is="modeIcon" /></el-icon>
          </div>
          <h2 class="title">{{ mode === 'init' ? '创建管理员账号' : '登录' }}</h2>

          <!-- 状态检查中：避免登录表单闪一下再切成初始化向导 -->
          <div v-if="checking" class="checking">
            <el-icon class="spin" :size="22"><Loading /></el-icon>
            <span>正在检查登录状态…</span>
          </div>

          <el-form v-else class="auth-form" @submit.prevent>
            <div class="field">
              <label>用户名</label>
              <el-input v-model="username" placeholder="请输入用户名" size="large" :prefix-icon="User" />
            </div>
            <div class="field">
              <label>密码</label>
              <el-input
                v-model="password"
                type="password"
                show-password
                :placeholder="mode === 'init' ? '至少 6 位' : '请输入密码'"
                size="large"
                :prefix-icon="Lock"
                @keyup.enter="submit"
              />
              <div v-if="mode === 'init' && password" class="strength">
                <span class="strength-bars">
                  <i v-for="n in 3" :key="n" :class="{ on: n <= strength, [`lv-${strength}`]: n <= strength }" />
                </span>
                <span class="strength-label" :class="`lv-${strength}`">{{ strengthLabel }}</span>
              </div>
            </div>
            <div v-if="mode === 'init'" class="field">
              <label>确认密码</label>
              <el-input
                v-model="confirm"
                type="password"
                show-password
                placeholder="再输入一次密码"
                size="large"
                :prefix-icon="Key"
                @keyup.enter="submit"
              />
              <div v-if="confirm" class="match-hint" :class="{ ok: password === confirm }">
                <el-icon :size="13"><CircleCheck /></el-icon>
                {{ password === confirm ? '一致' : '密码不一致' }}
              </div>
            </div>

            <transition name="fade">
              <div v-if="formError" class="form-error">
                <el-icon :size="14"><WarningFilled /></el-icon>{{ formError }}
              </div>
            </transition>

            <el-button type="primary" size="large" class="submit" :loading="busy" @click="submit">
              <span>{{ mode === 'init' ? '创建账号并进入' : '登录' }}</span>
              <el-icon v-if="!busy" class="btn-arrow" :size="16"><ArrowRight /></el-icon>
            </el-button>
          </el-form>
        </div>
      </section>
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  ArrowRight, CircleCheck, Key, Loading, Lock,
  User, WarningFilled,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { setLoggedIn } from '../auth'

const route = useRoute()
const mode = ref<'login' | 'init'>('login')
const checking = ref(true)
const username = ref('')
const password = ref('')
const confirm = ref('')
const busy = ref(false)
const formError = ref('')

const modeIcon = computed(() => (mode.value === 'init' ? Key : Lock))
const strength = computed(() => {
  const p = password.value
  if (!p) return 0
  let score = 0
  if (p.length >= 8) score++
  if (/[A-Za-z]/.test(p) && /[0-9]/.test(p)) score++
  if (/[^A-Za-z0-9]/.test(p) || p.length >= 12) score++
  return Math.max(1, Math.min(3, score))
})
const strengthLabel = computed(() => ['', '弱', '中', '强'][strength.value])

// 用户重新输入时清除行内校验提示
watch([username, password, confirm], () => { formError.value = '' })

onMounted(async () => {
  try {
    const st = await api.authStatus()
    mode.value = st.initialized ? 'login' : 'init'
    if (st.initialized && st.username) username.value = st.username
  } catch {
    mode.value = 'login'
  } finally {
    checking.value = false
  }
})

async function submit() {
  if (checking.value || busy.value) return
  if (!username.value.trim() || !password.value) {
    formError.value = '请输入用户名和密码'
    return
  }
  if (mode.value === 'init') {
    if (password.value.length < 6) {
      formError.value = '密码至少需要 6 位'
      return
    }
    if (password.value !== confirm.value) {
      formError.value = '两次输入的密码不一致'
      return
    }
  }
  busy.value = true
  try {
    const res = mode.value === 'init'
      ? await api.authInit(username.value.trim(), password.value)
      : await api.authLogin(username.value.trim(), password.value)
    setLoggedIn(res.username)
    ElMessage.success(mode.value === 'init' ? '账号创建成功' : '登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    location.hash = `#${redirect.startsWith('/') ? redirect : `/${redirect}`}`
  } catch (e) {
    formError.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}
</script>
<style scoped>
.auth-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  overflow: hidden;
}
.ambient {
  position: absolute;
  border-radius: 50%;
  filter: blur(70px);
  pointer-events: none;
  animation: float 16s ease-in-out infinite alternate;
}
.ambient-a {
  width: 420px;
  height: 420px;
  left: -60px;
  top: -40px;
  background: radial-gradient(circle, rgba(122, 162, 255, 0.55) 0%, transparent 70%);
}
.ambient-b {
  width: 460px;
  height: 460px;
  right: -80px;
  bottom: -100px;
  background: radial-gradient(circle, rgba(255, 168, 210, 0.5) 0%, transparent 70%);
  animation-delay: -8s;
}
.ambient-ring {
  position: absolute;
  width: 560px;
  height: 560px;
  border: 1px solid rgba(255, 255, 255, 0.35);
  border-radius: 50%;
  right: 8%;
  top: -220px;
  pointer-events: none;
}
.auth-shell {
  position: relative;
  z-index: 1;
  display: grid;
  /* 黄金分割：左右半区 38.2% / 61.8%，整卡宽高 1.618:1 */
  grid-template-columns: 382fr 618fr;
  aspect-ratio: 1618 / 1000;
  min-height: 560px;
  max-width: calc(100vw - 48px);
  border-radius: 24px;
  overflow: hidden;
  border: 1px solid var(--glass-border);
  background: var(--glass-bg-soft);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  box-shadow: 0 24px 70px rgba(60, 80, 150, 0.22);
  animation: rise 0.55s cubic-bezier(0.2, 0.8, 0.2, 1) both;
}
.brand-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  padding: 40px 38px;
  color: #fff;
  overflow: hidden;
  background: linear-gradient(150deg, #5b6ee1 0%, #7b6fe0 55%, #9b7ad8 100%);
}
.brand-panel::before {
  content: '';
  position: absolute;
  width: 300px;
  height: 300px;
  border-radius: 50%;
  left: -150px;
  top: -130px;
  background: rgba(255, 255, 255, 0.12);
}
.brand-orb {
  position: absolute;
  width: 280px;
  height: 280px;
  border-radius: 50%;
  right: -110px;
  bottom: -90px;
  border: 1px solid rgba(255, 255, 255, 0.28);
  background: rgba(255, 255, 255, 0.1);
  animation: float 12s ease-in-out infinite alternate;
}
.form-panel {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 36px;
  background: rgba(255, 255, 255, 0.52);
}
.auth-card { position: relative; width: 100%; }
.card-glow {
  position: absolute;
  right: -40px;
  top: -60px;
  width: 180px;
  height: 180px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(91, 110, 225, 0.16) 0%, transparent 70%);
  pointer-events: none;
}
.mode-badge {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: linear-gradient(135deg, #5b6ee1, #8b98ec);
  box-shadow: 0 8px 18px rgba(91, 110, 225, 0.3);
}
.mode-badge.init { background: linear-gradient(135deg, #7b6fe0, #a98ce4); }
.title {
  font-size: 25px;
  font-weight: 700;
  margin: 20px 0 26px;
  color: var(--el-text-color-primary);
}
.field { margin-bottom: 18px; }
.field label {
  display: block;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-regular);
  margin-bottom: 8px;
}
:deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.72) !important;
  border-radius: 12px !important;
  padding: 2px 12px;
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.9) inset, 0 2px 8px rgba(60, 80, 150, 0.06) !important;
  transition: box-shadow 0.2s, background 0.2s, transform 0.2s;
}
:deep(.el-input__wrapper.is-focus) {
  background: #fff !important;
  box-shadow: 0 0 0 2px rgba(91, 110, 225, 0.35) inset, 0 4px 14px rgba(91, 110, 225, 0.12) !important;
}
:deep(.el-input__inner) { height: 42px; font-size: 14px; }
.strength { display: flex; align-items: center; gap: 10px; margin-top: 9px; }
.strength-bars { display: flex; gap: 4px; flex: 1; }
.strength-bars i {
  flex: 1;
  height: 4px;
  border-radius: 3px;
  background: rgba(120, 130, 165, 0.18);
  transition: background 0.25s;
}
.strength-bars i.on.lv-1 { background: var(--el-color-warning); }
.strength-bars i.on.lv-2 { background: var(--el-color-primary); }
.strength-bars i.on.lv-3 { background: var(--el-color-success); }
.strength-label { font-size: 12px; flex-shrink: 0; }
.strength-label.lv-1 { color: var(--el-color-warning); }
.strength-label.lv-2 { color: var(--el-color-primary); }
.strength-label.lv-3 { color: var(--el-color-success); }
.match-hint {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-color-danger);
}
.match-hint.ok { color: var(--el-color-success); }
.form-error {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 9px 12px;
  margin-bottom: 14px;
  border-radius: 10px;
  font-size: 12px;
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  border: 1px solid var(--el-color-danger-light-7);
}
.submit {
  width: 100%;
  height: 46px;
  margin-top: 20px;
  border: none;
  border-radius: 12px;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.5px;
  background: linear-gradient(100deg, #5b6ee1, #8b7ce0);
  box-shadow: 0 8px 20px rgba(91, 110, 225, 0.32);
  transition: transform 0.2s, box-shadow 0.2s;
}
.submit:hover {
  transform: translateY(-2px);
  box-shadow: 0 12px 26px rgba(91, 110, 225, 0.42);
}
.submit:active {
  transform: translateY(0);
  box-shadow: 0 6px 14px rgba(91, 110, 225, 0.3);
}
.btn-arrow { margin-left: 8px; }
.checking {
  height: 220px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.spin { color: var(--el-color-primary); animation: spin 1s linear infinite; }
.fade-enter-active, .fade-leave-active { transition: opacity 0.2s, transform 0.2s; }
.fade-enter-from, .fade-leave-to { opacity: 0; transform: translateY(-4px); }
@keyframes rise {
  from { opacity: 0; transform: translateY(18px) scale(0.98); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}
@keyframes float {
  from { transform: translate3d(0, 0, 0); }
  to { transform: translate3d(24px, -28px, 0); }
}
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 900px) {
  .auth-shell { grid-template-columns: 1fr; aspect-ratio: auto; max-width: 440px; width: 100%; }
  .brand-panel { display: none; }
  .form-panel { padding: 36px 28px 28px; }
  .ambient-ring { display: none; }
}
@media (max-width: 480px) {
  .auth-page { padding: 12px; }
  .form-panel { padding: 30px 22px 24px; }
  .title { font-size: 22px; }
}
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation: none !important; transition: none !important; }
}
</style>
