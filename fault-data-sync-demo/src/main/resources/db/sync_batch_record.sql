-- 批次状态记录表，用于支持批次级粒度重试
USE code_demo;

CREATE TABLE IF NOT EXISTS sync_batch_record (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    domain         VARCHAR(64)  NOT NULL               COMMENT '数据领域',
    data_date      DATE         NOT NULL               COMMENT '数据所属日期',
    batch_index    INT          NOT NULL               COMMENT '批次序号（从 0 开始）',
    start_rank     BIGINT       NOT NULL               COMMENT '本批 pull 的起始 rank（游标值）',
    end_rank       BIGINT       NOT NULL DEFAULT 0     COMMENT '本批最大 rank，成功后回填',
    record_count   INT          NOT NULL DEFAULT 0     COMMENT '本批记录数',
    pull_status    VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED',
    insert_status  VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED',
    error_message  VARCHAR(500)                        COMMENT '失败原因',
    create_time    DATETIME                            COMMENT '创建时间',
    update_time    DATETIME                            COMMENT '更新时间',
    UNIQUE KEY uk_domain_date_batch (domain, data_date, batch_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步批次状态记录';
