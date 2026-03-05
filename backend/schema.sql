CREATE TABLE IF NOT EXISTS doc_catalog (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_group_id VARCHAR(64) NOT NULL,
    doc_type VARCHAR(32) NOT NULL COMMENT 'REQUIREMENT/DESIGN/TESTCASE',
    catalog_no VARCHAR(64) NOT NULL,
    title VARCHAR(500) NOT NULL,
    catalog_level INT NOT NULL,
    parent_id BIGINT NULL,
    full_path VARCHAR(2000) NULL,
    INDEX idx_doc_group_type (document_group_id, doc_type),
    INDEX idx_doc_group_catalog (document_group_id, catalog_no)
);

CREATE TABLE IF NOT EXISTS doc_content (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    catalog_id BIGINT NOT NULL,
    document_group_id VARCHAR(64) NOT NULL,
    content_text LONGTEXT,
    INDEX idx_content_catalog (catalog_id),
    INDEX idx_content_group (document_group_id)
);
