# GitHub Actions Pre-release 修复

此补丁将自动发布行为改为：

- 构建与校验成功后始终创建 GitHub **Pre-release**。
- 已配置正式签名 Secrets：发布签名 release APK，但仍标记为 Pre-release。
- 未配置正式签名 Secrets：发布 `UNSTABLE-debug` APK，并标记为 Pre-release。
- 保留 checksums、provenance、tsaQB provenance/checksums 和 release notes。
- 已存在相同 tag 时仍拒绝覆盖；手动 `force=true` 会继续使用 `-rN` 新 tag。

覆盖 `.github/workflows/android-gui-upstream-release.yml` 后提交并 push，再手动 Run workflow 即可。
