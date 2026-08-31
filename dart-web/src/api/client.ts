import axios, { type AxiosInstance } from 'axios'

/**
 * 当前数据池用户 id：产品固定用户数据池（缺省 3）；
 * localStorage poolUserId 仅作为测试注入通道（E2E 指向 pool_test 防污染），不对用户暴露。
 */
export function getPoolUserId(): number {
  const raw = localStorage.getItem('poolUserId')
  const id = Number(raw)
  return Number.isInteger(id) && id > 0 ? id : 3
}

export function setPoolUserId(id: number) {
  localStorage.setItem('poolUserId', String(id))
}

const client: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

// 请求拦截器：无登录模式，注入数据池用户 id
client.interceptors.request.use((config) => {
  config.headers['X-User-Id'] = String(getPoolUserId())
  return config
})

export default client
