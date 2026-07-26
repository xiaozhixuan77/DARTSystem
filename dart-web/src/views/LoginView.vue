<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import AuthCard from '@/components/auth/AuthCard.vue'
import { ElMessage } from 'element-plus'

const router = useRouter()
const authStore = useAuthStore()
const activeTab = ref<'login' | 'register'>('login')
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
      <h1 class="hero-title">让技术栈迁移<br/>学习更聚焦</h1>
      <p class="hero-desc">Search Agent 负责全网搜索，Curator Agent 负责质量评分与知识整理。把零散的技术资源变成可追问的个人知识库。</p>
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
@import '@/assets/styles/auth.scss';
</style>
