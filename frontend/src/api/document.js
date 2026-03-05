import axios from 'axios'

export const importDocument = (formData) => axios.post('/api/documents/import', formData)

export const getTraceMatrix = (documentGroupId) => axios.get('/api/documents/trace-matrix', {
  params: { documentGroupId }
})

export const getTraceGraph = (documentGroupId) => axios.get('/api/documents/trace-graph', {
  params: { documentGroupId }
})
