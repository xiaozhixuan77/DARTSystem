export interface ApiResponse<T> {
  code: number
  message: string
  suggestion: string | null
  data: T
}

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}
