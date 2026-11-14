-- MEMORY H2 TEST ONLY. Not loaded by production configuration.
CREATE TABLE study_log_requests (
 user_id BIGINT NOT NULL, operation VARCHAR(32) NOT NULL, request_key VARCHAR(80) NOT NULL,
 goal_id BIGINT NOT NULL, request_hash CHAR(64) NOT NULL, request_state VARCHAR(16) NOT NULL,
 log_id BIGINT, response_json CLOB, created_at TIMESTAMP NOT NULL, completed_at TIMESTAMP,
 PRIMARY KEY(user_id,operation,request_key),
 FOREIGN KEY(user_id) REFERENCES users(user_id), FOREIGN KEY(goal_id) REFERENCES goals(goal_id),
 FOREIGN KEY(log_id) REFERENCES study_logs(log_id),
 CHECK(request_state IN ('PROCESSING','COMPLETED'))
);
