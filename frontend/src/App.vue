<template>
  <main class="container">
    <h1>需求/设计/测试文档追踪矩阵</h1>
    <div class="upload-panel">
      <label>文档组ID（第一次可留空自动生成）</label>
      <input v-model="documentGroupId" placeholder="例如: project-a-v1" />
      <label>文档类型</label>
      <select v-model="docType">
        <option value="REQUIREMENT">需求文档</option>
        <option value="DESIGN">设计文档</option>
        <option value="TESTCASE">测试用例文档</option>
      </select>
      <input type="file" @change="onFileChange" accept=".doc,.docx,.wps" />
      <div class="actions">
        <button :disabled="!file" @click="upload">导入文档</button>
        <button :disabled="!documentGroupId" @click="loadTrace">生成追踪矩阵</button>
      </div>
    </div>

    <p class="hint">{{ message }}</p>

    <section v-if="traceRows.length">
      <h2>追踪矩阵</h2>
      <table>
        <thead>
          <tr>
            <th>需求章节</th>
            <th>需求标题</th>
            <th>设计章节</th>
            <th>设计标题</th>
            <th>测试章节</th>
            <th>测试标题</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in traceRows" :key="`${row.requirementCatalog}-${row.requirementTitle}`">
            <td>{{ row.requirementCatalog }}</td>
            <td>{{ row.requirementTitle }}</td>
            <td>{{ row.designCatalog || '-' }}</td>
            <td>{{ row.designTitle || '-' }}</td>
            <td>{{ row.testCatalog || '-' }}</td>
            <td>{{ row.testTitle || '-' }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <section v-if="graphColumns.requirement.length || graphColumns.design.length || graphColumns.test.length" class="graph-wrap">
      <h2>需求 → 设计 → 测试 追踪图</h2>
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
    </section>
  </main>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
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

const onFileChange = (event) => {
  file.value = event.target.files?.[0] || null
}

const upload = async () => {
  const formData = new FormData()
  formData.append('file', file.value)
  formData.append('docType', docType.value)
  if (documentGroupId.value) {
    formData.append('documentGroupId', documentGroupId.value)
  }

  const { data } = await importDocument(formData)
  documentGroupId.value = data.documentGroupId
  message.value = `导入成功：${data.docType}，解析目录 ${data.catalogCount} 条，文档组ID=${data.documentGroupId}`
}

const loadTrace = async () => {
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
