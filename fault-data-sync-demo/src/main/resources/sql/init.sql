-- fault-data-sync-demo 初始化 DDL
-- 执行前请确认数据库已存在：CREATE DATABASE IF NOT EXISTS code_demo;

USE code_demo;

-- 故障记录主表
CREATE TABLE IF NOT EXISTS fault_record (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    domain      VARCHAR(100) NOT NULL       COMMENT '数据领域',
    data_date   DATE NOT NULL               COMMENT '数据所属日期',
    rank        INT NOT NULL                COMMENT '当天唯一排序值（原数据源赋予）',
    fault_type  VARCHAR(200)                COMMENT '故障类型',
    device_id   VARCHAR(100)               COMMENT '设备ID',
    fault_detail TEXT                       COMMENT '故障详情',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    UNIQUE KEY uk_domain_date_rank (domain, data_date, rank)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='故障记录';

-- 同步任务状态表
CREATE TABLE IF NOT EXISTS sync_task_record (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    domain                VARCHAR(100) NOT NULL  COMMENT '领域',
    data_date             DATE NOT NULL          COMMENT '同步数据日期',
    status                VARCHAR(20) NOT NULL   COMMENT 'PENDING/RUNNING/MESSAGES_SENT/SUCCESS/FAILED',
    batch_count           INT DEFAULT 0          COMMENT '已发送MQ批次总数',
    completed_batch_count INT DEFAULT 0          COMMENT '已成功插入DB的批次数',
    total_records         INT DEFAULT 0          COMMENT '本次同步总记录数',
    retry_count           INT DEFAULT 0          COMMENT '重试次数',
    error_message         VARCHAR(500)           COMMENT '失败原因',
    start_time            DATETIME               COMMENT '同步开始时间',
    end_time              DATETIME               COMMENT '同步结束时间',
    create_time           DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time           DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_domain_date (domain, data_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步任务状态记录';
