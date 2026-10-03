-- ------------------------- info -------------------------
-- @@ver: 1_000_006
-- @@info: 增加个人消息免打扰设置
-- ------------------------- info -------------------------

ALTER TABLE "im_participant" ADD COLUMN IF NOT EXISTS "muted" boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN "im_participant"."muted" IS '个人消息免打扰';
