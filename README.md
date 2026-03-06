# RDMS 文档目录识别与追踪示例

本项目基于 **Java + Spring Boot + MyBatis-Plus + Vue3 + Vite + Element Plus**，用于实现：

1. 导入需求/设计/测试用例 Word（`.doc` / `.docx` / `.wps`）文档；
2. 识别 Word 目录（标题层级）和目录对应正文内容；
3. 将目录与内容分别落库到两张表：`doc_catalog`、`doc_content`；
4. 依据目录编号或标题相似度，建立需求-设计-测试追踪矩阵与追踪图。

## 项目结构

- `backend`：Spring Boot 后端服务
- `frontend`：Vue3 + Vite + Element Plus 前端页面
- `backend/schema.sql`：建表脚本

## 快速启动

### 1. 初始化数据库

执行：`backend/schema.sql`

### 2. 启动后端

```bash
cd backend
mvn spring-boot:run
```

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

## 核心接口

- `POST /api/documents/import`
  - 参数：`file`、`docType(REQUIREMENT|DESIGN|TESTCASE)`、`documentGroupId(可选)`、`versionNo(可选)`
- `GET /api/documents/versions?documentGroupId=xxx&docType=REQUIREMENT|DESIGN|TESTCASE`
- `GET /api/documents/trace-matrix?documentGroupId=xxx`
- `POST /api/documents/trace-matrix/manual-adjust`
- `GET /api/documents/trace-graph?documentGroupId=xxx`
- `GET /api/documents/catalog-content-tree?documentGroupId=xxx&docType=REQUIREMENT|DESIGN|TESTCASE&versionNo=可选`
- `POST /api/documents/catalog-content/update`

## 目录识别增强算法

- 文件格式支持：`doc` / `docx` / `wps`（wps 自动尝试 doc/docx 两种解析）。
- 优先使用 Word 标题样式（`HeadingN` / `标题N`）识别层级。
- 支持阿拉伯数字目录编号（如 `1.2.3`）推断层级。
- 支持中文章节标题（如 `第一章` / `第二节`）识别。
- 支持中文条目（如 `（一）`）作为子层级补充。

## 追踪关系策略

1. 先按目录编号精确匹配（score=100）。
2. 再按目录编号前缀关系匹配（score=85）。
3. 最后按标题最长公共子串相似度匹配（阈值 >= 50）。


## 目录与内容关联

目录与内容通过 `doc_content.catalog_id -> doc_catalog.id` 关联。可通过 `catalog-content-tree` 接口一次性获取目录树及每个目录节点对应正文内容。


## 内容格式化落库

导入时会将正文转换为 HTML 存储到 `doc_content.content_text`：
- 普通段落转 `<p>`，并尽量保留原文字样式（加粗、斜体、下划线、删除线、颜色、字号、字体）
- 表格转 `<table>`，单元格内部段落继续按样式转 HTML
- 图片转 `<img src="data:*;base64,...">`

这样目录节点与对应内容不仅可关联，还能保留图片、表格等格式。

## 新增能力

- 文档版本管理：导入时可指定版本号，不指定则自动生成；同一 `documentGroupId + docType` 始终标记一个最新版本。
- 目录和目录内容编辑：支持基于 `catalogId` 修改目录标题与对应 HTML 内容。
- 追踪矩阵手工调整：矩阵以表格展示，并支持逐行手动修改设计/测试关联后保存。
