-- ------------------------- info -------------------------
-- @@ver: 1_000_001
-- @@info: 完善fa-im字段约束和查询索引
-- ------------------------- info -------------------------

UPDATE "im_participant"
SET "unread_count" = 0
WHERE "unread_count" IS NULL;

UPDATE "im_message"
SET "is_withdrawn" = false
WHERE "is_withdrawn" IS NULL;

ALTER TABLE "im_participant"
    ALTER COLUMN "unread_count" SET DEFAULT 0;
ALTER TABLE "im_participant"
    ALTER COLUMN "unread_count" SET NOT NULL;

ALTER TABLE "im_message"
    ALTER COLUMN "is_withdrawn" SET DEFAULT false;
ALTER TABLE "im_message"
    ALTER COLUMN "is_withdrawn" SET NOT NULL;

CREATE INDEX "idx_im_message_conversation_id_id"
    ON "im_message" ("conversation_id", "id");

CREATE INDEX "idx_im_participant_user_id"
    ON "im_participant" ("user_id");
