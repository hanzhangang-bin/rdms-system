import axios from 'axios'

export const importDocument = (formData) => axios.post('/api/documents/import', formData)

export const getTraceMatrix = (documentGroupId) => axios.get('/api/documents/trace-matrix', {
  params: { documentGroupId }
})

export const saveTraceManualAdjust = (payload) => axios.post('/api/documents/trace-matrix/manual-adjust', payload)

export const getTraceGraph = (documentGroupId) => axios.get('/api/documents/trace-graph', {
  params: { documentGroupId }
})

export const getCatalogContentTree = (documentGroupId, docType, versionNo) => axios.get('/api/documents/catalog-content-tree', {
  params: { documentGroupId, docType, versionNo }
})

export const updateCatalogContent = (payload) => axios.post('/api/documents/catalog-content/update', payload)

export const getVersions = (documentGroupId, docType) => axios.get('/api/documents/versions', {
  params: { documentGroupId, docType }
})
