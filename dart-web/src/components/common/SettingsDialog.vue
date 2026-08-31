<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { usePoolStore } from '@/stores/pool'
import client from '@/api/client'
import type { ApiResponse } from '@/types/api'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ close: [] }>()

const poolStore = usePoolStore()

const baseUrl = ref('')
const model = ref('')
const apiKey = ref('')
const saving = ref(false)
const balanceText = ref('')
const balanceError = ref('')
const balanceAvailable = ref(false)

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

async function loadApiConfig() {
  try {
    const { data } = await client.get<ApiResponse<ApiConfigData>>('/settings/api')
    if (data.code === 200 && data.data) {
      baseUrl.value = data.data.baseUrl || ''
      model.value = data.data.model || ''
      apiKey.value = data.data.apiKey || ''
    }
  } catch { /* 保持空表单 */ }
}

async function saveApi() {
  if (!baseUrl.value || !model.value || !apiKey.value) {
    ElMessage.warning('URL、模型名、API Key 均不能为空')
    return
  }
  saving.value = true
  try {
    const { data } = await client.put<ApiResponse<void>>('/settings/api', {
      baseUrl: baseUrl.value,
      model: model.value,
      apiKey: apiKey.value
    })
    if (data.code === 200) {
      ElMessage.success('API 配置已保存并生效')
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
  balanceText.value = ''
  try {
    const { data } = await client.get<ApiResponse<BalanceData>>('/settings/balance')
    if (data.code === 200 && data.data) {
      if (data.data.isAvailable && data.data.totalBalance) {
        balanceAvailable.value = true
        balanceText.value = `${data.data.currency ?? ''} ${data.data.totalBalance}`
      } else {
        balanceAvailable.value = false
        balanceError.value = data.data.error ?? '查询失败'
      }
    }
  } catch {
    balanceError.value = '余额查询失败（网络错误）'
  }
}

async function switchPool(id: number, name: string) {
  if (id === poolStore.currentId) return
  if (!window.confirm(`切换到「${name}」？切换后页面将刷新。`)) return
  await poolStore.switchPool(id)
  window.location.reload()
}

onMounted(() => {
  poolStore.loadPools()
  if (props.visible) {
    loadApiConfig()
  }
})

watch(() => props.visible, (v) => {
  if (v) {
    loadApiConfig()
    poolStore.loadPools()
  }
})
</script>

<template>
  <div v-if="visible" class="settings-overlay" @click.self="emit('close')">
    <div class="settings-dialog">
      <header class="settings-header">
        <h3>设置</h3>
        <button class="close-btn" @click="emit('close')">×</button>
      </header>

      <section class="pool-section">
        <h4>数据池</h4>
        <div class="pool-options">
          <label v-for="p in poolStore.pools" :key="p.key" class="pool-option">
            <input
              type="radio"
              :checked="poolStore.currentId === p.id"
              @change="switchPool(p.id, p.name)"
            />
            <span>{{ p.name }}</span>
          </label>
          <p v-if="poolStore.pools.length === 0" class="pool-empty">加载数据池中...</p>
        </div>
      </section>

      <section class="api-section">
        <h4>chat API 配置</h4>
        <div class="form-row">
          <label>URL</label>
          <input v-model="baseUrl" class="api-url" placeholder="https://api.deepseek.com" />
        </div>
        <div class="form-row">
          <label>模型名称</label>
          <input v-model="model" class="api-model" placeholder="deepseek-v4-flash" />
        </div>
        <div class="form-row">
          <label>API Key</label>
          <input v-model="apiKey" type="password" class="api-key" placeholder="sk-..." />
        </div>
        <button class="api-save primary" :disabled="saving" @click="saveApi">
          {{ saving ? '保存中...' : '保存并生效' }}
        </button>
      </section>

      <section class="balance-section">
        <h4>API 余额</h4>
        <p v-if="balanceText" class="balance-text">余额：{{ balanceText }}</p>
        <p v-if="balanceError" class="balance-error">{{ balanceError }}</p>
        <button class="balance-refresh" @click="refreshBalance">刷新余额</button>
      </section>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.settings-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.settings-dialog {
  width: 460px;
  max-width: 92vw;
  max-height: 85vh;
  overflow-y: auto;
  background: $color-surface;
  border-radius: $radius-lg;
  padding: 20px 24px;
}
.settings-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  h3 { margin: 0; }
}
.close-btn {
  border: none;
  background: transparent;
  font-size: 22px;
  cursor: pointer;
  color: $color-muted;
}
section {
  margin-bottom: 20px;
  h4 {
    margin: 0 0 10px;
    font-size: 15px;
    border-bottom: 1px solid $color-border;
    padding-bottom: 6px;
  }
}
.pool-options {
  display: flex;
  gap: 16px;
  .pool-option {
    display: flex;
    align-items: center;
    gap: 6px;
    cursor: pointer;
  }
}
.form-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  label { width: 70px; font-size: 13px; color: $color-muted; flex-shrink: 0; }
  input {
    flex: 1;
    padding: 7px 10px;
    border: 1px solid $color-border;
    border-radius: $radius;
    font-size: 13px;
  }
}
.api-save, .balance-refresh {
  padding: 7px 16px;
  border-radius: $radius;
  border: 1px solid $color-border;
  background: $color-surface;
  cursor: pointer;
  font-size: 13px;
  &.primary { background: $color-accent; color: white; border-color: $color-accent; }
  &:disabled { opacity: 0.6; }
}
.balance-text { color: $color-success; font-weight: 600; }
.balance-error { color: $color-warning; }
</style>
