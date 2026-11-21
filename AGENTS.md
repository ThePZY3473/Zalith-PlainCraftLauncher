# 项目约定

## 这是 ZPCL-Launcher 的 fork

本仓库基于上游 [zuanxu53-hub/ZPCL-Launcher](https://github.com/zuanxu53-hub/ZPCL-Launcher)
（更上游是 [ZalithLauncher2](https://github.com/ZalithLauncher/ZalithLauncher2)），
额外维护 minSdk 24（Android 7.0）兼容与若干本地化修改。

## 从上游同步代码后

**必须**运行一次：

```bash
python3 scripts/apply-fork-docs.py
```

它会以幂等方式重新应用本 fork 对 `README.md` / `NOTICE.md` 的定制（仓库地址、
发布方式说明、「关于本 fork」章节等），避免被上游版本覆盖。

脚本输出 `WARN` 表示上游改动了对应段落，需要手动核对后再提交。

## 其他注意

- `*.bak` 是本地备份，不提交。
- 签名密钥 `ZalithLauncher/zalith_launcher.jks`（别名 `cutepzy`）随仓库分发，
  密码来自环境变量 `STORE_PASSWORD` / `KEY_PASSWORD` 或根目录
  `.store_password.txt` / `.key_password.txt`（已 gitignore）。
