import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { setPoolUserId } from '@/api/client'
import type { ApiResponse } from '@/types/api'
import client from '@/api/client'

export interface PoolVO {
  key: 'user' | 'test'
  id: number
  name: string
}

const DEFAULT_POOL_USER_ID = 3

export const usePoolStore = defineStore('pool', () => {
  const pools = ref<PoolVO[]>([])
  const currentId = ref(getCurrentPoolId())
  const loading = ref(false)

  function getCurrentPoolId(): number {
    const raw = localStorage.getItem('poolUserId')
    const id = Number(raw)
    return Number.isInteger(id) && id > 0 ? id : DEFAULT_POOL_USER_ID
  }

  const currentName = computed(() => {
    const p = pools.value.find((x) => x.id === currentId.value)
    return p ? p.name : '用户数据'
  })

  async function loadPools() {
    loading.value = true
    try {
      const { data } = await client.get<ApiResponse<PoolVO[]>>('/settings/pools')
      if (data.code === 200) {
        pools.value = data.data
        currentId.value = getCurrentPoolId()
      }
    } catch { /* ignore */ }
    finally { loading.value = false }
  }

  async function switchPool(id: number) {
    setPoolUserId(id)
    currentId.value = id
  }

  return {
    pools, currentId, currentName, loading,
    loadPools, switchPool, getCurrentPoolId
  }
})
