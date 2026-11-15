-- one live token per (user, purpose): keep the newest, so the unique index below cannot fail on old data
DELETE FROM user_token WHERE EXISTS (SELECT 1 FROM user_token n WHERE n.user_id = user_token.user_id AND n.purpose = user_token.purpose AND (n.created_at > user_token.created_at OR (n.created_at = user_token.created_at AND n.id > user_token.id)));
CREATE UNIQUE INDEX uq_user_token_user_purpose ON user_token (user_id, purpose);
