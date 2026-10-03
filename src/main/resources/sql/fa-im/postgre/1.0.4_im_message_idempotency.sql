-- ------------------------- info -------------------------
-- @@ver: 1_000_004
-- @@info: 增加IM发送消息幂等标识
-- ------------------------- info -------------------------

ALTER TABLE "im_message"
    ADD COLUMN IF NOT EXISTS "client_message_id" varchar(64) COLLATE "C" DEFAULT NULL;
COMMENT ON COLUMN "im_message"."client_message_id" IS '客户端消息幂等标识';

CREATE UNIQUE INDEX IF NOT EXISTS "uk_im_message_client"
    ON "im_message" ("conversation_id", "sender_id", "client_message_id");
