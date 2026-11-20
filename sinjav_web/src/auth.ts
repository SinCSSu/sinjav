/** 登录态标记（localStorage）：仅作前端路由跳转用，真实鉴权由后端会话承担 */

const FLAG = 'sinjav.auth.user'

export function currentUsername(): string | null {
  return localStorage.getItem(FLAG)
}

export function isLoggedIn(): boolean {
  return localStorage.getItem(FLAG) != null
}

export function setLoggedIn(username: string): void {
  localStorage.setItem(FLAG, username)
}

export function setLoggedOut(): void {
  localStorage.removeItem(FLAG)
}

/** 未登录跳转（携带原路径，登录后回跳）：hash 路由直接改 location，避免 api 与 router 循环依赖 */
export function redirectToLogin(): void {
  setLoggedOut()
  if (location.hash.startsWith('#/login')) return
  const current = location.hash.replace(/^#/, '')
  location.hash = current ? `#/login?redirect=${encodeURIComponent(current)}` : '#/login'
}
