## 改动说明
<!-- 一句话：改了什么、为什么 -->

## 文档同步（按 scripts/docs/doc-map.json）
- [ ] 已更新对应文档（列出路径）：
- [ ] 本次改动无需文档更新（**需同时满足**：填写下方理由 + 给本 PR 打 `docs-exempt` 标签）
  理由：
- [ ] 生成物已同步（如适用：已运行生成脚本并提交产物）

<!--
豁免成立需要三条同时满足（缺一即 CI 失败）：
  1. 本 PR 打有 `docs-exempt` 标签；
  2. 标签时间晚于最近一次推送（synchronize）——先打标签后推送新提交会失效，需重新打标签；
  3. 上方"理由："行非空（按行匹配，必须写在行首）。
豁免与 head SHA 绑定，换 SHA 即重审；成立时 CI 在 job summary 记录规则/理由/SHA。

说明：改了 must 级路径（API/配置/协议/安全行为）就必须更新对应文档，或走上面的豁免通道。
不允许空白豁免——无理由或标签过期一律失败。
-->

## 验证
- [ ] 本地执行过 `python scripts/docs/check_docs.py`
- [ ] 本地执行过 `python scripts/docs/doc_sync_check.py --validate`
