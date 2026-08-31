import axios, { type AxiosInstance } from 'axios'

/** 当前数据池用户 id：前端池切换写入，缺省 3（xiao 用户数据池） */
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
