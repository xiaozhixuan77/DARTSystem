<script setup lang="ts">
import { ref, onMounted } from 'vue'

defineProps<{ activeTab: 'login' | 'register' }>()
const emit = defineEmits<{
  switchTab: [tab: 'login' | 'register']
  submit: [data: { username: string; password: string; confirmPassword?: string }]
}>()

const loginForm = ref({ username: '', password: '' })
const regForm = ref({ username: '', password: '', confirmPassword: '' })
const cardReady = ref(false)

onMounted(() => {
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      cardReady.value = true
    })
  })
})

function handleLoginSubmit() {
  emit('submit', { ...loginForm.value })
}

function handleRegSubmit() {
  emit('submit', { ...regForm.value })
}
</script>

<template>
  <div class="auth-card" :class="{ 'is-ready': cardReady }">
    <div class="auth-tabs">
      <button
        class="auth-tab"
        :class="{ active: activeTab === 'login' }"
        @click="emit('switchTab', 'login')"
      >登录</button>
      <button
        class="auth-tab"
        :class="{ active: activeTab === 'register' }"
        @click="emit('switchTab', 'register')"
      >注册</button>
    </div>
    <div class="form-stage">
      <!-- 登录表单 -->
      <form
        v-show="activeTab === 'login'"
        class="auth-form"
        :class="{ active: activeTab === 'login' }"
        @submit.prevent="handleLoginSubmit"
      >
        <div class="form-field">
          <label for="loginUser">用户名</label>
          <input id="loginUser" v-model="loginForm.username" type="text" placeholder="请输入用户名" />
        </div>
        <div class="form-field">
          <label for="loginPass">密码</label>
          <input id="loginPass" v-model="loginForm.password" type="password" placeholder="请输入密码" />
        </div>
        <button type="submit" class="submit-btn">登 录</button>
        <div class="form-footer">
          还没有账号？<a href="#" @click.prevent="emit('switchTab', 'register')">去注册</a>
        </div>
      </form>

      <!-- 注册表单 -->
      <form
        v-show="activeTab === 'register'"
        class="auth-form"
        :class="{ active: activeTab === 'register' }"
        @submit.prevent="handleRegSubmit"
      >
        <div class="form-field">
          <label for="regUser">用户名</label>
          <input id="regUser" v-model="regForm.username" type="text" placeholder="请输入用户名" />
        </div>
        <div class="form-field">
          <label for="regPass">密码</label>
          <input id="regPass" v-model="regForm.password" type="password" placeholder="请输入密码" />
        </div>
        <div class="form-field">
          <label for="regPass2">确认密码</label>
          <input id="regPass2" v-model="regForm.confirmPassword" type="password" placeholder="请再次输入密码" />
        </div>
        <button type="submit" class="submit-btn">注 册</button>
        <div class="form-footer">
          已有账号？<a href="#" @click.prevent="emit('switchTab', 'login')">去登录</a>
        </div>
      </form>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.auth-card {
  width: 100%;
  max-width: 360px;
}
.auth-tabs {
  display: flex;
  gap: $gap-lg;
  margin-bottom: $gap-xl;
  border-bottom: 1px solid $color-border;
}
.auth-tab {
  padding-bottom: 12px;
  font-size: 18px;
  font-weight: 600;
  color: $color-muted;
  position: relative;
  transition: color $duration-fast ease;
  border: none;
  background: none;
  cursor: pointer;
  &:hover { color: $color-fg; }
  &.active { color: $color-fg; }
  &.active::after {
    content: '';
    position: absolute; bottom: -1px; left: 0; right: 0;
    height: 2px; background: $color-accent; border-radius: 2px;
  }
}
.auth-form {
  display: flex;
  flex-direction: column;
  gap: $gap-md;
}
.form-field {
  label {
    display: block;
    margin-bottom: 6px;
    font-size: 13px; color: $color-muted;
  }
  input {
    width: 100%;
    padding: 11px 14px;
    border: 1px solid $color-border;
    border-radius: $radius;
    background: $color-surface;
    color: $color-fg;
    font: inherit;
    font-size: 15px;
    outline: none;
    transition: border-color $duration-fast ease;
    &:focus { border-color: $color-accent; box-shadow: 0 0 0 3px $color-accent-soft; }
  }
}
.submit-btn {
  width: 100%;
  padding: 12px;
  background: $color-accent; color: $color-surface;
  border-radius: $radius;
  font-size: 15px; font-weight: 500;
  border: none;
  cursor: pointer;
  transition: background $duration-fast ease;
  &:hover { background: $color-accent-dark; }
}
.form-footer {
  margin-top: $gap-md;
  text-align: center;
  font-size: 13px; color: $color-muted;
  a { color: $color-accent; font-weight: 500; }
}
</style>
