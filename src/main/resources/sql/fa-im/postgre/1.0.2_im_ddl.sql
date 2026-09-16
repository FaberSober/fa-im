-- ------------------------- info -------------------------
-- @@ver: 1_000_002
-- @@info: 增加单聊双方唯一标识
-- ------------------------- info -------------------------

ALTER TABLE "im_conversation"
    ADD COLUMN "single_key" varchar(65) NULL DEFAULT NULL;
COMMENT ON COLUMN "im_conversation"."single_key" IS '单聊双方规范化标识';

UPDATE "im_conversation"
SET "single_key" = ("user_ids"::jsonb ->> 0) || ',' || ("user_ids"::jsonb ->> 1)
WHERE "type" = 1;

CREATE UNIQUE INDEX "uk_im_conversation_single_key"
    ON "im_conversation" ("single_key");
