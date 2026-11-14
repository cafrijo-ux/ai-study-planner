-- Memory H2 only, explicitly loaded by offline tests. Never startup SQL.
CREATE TABLE ai_generation_requests (
 user_id BIGINT NOT NULL, feature VARCHAR(32) NOT NULL, request_key VARCHAR(80) NOT NULL,
 goal_id BIGINT NOT NULL, request_hash CHAR(64) NOT NULL, request_state VARCHAR(32) NOT NULL,
 policy_day DATE NOT NULL, provider VARCHAR(32) NOT NULL, model VARCHAR(150) NOT NULL,
 dispatch_reserved BOOLEAN NOT NULL, provider_response_received BOOLEAN NOT NULL, result_stored BOOLEAN NOT NULL,
 ai_response_saved BOOLEAN, response_id BIGINT, prompt_sha256 CHAR(64), result_json CLOB, usage_json CLOB,
 created_at TIMESTAMP NOT NULL, dispatch_at TIMESTAMP, completed_at TIMESTAMP,
 PRIMARY KEY(user_id,feature,request_key),
 FOREIGN KEY(user_id) REFERENCES users(user_id), FOREIGN KEY(goal_id) REFERENCES goals(goal_id),
 CHECK(request_state IN ('RESERVED','DISPATCH_RESERVED','COMPLETED','NO_SEND','UNKNOWN'))
);
CREATE INDEX idx_ai_generation_budget ON ai_generation_requests(user_id,feature,policy_day,model);
