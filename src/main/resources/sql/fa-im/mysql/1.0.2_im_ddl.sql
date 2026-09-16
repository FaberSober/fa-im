-- ------------------------- info -------------------------
-- @@ver: 1_000_002
-- @@info: 增加单聊双方唯一标识
-- ------------------------- info -------------------------

ALTER TABLE `im_conversation`
    ADD COLUMN `single_key` varchar(65) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '单聊双方规范化标识' AFTER `user_ids`;

UPDATE `im_conversation`
SET `single_key` = CONCAT(
    JSON_UNQUOTE(JSON_EXTRACT(`user_ids`, '$[0]')),
    ',',
    JSON_UNQUOTE(JSON_EXTRACT(`user_ids`, '$[1]'))
)
WHERE `type` = 1;

CREATE UNIQUE INDEX `uk_im_conversation_single_key`
    ON `im_conversation` (`single_key`);
