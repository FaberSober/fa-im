-- ------------------------- info -------------------------
-- @@ver: 1_000_003
-- @@info: 增加IM会话租户隔离
-- ------------------------- info -------------------------

ALTER TABLE "im_conversation"
    ADD COLUMN IF NOT EXISTS "tenant_id" varchar(32) DEFAULT NULL;
COMMENT ON COLUMN "im_conversation"."tenant_id" IS '租户ID';

ALTER TABLE "im_conversation"
    ALTER COLUMN "single_key" TYPE varchar(110);
COMMENT ON COLUMN "im_conversation"."single_key" IS '单聊双方规范化标识';

UPDATE "im_conversation"
SET "single_key" = 'g:' || "single_key"
WHERE "type" = 1 AND "single_key" IS NOT NULL;

ALTER TABLE "im_participant"
    ADD COLUMN IF NOT EXISTS "tenant_id" varchar(32) DEFAULT NULL;
COMMENT ON COLUMN "im_participant"."tenant_id" IS '租户ID';

ALTER TABLE "im_message"
    ADD COLUMN IF NOT EXISTS "tenant_id" varchar(32) DEFAULT NULL;
COMMENT ON COLUMN "im_message"."tenant_id" IS '租户ID';

CREATE INDEX "idx_im_conversation_tenant_upd_time"
    ON "im_conversation" ("tenant_id", "upd_time");

CREATE INDEX "idx_im_participant_tenant_user"
    ON "im_participant" ("tenant_id", "user_id", "conversation_id");

CREATE INDEX "idx_im_message_tenant_conv_id"
    ON "im_message" ("tenant_id", "conversation_id", "id");
