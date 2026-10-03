-- ------------------------- info -------------------------
-- @@ver: 1_000_005
-- @@info: 增加个人聊天置顶设置
-- ------------------------- info -------------------------

ALTER TABLE `im_participant`
    ADD COLUMN `pinned` tinyint(1) NOT NULL DEFAULT 0 COMMENT '个人聊天置顶',
    ADD COLUMN `pinned_time` timestamp(3) NULL DEFAULT NULL COMMENT '个人聊天置顶时间';
