-- ------------------------- info -------------------------
-- @@ver: 1_000_003
-- @@info: 增加IM会话租户隔离
-- ------------------------- info -------------------------

ALTER TABLE `im_conversation`
    ADD COLUMN `tenant_id` varchar(32) NULL DEFAULT NULL COMMENT '租户ID';

ALTER TABLE `im_conversation`
    MODIFY COLUMN `single_key` varchar(110) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单聊双方规范化标识';

UPDATE `im_conversation`
SET `single_key` = CONCAT('g:', `single_key`)
WHERE `type` = 1 AND `single_key` IS NOT NULL;

ALTER TABLE `im_participant`
    ADD COLUMN `tenant_id` varchar(32) NULL DEFAULT NULL COMMENT '租户ID';

ALTER TABLE `im_message`
    ADD COLUMN `tenant_id` varchar(32) NULL DEFAULT NULL COMMENT '租户ID';

CREATE INDEX `idx_im_conversation_tenant_upd_time`
    ON `im_conversation` (`tenant_id`, `upd_time`);

CREATE INDEX `idx_im_participant_tenant_user`
    ON `im_participant` (`tenant_id`, `user_id`, `conversation_id`);

CREATE INDEX `idx_im_message_tenant_conv_id`
    ON `im_message` (`tenant_id`, `conversation_id`, `id`);
