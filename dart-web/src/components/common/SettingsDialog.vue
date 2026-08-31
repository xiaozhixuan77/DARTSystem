<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import client from '@/api/client'
import type { ApiResponse } from '@/types/api'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ close: [] }>()

const baseUrl = ref('')
const model = ref('')
const apiKey = ref('')
const saving = ref(false)
const balanceValue = ref('')
const balanceUnit = ref('')
const balanceError = ref('')
const balanceLoading = ref(false)

interface ApiConfigData {
  baseUrl: string
  model: string
  apiKey: string
}

interface BalanceData {
  isAvailable: boolean
  currency: string | null
  totalBalance: string | null
  error: string | null
}

const urlError = computed(() => {
  if (!baseUrl.value) return false
  return !/^https:\/\/.+$/i.test(baseUrl.value.trim())
})

async function loadApiConfig() {
  try {
    const { data } = await client.get<ApiResponse<ApiConfigData>>('/settings/api')
    if (data.code === 200 && data.data) {
      baseUrl.value = data.data.baseUrl || ''
      model.value = data.data.model || ''
      // 脱敏值回填展示（sk-6****81cb 形式），用户覆盖时以输入为准
      apiKey.value = data.data.apiKey || ''
    }
  } catch { /* 保持空表单 */ }
}

async function saveApi() {
  if (urlError.value) {
    ElMessage.error('请输入有效的 HTTPS 服务地址')
    return
  }
  if (!model.value.trim() || !apiKey.value.trim()) {
    ElMessage.error('模型名称与 API Key 不能为空')
    return
  }
  saving.value = true
  try {
    const { data } = await client.put<ApiResponse<void>>('/settings/api', {
      baseUrl: baseUrl.value.trim(),
      model: model.value.trim(),
      apiKey: apiKey.value.trim()
    })
    if (data.code === 200) {
      ElMessage.success('设置已保存并生效')
      emit('close')
    } else {
      ElMessage.error(data.message || '保存失败')
    }
  } catch {
    ElMessage.error('保存失败（网络错误）')
  } finally {
    saving.value = false
  }
}

async function refreshBalance() {
  balanceError.value = ''
  balanceLoading.value = true
  try {
    const { data } = await client.get<ApiResponse<BalanceData>>('/settings/balance')
    if (data.code === 200 && data.data) {
      if (data.data.isAvailable && data.data.totalBalance) {
        balanceValue.value = data.data.totalBalance
        balanceUnit.value = data.data.currency ?? ''
      } else {
        balanceError.value = data.data.error ?? '查询失败'
      }
    }
  } catch {
    balanceError.value = '余额查询失败（网络错误）'
  } finally {
    balanceLoading.value = false
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
}

onMounted(() => {
  if (props.visible) {
    loadApiConfig()
  }
  window.addEventListener('keydown', onKeydown)
})

watch(() => props.visible, (v) => {
  if (v) {
    loadApiConfig()
    refreshBalance()
  }
})
</script>

<template>
  <div v-if="visible" class="settings-overlay" @click.self="emit('close')">
    <div class="settings-dialog">
      <header class="settings-header">
        <h2 class="settings-title">设置</h2>
        <button class="close-btn" aria-label="关闭设置" @click="emit('close')">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
            <path d="M18 6 6 18M6 6l12 12"/>
          </svg>
        </button>
      </header>

      <div class="settings-body">
        <section class="balance-section">
          <div class="section-title">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/>
            </svg>
            API 余额
          </div>
          <p class="section-hint">余额由对话与检索 Agent 共同消耗。</p>
          <div class="balance-card">
            <div class="balance-main">
              <div class="balance-label">剩余可用额度</div>
              <div class="balance-value">
                <span class="num">{{ balanceValue || '--' }}</span>
                <span class="balance-unit">{{ balanceUnit }}</span>
              </div>
            </div>
            <button class="refresh-btn" :class="{ 'is-loading': balanceLoading }" @click="refreshBalance">
              <span class="spinner" aria-hidden="true"></span>
              <span>{{ balanceLoading ? '刷新中' : '刷新' }}</span>
            </button>
          </div>
          <p v-if="balanceError" class="balance-error">{{ balanceError }}</p>
        </section>

        <section class="config-section">
          <div class="section-title">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83"/>
            </svg>
            服务配置
          </div>
          <div class="form-group" :class="{ 'has-error': urlError }">
            <label class="form-label" for="apiUrl">API 服务地址 <span class="hint">对话模型请求地址</span></label>
            <input id="apiUrl" v-model="baseUrl" type="url" class="form-input" :class="{ 'is-error': urlError }"
              placeholder="https://api.deepseek.com" />
            <span class="form-error">请输入有效的 HTTPS 地址</span>
          </div>
          <div class="form-group">
            <label class="form-label" for="modelName">模型名称 <span class="hint">对话模型</span></label>
            <input id="modelName" v-model="model" type="text" class="form-input"
              placeholder="deepseek-v4-flash" />
          </div>
          <div class="form-group">
            <label class="form-label" for="apiKey">API Key <span class="hint">保存后立即生效</span></label>
            <input id="apiKey" v-model="apiKey" type="password" class="form-input" placeholder="sk-..." />
          </div>
        </section>
      </div>

      <footer class="settings-footer">
        <button class="btn btn-secondary" @click="emit('close')">取消</button>
        <button class="btn btn-primary" :class="{ 'is-loading': saving }" :disabled="saving" @click="saveApi">
          <span class="spinner" aria-hidden="true"></span>
          <span>{{ saving ? '保存中' : '保存设置' }}</span>
        </button>
      </footer>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.settings-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(51, 46, 65, 0.18);
}
.settings-dialog {
  width: 100%;
  max-width: 560px;
  max-height: calc(100vh - 48px);
  background: $color-surface;
  border: 1px solid $color-border;
  border-radius: $radius-lg;
  box-shadow: 0 24px 60px rgba(51, 46, 65, 0.14);
  display: flex;
  flex-direction: column;
}
.settings-header {
  padding: 16px 24px;
  border-bottom: 1px solid $color-border;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
}
.settings-title {
  font-size: 17px;
  font-weight: 600;
  margin: 0;
}
.close-btn {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: $radius;
  background: transparent;
  color: $color-muted;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, transform 0.15s;
  &:hover { background: $color-bg; color: $color-fg; }
  &:active { transform: scale(0.94); }
}
.settings-body {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  > * + * { margin-top: 24px; }
}
.section-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.section-hint {
  font-size: 12px;
  color: $color-muted;
  margin: 0 0 10px;
}
.balance-card {
  padding: 16px;
  background: $color-accent-soft;
  border: 1px solid rgba(92, 92, 235, 0.18);
  border-radius: $radius;
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 16px;
  align-items: center;
}
.balance-main { display: flex; flex-direction: column; gap: 6px; }
.balance-label { font-size: 12px; color: $color-muted; }
.balance-value {
  font-family: $font-mono;
  font-size: 28px;
  font-weight: 700;
  color: $color-fg;
  letter-spacing: -0.02em;
}
.balance-unit { font-size: 16px; font-weight: 500; color: $color-muted; margin-left: 4px; }
.balance-error { font-size: 12px; color: $color-warning; margin: 10px 0 0; }
.refresh-btn {
  padding: 6px 12px;
  display: flex;
  align-items: center;
  gap: 6px;
  border: 1px solid $color-border;
  border-radius: $radius;
  background: $color-surface;
  font-size: 13px;
  color: $color-muted;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s;
  &:hover { border-color: $color-accent; color: $color-accent; }
}
.spinner {
  display: none;
  width: 12px;
  height: 12px;
  border: 2px solid $color-border;
  border-top-color: $color-accent;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
.refresh-btn.is-loading .spinner,
.btn-primary.is-loading .spinner { display: inline-block; }
@keyframes spin { to { transform: rotate(360deg); } }
.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
  & + & { margin-top: 16px; }
}
.form-label {
  font-size: 13px;
  font-weight: 500;
  display: flex;
  align-items: center;
  justify-content: space-between;
  .hint { font-weight: 400; color: $color-muted; }
}
.form-input {
  width: 100%;
  padding: 10px 12px;
  background: $color-surface;
  border: 1px solid $color-border;
  border-radius: $radius;
  font-size: 14px;
  transition: border-color 0.15s, box-shadow 0.15s;
  &:hover { border-color: rgba(92, 92, 235, 0.4); }
  &:focus {
    outline: none;
    border-color: $color-accent;
    box-shadow: 0 0 0 3px $color-accent-soft;
  }
  &.is-error { border-color: #d66; background: #fdf3f2; }
}
.form-error {
  display: none;
  font-size: 12px;
  color: #d66;
}
.form-group.has-error {
  .form-error { display: block; }
  .form-input { border-color: #d66; }
}
.settings-footer {
  padding: 16px 24px;
  border-top: 1px solid $color-border;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  flex-shrink: 0;
}
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 9px 16px;
  border-radius: $radius;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, transform 0.15s;
  &:active { transform: scale(0.98); }
  &:disabled { opacity: 0.55; cursor: not-allowed; pointer-events: none; }
}
.btn-primary {
  background: $color-accent;
  color: white;
  border: none;
  &:hover { background: $color-accent-dark; }
  .spinner { border-color: rgba(255, 255, 255, 0.35); border-top-color: white; }
}
.btn-secondary {
  background: transparent;
  border: 1px solid $color-border;
  color: $color-fg;
  &:hover { background: $color-accent-soft; border-color: rgba(92, 92, 235, 0.35); }
}
</style>
