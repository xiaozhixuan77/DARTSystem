import client from './client'
import type { ApiResponse } from '@/types/api'
import type { KnowledgeItemVO, KnowledgeItemDetail, GraphData, KnowledgeEntityVO } from '@/types/knowledge'

export async function listKnowledge(page: number = 1, size: number = 12, tag?: string, keyword?: string, entity?: string): Promise<ApiResponse<{ records: KnowledgeItemVO[]; total: number }>> {
  const { data } = await client.get<ApiResponse<{ records: KnowledgeItemVO[]; total: number }>>('/knowledge', {
    params: { page, size, tag, keyword, entity }
  })
  return data
}

export async function getKnowledgeTags(): Promise<ApiResponse<string[]>> {
  const { data } = await client.get<ApiResponse<string[]>>('/knowledge/tags')
  return data
}

export async function fetchEntities(): Promise<ApiResponse<KnowledgeEntityVO[]>> {
  const { data } = await client.get<ApiResponse<KnowledgeEntityVO[]>>('/knowledge/entities')
  return data
}

export async function updateKnowledge(id: number, payload: { title: string; tags: string; notes: string; content: string }): Promise<ApiResponse<void>> {
  const { data } = await client.put<ApiResponse<void>>(`/knowledge/${id}`, payload)
  return data
}

export async function getGlobalKnowledgeGraph(): Promise<ApiResponse<GraphData>> {
  const { data } = await client.get<ApiResponse<GraphData>>('/knowledge/graph')
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
