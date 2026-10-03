-- ------------------------- info -------------------------
-- @@ver: 1_000_005
-- @@info: 增加个人聊天置顶设置
-- ------------------------- info -------------------------

ALTER TABLE "im_participant"
    ADD COLUMN IF NOT EXISTS "pinned" boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS "pinned_time" timestamp(3) NULL DEFAULT NULL;
COMMENT ON COLUMN "im_participant"."pinned" IS '个人聊天置顶';
COMMENT ON COLUMN "im_participant"."pinned_time" IS '个人聊天置顶时间';
