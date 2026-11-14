-- REVIEW ONLY. No application startup migration. Fresh schema/DDL/privilege approval required.
-- Never automatically execute against ai_study_planner or ai_study_planner_test.
CREATE TABLE `__APPROVED_SCHEMA__`.`ai_generation_requests` (
 `user_id` BIGINT NOT NULL,
 `feature` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 `request_key` VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 `goal_id` BIGINT NOT NULL,
 `request_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 `request_state` VARCHAR(32) NOT NULL,
 `policy_day` DATE NOT NULL,
 `provider` VARCHAR(32) NOT NULL,
 `model` VARCHAR(150) NOT NULL,
 `dispatch_reserved` BOOLEAN NOT NULL,
 `provider_response_received` BOOLEAN NOT NULL,
 `result_stored` BOOLEAN NOT NULL,
 `ai_response_saved` BOOLEAN NULL,
 `response_id` BIGINT NULL,
 `prompt_sha256` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 `result_json` MEDIUMTEXT NULL,
 `usage_json` TEXT NULL,
 `created_at` DATETIME(6) NOT NULL,
 `dispatch_at` DATETIME(6) NULL,
 `completed_at` DATETIME(6) NULL,
 PRIMARY KEY (`user_id`,`feature`,`request_key`),
 KEY `idx_ai_generation_budget` (`user_id`,`feature`,`policy_day`,`model`),
 CONSTRAINT `fk_ai_generation_user` FOREIGN KEY (`user_id`) REFERENCES `__APPROVED_SCHEMA__`.`users` (`user_id`),
 CONSTRAINT `fk_ai_generation_goal` FOREIGN KEY (`goal_id`) REFERENCES `__APPROVED_SCHEMA__`.`goals` (`goal_id`),
 CONSTRAINT `ck_ai_generation_state` CHECK (`request_state` IN ('RESERVED','DISPATCH_RESERVED','COMPLETED','NO_SEND','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4;
-- Runtime SELECT/INSERT/UPDATE only; no automatic DELETE/DDL/cleanup.
-- response_id is a nullable observation, not proof of ledger atomicity with AiResponseRecorder's independent transaction.
-- Retention, unresolved reservations and schema/grant allowlist changes require separate approval.
