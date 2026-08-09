import client from './client'
import type { ApiResponse } from '@/types/api'
import type { KnowledgeItemVO, KnowledgeItemDetail, GraphData } from '@/types/knowledge'

export async function listKnowledge(page: number = 1, size: number = 12): Promise<ApiResponse<{ records: KnowledgeItemVO[]; total: number }>> {
  const { data } = await client.get<ApiResponse<{ records: KnowledgeItemVO[]; total: number }>>('/knowledge', {
    params: { page, size }
  })
  return data
}

export async function getKnowledgeDetail(id: number): Promise<ApiResponse<KnowledgeItemDetail>> {
  const { data } = await client.get<ApiResponse<KnowledgeItemDetail>>(`/knowledge/${id}`)
  return data
}

export async function deleteKnowledge(id: number): Promise<ApiResponse<void>> {
  const { data } = await client.delete<ApiResponse<void>>(`/knowledge/${id}`)
  return data
}

export async function getKnowledgeGraph(id: number): Promise<ApiResponse<GraphData>> {
  const { data } = await client.get<ApiResponse<GraphData>>(`/knowledge/${id}/graph`)
  return data
}
