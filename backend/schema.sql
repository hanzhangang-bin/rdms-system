CREATE TABLE IF NOT EXISTS doc_catalog (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_group_id VARCHAR(64) NOT NULL,
    version_no VARCHAR(64) NOT NULL,
    doc_type VARCHAR(32) NOT NULL COMMENT 'REQUIREMENT/DESIGN/TESTCASE',
    catalog_no VARCHAR(64) NOT NULL,
    title VARCHAR(500) NOT NULL,
    catalog_level INT NOT NULL,
    parent_id BIGINT NULL,
    full_path VARCHAR(2000) NULL,
    INDEX idx_doc_group_version_type (document_group_id, version_no, doc_type),
    INDEX idx_doc_group_catalog (document_group_id, catalog_no)
);

CREATE TABLE IF NOT EXISTS doc_content (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    catalog_id BIGINT NOT NULL,
    document_group_id VARCHAR(64) NOT NULL,
    version_no VARCHAR(64) NOT NULL,
    content_text LONGTEXT,
    INDEX idx_content_catalog (catalog_id),
    INDEX idx_content_group_version (document_group_id, version_no)
);

CREATE TABLE IF NOT EXISTS doc_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_group_id VARCHAR(64) NOT NULL,
    doc_type VARCHAR(32) NOT NULL,
    version_no VARCHAR(64) NOT NULL,
    is_latest TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_group_type_version (document_group_id, doc_type, version_no),
    INDEX idx_group_type_latest (document_group_id, doc_type, is_latest)
);

CREATE TABLE IF NOT EXISTS trace_matrix_manual (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_group_id VARCHAR(64) NOT NULL,
    requirement_catalog VARCHAR(64) NOT NULL,
    design_catalog VARCHAR(64) NULL,
    test_catalog VARCHAR(64) NULL,
    UNIQUE KEY uk_manual_req (document_group_id, requirement_catalog)
);
