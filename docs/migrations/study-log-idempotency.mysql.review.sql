-- REVIEW DRAFT ONLY: NOT a Flyway/Liquibase migration, NOT executed by the app.
-- Replace __APPROVED_SCHEMA__ only after fresh schema/DDL/backup/privilege approval.
-- Never run this file against ai_study_planner or ai_study_planner_test implicitly.
-- MySQL8 / InnoDB / exact case-sensitive ASCII key. No existing-row rewrite or cleanup.
CREATE TABLE `__APPROVED_SCHEMA__`.`study_log_requests` (
 `user_id` BIGINT NOT NULL,
 `operation` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 `request_key` VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 `goal_id` BIGINT NOT NULL,
 `request_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 `request_state` VARCHAR(16) NOT NULL,
 `log_id` BIGINT NULL,
 `response_json` MEDIUMTEXT NULL,
 `created_at` DATETIME(6) NOT NULL,
 `completed_at` DATETIME(6) NULL,
 PRIMARY KEY (`user_id`,`operation`,`request_key`),
 KEY `idx_study_request_goal` (`user_id`,`goal_id`,`created_at`),
 CONSTRAINT `fk_study_request_user` FOREIGN KEY (`user_id`) REFERENCES `__APPROVED_SCHEMA__`.`users` (`user_id`),
 CONSTRAINT `fk_study_request_goal` FOREIGN KEY (`goal_id`) REFERENCES `__APPROVED_SCHEMA__`.`goals` (`goal_id`),
 CONSTRAINT `fk_study_request_log` FOREIGN KEY (`log_id`) REFERENCES `__APPROVED_SCHEMA__`.`study_logs` (`log_id`),
 CONSTRAINT `ck_study_request_state` CHECK (`request_state` IN ('PROCESSING','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4;
-- Runtime account needs SELECT/INSERT/UPDATE on THIS table only in addition to
-- existing approved business-table grants; no DELETE/DDL/global grant required.
-- Cleanup and retention changes require a separate approval. No DROP/IF NOT EXISTS.
