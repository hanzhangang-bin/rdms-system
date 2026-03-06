<template>
  <main class="container">
    <el-card shadow="never" class="panel">
      <template #header><div class="title">需求/设计/测试文档追踪矩阵</div></template>
      <el-form label-position="top" class="upload-panel">
        <el-form-item label="文档组ID（第一次可留空自动生成）">
          <el-input v-model="documentGroupId" clearable />
        </el-form-item>
        <el-form-item label="文档类型">
          <el-select v-model="docType" style="width:100%">
            <el-option value="REQUIREMENT" label="需求文档" />
            <el-option value="DESIGN" label="设计文档" />
            <el-option value="TESTCASE" label="测试用例文档" />
          </el-select>
        </el-form-item>
        <el-form-item label="版本号（可选，不填自动生成）">
          <el-input v-model="versionNo" placeholder="例如 v1.0.0" clearable />
        </el-form-item>
        <el-form-item label="上传文件（支持 .doc/.docx/.wps）">
          <el-upload :auto-upload="false" :limit="1" accept=".doc,.docx,.wps" :on-change="onFileChange" :on-remove="onFileRemove">
            <el-button type="primary" plain>选择文件</el-button>
          </el-upload>
        </el-form-item>
        <div class="actions">
          <el-button type="primary" :disabled="!file" @click="upload">导入文档</el-button>
          <el-button :disabled="!documentGroupId" @click="loadTrace">生成追踪矩阵</el-button>
          <el-button :disabled="!documentGroupId" @click="loadVersions">加载版本</el-button>
          <el-button :disabled="!documentGroupId" @click="loadCatalogTree">加载目录内容</el-button>
        </div>
      </el-form>
      <el-alert :title="message" type="info" :closable="false" show-icon class="hint" />
    </el-card>

    <el-card v-if="versions.length" shadow="never" class="panel">
      <template #header><div class="subtitle">文档版本管理</div></template>
      <el-table :data="versions" border>
        <el-table-column prop="versionNo" label="版本号" width="200" />
        <el-table-column prop="createdAt" label="创建时间" min-width="220" />
        <el-table-column label="最新版本" width="100">
          <template #default="scope">{{ scope.row.isLatest === 1 ? '是' : '否' }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="traceRows.length" shadow="never" class="panel">
      <template #header><div class="subtitle">追踪矩阵（可手动调整）</div></template>
      <el-table :data="traceRows" border stripe>
        <el-table-column prop="requirementCatalog" label="需求章节" min-width="110" />
        <el-table-column prop="requirementTitle" label="需求标题" min-width="180" />
        <el-table-column label="设计章节" min-width="140">
          <template #default="scope"><el-input v-model="scope.row.designCatalog" /></template>
        </el-table-column>
        <el-table-column prop="designTitle" label="设计标题" min-width="160" />
        <el-table-column label="测试章节" min-width="140">
          <template #default="scope"><el-input v-model="scope.row.testCatalog" /></template>
        </el-table-column>
        <el-table-column prop="testTitle" label="测试标题" min-width="160" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="scope">
            <el-button type="primary" link @click="saveManual(scope.row)">保存调整</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="catalogFlat.length" shadow="never" class="panel">
      <template #header><div class="subtitle">目录与内容编辑</div></template>
      <el-row :gutter="12">
        <el-col :span="8">
          <el-select v-model="selectedCatalogId" placeholder="选择目录" style="width:100%" @change="syncEditor">
            <el-option v-for="item in catalogFlat" :key="item.catalogId" :value="item.catalogId" :label="`${item.catalogNo || '-'} ${item.title}`" />
          </el-select>
        </el-col>
        <el-col :span="16">
          <el-input v-model="editorTitle" placeholder="目录标题" class="mb8" />
          <el-input v-model="editorContentHtml" type="textarea" :rows="8" placeholder="目录内容HTML" />
          <div class="actions mt8">
            <el-button type="primary" :disabled="!selectedCatalogId" @click="saveCatalogContent">保存目录内容</el-button>
          </div>
        </el-col>
      </el-row>
    </el-card>
  </main>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getCatalogContentTree,
  getTraceMatrix,
  getVersions,
  importDocument,
  saveTraceManualAdjust,
  updateCatalogContent
} from './api/document'

const file = ref(null)
const docType = ref('REQUIREMENT')
const documentGroupId = ref('')
const versionNo = ref('')
const traceRows = ref([])
const versions = ref([])
const message = ref('请先导入文档')

const catalogFlat = ref([])
const selectedCatalogId = ref(null)
const editorTitle = ref('')
const editorContentHtml = ref('')

const onFileChange = (uploadFile) => {
  file.value = uploadFile.raw || null
}

const onFileRemove = () => {
  file.value = null
}

const upload = async () => {
  try {
    const formData = new FormData()
    formData.append('file', file.value)
    formData.append('docType', docType.value)
    if (documentGroupId.value) formData.append('documentGroupId', documentGroupId.value)
    if (versionNo.value) formData.append('versionNo', versionNo.value)

    const { data } = await importDocument(formData)
    documentGroupId.value = data.documentGroupId
    versionNo.value = data.versionNo
    message.value = `导入成功：${data.docType}，版本=${data.versionNo}，目录 ${data.catalogCount} 条`
    ElMessage.success('文档导入成功')
  } catch (e) {
    ElMessage.error('导入失败')
  }
}

const loadTrace = async () => {
  const { data } = await getTraceMatrix(documentGroupId.value)
  traceRows.value = data
  message.value = `已生成追踪矩阵 ${data.length} 行`
}

const saveManual = async (row) => {
  await saveTraceManualAdjust({
    documentGroupId: documentGroupId.value,
    requirementCatalog: row.requirementCatalog,
    designCatalog: row.designCatalog,
    testCatalog: row.testCatalog
  })
  ElMessage.success('手工调整已保存')
}

const loadVersions = async () => {
  const { data } = await getVersions(documentGroupId.value, docType.value)
  versions.value = data
}

const flattenTree = (nodes, acc = []) => {
  nodes.forEach((n) => {
    acc.push(n)
    if (n.children?.length) flattenTree(n.children, acc)
  })
  return acc
}

const loadCatalogTree = async () => {
  const { data } = await getCatalogContentTree(documentGroupId.value, docType.value, versionNo.value || null)
  catalogFlat.value = flattenTree(data || [])
  if (catalogFlat.value.length && !selectedCatalogId.value) {
    selectedCatalogId.value = catalogFlat.value[0].catalogId
    syncEditor()
  }
}

const syncEditor = () => {
  const node = catalogFlat.value.find((n) => n.catalogId === selectedCatalogId.value)
  editorTitle.value = node?.title || ''
  editorContentHtml.value = node?.contentText || ''
}

const saveCatalogContent = async () => {
  await updateCatalogContent({
    catalogId: selectedCatalogId.value,
    title: editorTitle.value,
    contentHtml: editorContentHtml.value
  })
  ElMessage.success('目录与内容已保存')
}
</script>
