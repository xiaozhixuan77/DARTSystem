export interface ApiResponse<T> {
  code: number
  message: string
  suggestion: string | null
  data: T
}
