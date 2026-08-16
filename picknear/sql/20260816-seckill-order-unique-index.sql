-- ============================================================
-- 秒杀订单唯一索引（voucherId + userId）— S-1 幂等兜底
-- 决策：方案 D2（2026-08 拍板"P1 只拆代码、唯一索引后补独立提交"）
-- 作用：MQ 消费/落单并发下 DB 层防重复订单（与 Redis Lua 重复判断互补）
-- ============================================================
-- ⚠️ 执行前请先清理历史重复数据（如有）：
--   DELETE t1 FROM tb_voucher_order t1
--   INNER JOIN tb_voucher_order t2
--     ON t1.user_id = t2.user_id AND t1.voucher_id = t2.voucher_id AND t1.id > t2.id;
-- ============================================================

ALTER TABLE tb_voucher_order
    ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id);
