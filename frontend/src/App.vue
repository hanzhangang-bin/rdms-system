<template>
  <main class="container">
    <el-card shadow="never" class="panel">
      <template #header>
        <div class="title">需求/设计/测试文档追踪矩阵</div>
      </template>

      <el-form label-position="top" class="upload-panel">
        <el-form-item label="文档组ID（第一次可留空自动生成）">
          <el-input v-model="documentGroupId" placeholder="例如: project-a-v1" clearable />
        </el-form-item>

        <el-form-item label="文档类型">
          <el-select v-model="docType" style="width: 100%">
            <el-option value="REQUIREMENT" label="需求文档" />
            <el-option value="DESIGN" label="设计文档" />
            <el-option value="TESTCASE" label="测试用例文档" />
          </el-select>
        </el-form-item>

        <el-form-item label="上传文件（支持 .doc/.docx/.wps）">
          <el-upload
            :auto-upload="false"
            :show-file-list="true"
            :limit="1"
            accept=".doc,.docx,.wps"
            :on-change="onFileChange"
            :on-remove="onFileRemove"
          >
            <el-button type="primary" plain>选择文件</el-button>
          </el-upload>
        </el-form-item>

        <div class="actions">
          <el-button type="primary" :disabled="!file" @click="upload">导入文档</el-button>
          <el-button :disabled="!documentGroupId" @click="loadTrace">生成追踪矩阵</el-button>
        </div>
      </el-form>

      <el-alert :title="message" type="info" :closable="false" show-icon class="hint" />
    </el-card>

    <el-card v-if="traceRows.length" shadow="never" class="panel">
      <template #header>
        <div class="subtitle">追踪矩阵</div>
      </template>
      <el-table :data="traceRows" border stripe>
        <el-table-column prop="requirementCatalog" label="需求章节" min-width="110" />
        <el-table-column prop="requirementTitle" label="需求标题" min-width="180" />
        <el-table-column prop="designCatalog" label="设计章节" min-width="110" />
        <el-table-column prop="designTitle" label="设计标题" min-width="180" />
        <el-table-column prop="testCatalog" label="测试章节" min-width="110" />
        <el-table-column prop="testTitle" label="测试标题" min-width="180" />
      </el-table>
    </el-card>

    <el-card v-if="graphColumns.requirement.length || graphColumns.design.length || graphColumns.test.length" shadow="never" class="panel">
      <template #header>
        <div class="subtitle">需求 → 设计 → 测试 追踪图</div>
      </template>
      <div class="graph" ref="graphRef">
        <svg class="edges" :width="svgSize.width" :height="svgSize.height">
          <defs>
            <marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
              <path d="M0,0 L8,4 L0,8 Z" fill="#909399" />
            </marker>
          </defs>
          <line
            v-for="edge in graphEdges"
            :key="`${edge.source}-${edge.target}`"
            :x1="edge.x1"
            :y1="edge.y1"
            :x2="edge.x2"
            :y2="edge.y2"
            stroke="#909399"
            stroke-width="1.4"
            marker-end="url(#arrow)"
          />
        </svg>

        <div class="column">
          <h3>需求</h3>
          <div v-for="node in graphColumns.requirement" :id="node.id" :key="node.id" class="node req">
            <span class="no">{{ node.catalogNo }}</span>
            <span>{{ node.title }}</span>
          </div>
        </div>
        <div class="column">
          <h3>设计</h3>
          <div v-for="node in graphColumns.design" :id="node.id" :key="node.id" class="node design">
            <span class="no">{{ node.catalogNo }}</span>
            <span>{{ node.title }}</span>
          </div>
        </div>
        <div class="column">
          <h3>测试</h3>
          <div v-for="node in graphColumns.test" :id="node.id" :key="node.id" class="node test">
            <span class="no">{{ node.catalogNo }}</span>
            <span>{{ node.title }}</span>
          </div>
        </div>
      </div>
    </el-card>
  </main>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getTraceGraph, getTraceMatrix, importDocument } from './api/document'

const file = ref(null)
const docType = ref('REQUIREMENT')
const documentGroupId = ref('')
const traceRows = ref([])
const message = ref('请先导入文档')

const graphRef = ref(null)
const graphColumns = reactive({ requirement: [], design: [], test: [] })
const graphEdges = ref([])
const svgSize = reactive({ width: 0, height: 0 })

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
    if (documentGroupId.value) {
      formData.append('documentGroupId', documentGroupId.value)
    }

    const { data } = await importDocument(formData)
    documentGroupId.value = data.documentGroupId
    message.value = `导入成功：${data.docType}，解析目录 ${data.catalogCount} 条，文档组ID=${data.documentGroupId}`
    ElMessage.success('文档导入成功')
  } catch (error) {
    ElMessage.error('导入失败，请检查文件格式或服务状态')
  }
}

const loadTrace = async () => {
  try {
    const [matrixResp, graphResp] = await Promise.all([
      getTraceMatrix(documentGroupId.value),
      getTraceGraph(documentGroupId.value)
    ])
    traceRows.value = matrixResp.data

    const nodes = graphResp.data.nodes || []
    graphColumns.requirement = nodes.filter((n) => n.type === 'REQUIREMENT')
    graphColumns.design = nodes.filter((n) => n.type === 'DESIGN')
    graphColumns.test = nodes.filter((n) => n.type === 'TESTCASE')

    await nextTick()
    redrawEdges(graphResp.data.edges || [])
    message.value = `已生成追踪矩阵 ${matrixResp.data.length} 行，追踪边 ${graphEdges.value.length} 条`
  } catch (error) {
    ElMessage.error('查询追踪关系失败，请检查文档组ID')
  }
}

const redrawEdges = (edges) => {
  if (!graphRef.value) return
  const rect = graphRef.value.getBoundingClientRect()
  svgSize.width = rect.width
  svgSize.height = rect.height

  graphEdges.value = edges
    .map((edge) => {
      const sourceEl = graphRef.value.querySelector(`#${CSS.escape(edge.source)}`)
      const targetEl = graphRef.value.querySelector(`#${CSS.escape(edge.target)}`)
      if (!sourceEl || !targetEl) return null

      const s = sourceEl.getBoundingClientRect()
      const t = targetEl.getBoundingClientRect()
      return {
        ...edge,
        x1: s.right - rect.left,
        y1: s.top + s.height / 2 - rect.top,
        x2: t.left - rect.left,
        y2: t.top + t.height / 2 - rect.top
      }
    })
    .filter(Boolean)
}

const onResize = () => redrawEdges(graphEdges.value)

onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))
</script>
