<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import AuthCard from '@/components/auth/AuthCard.vue'
import { ElMessage } from 'element-plus'

const router = useRouter()
const authStore = useAuthStore()
const activeTab = ref<'login' | 'register'>('register')
const heroReady = ref(false)

onMounted(() => {
  authStore.initAuth()
  requestAnimationFrame(() => {
    requestAnimationFrame(() => { heroReady.value = true })
  })
})

async function handleSubmit(data: { username: string; password: string; confirmPassword?: string }) {
  if (activeTab.value === 'login') {
    if (!data.username || !data.password) { ElMessage.warning('请填写用户名和密码'); return }
    await authStore.login(data)
    if (authStore.isLoggedIn) router.push('/chat')
    else if (authStore.error) ElMessage.error(authStore.error)
  } else {
    if (!data.username || !data.password) { ElMessage.warning('请填写用户名和密码'); return }
    if (data.password !== data.confirmPassword) { ElMessage.warning('两次密码输入不一致'); return }
    await authStore.register({ username: data.username, password: data.password })
    if (!authStore.error) { ElMessage.success('注册成功，请登录'); activeTab.value = 'login' }
    else ElMessage.error(authStore.error)
  }
}
</script>

<template>
  <div class="auth-page">
    <section class="auth-hero" :class="{ 'is-ready': heroReady }">
      <div class="brand">
        <div class="brand-mark">D</div>
        <div>
          <div class="brand-name">DART</div>
          <div class="brand-sub">双 Agent 智能检索与知识整理系统</div>
        </div>
      </div>
      <h1 class="hero-title">开始构建你的<br/>个人技术知识库</h1>
      <p class="hero-desc">注册 DART，让每一次技术学习都变得高效、结构化、可回顾。</p>
      <div class="hero-features">
        <div class="hero-feature">
          <div class="hero-feature-mark">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 21l-6-6m2-5a7 7 0 1 1-14 0 7 7 0 0 1 14 0z"/></svg>
          </div>
          双 Agent 协作搜索
        </div>
        <div class="hero-feature">
          <div class="hero-feature-mark">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"/></svg>
          </div>
          T/A/D/M 四维质量评分
        </div>
        <div class="hero-feature">
          <div class="hero-feature-mark">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20M4 4v15.5A2.5 2.5 0 0 1 6.5 22H20V4a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2z"/></svg>
          </div>
          个人知识库与 RAG 追问
        </div>
      </div>
    </section>
    <section class="auth-form-side">
      <AuthCard :active-tab="activeTab" @switch-tab="(t: 'login' | 'register') => activeTab = t" @submit="handleSubmit" />
    </section>
  </div>
</template>

<style lang="scss" scoped>
.auth-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(320px, 460px);
  background: $color-bg;
}
.auth-hero {
  display: flex; flex-direction: column; justify-content: center;
  padding: $gap-xl;
  background: $color-accent-soft;
  > * { opacity: 0; transform: translateY(18px); transition: opacity $duration-enter $ease-out, transform $duration-enter $ease-out; }
  &.is-ready > * { opacity: 1; transform: translateY(0); }
  &.is-ready > *:nth-child(1) { transition-delay: calc($stagger * 0); }
  &.is-ready > *:nth-child(2) { transition-delay: calc($stagger * 1); }
  &.is-ready > *:nth-child(3) { transition-delay: calc($stagger * 2); }
  &.is-ready > *:nth-child(4) { transition-delay: calc($stagger * 3); }
}
.brand { display: flex; align-items: center; gap: $gap-md; margin-bottom: $gap-xl; }
.brand-mark {
  width: 56px; height: 56px; display: grid; place-items: center;
  background: $color-accent; color: white;
  border-radius: $radius-lg; font-family: $font-mono; font-size: 24px; font-weight: 700;
}
.brand-name { font-family: $font-display; font-size: 32px; font-weight: 700; }
.brand-sub { font-size: 16px; color: $color-muted; margin-top: 6px; }
.hero-title { font-family: $font-display; font-size: 36px; font-weight: 700; line-height: 1.2; max-width: 22ch; margin-bottom: $gap-md; }
.hero-desc { font-size: 17px; color: $color-muted; line-height: 1.7; max-width: 42ch; }
.hero-features { margin-top: $gap-xl; display: flex; flex-direction: column; gap: $gap-md; }
.hero-feature { display: flex; align-items: center; gap: $gap-md; font-size: 15px; }
.hero-feature-mark {
  width: 28px; height: 28px; display: grid; place-items: center;
  background: white; border: 1px solid $color-border; border-radius: 50%;
  color: $color-accent; flex-shrink: 0;
  svg { width: 14px; height: 14px; }
}
.auth-form-side { display: flex; align-items: center; justify-content: center; padding: $gap-xl; background: $color-surface; }
@media (max-width: 920px) {
  .auth-page { grid-template-columns: 1fr; }
  .auth-hero { display: none; }
}
</style>
