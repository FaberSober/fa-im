-- ------------------------- info -------------------------
-- @@ver: 1_000_006
-- @@info: 增加个人消息免打扰设置
-- ------------------------- info -------------------------

ALTER TABLE `im_participant` ADD COLUMN `muted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '个人消息免打扰';
