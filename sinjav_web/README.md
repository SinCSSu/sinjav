# sinJav Web UI

sinJav 后端的 Web 管理界面（Vue 3 + TypeScript + Vite + Element Plus）。

## 功能

- **仪表盘**：影片统计、运行中任务实时卡片、触发扫描 / 刮削 / 整理 / 流水线
- **影片库**：封面网格、关键字搜索、状态过滤、排序分页、详情抽屉（剧照 / 文件记录）
- **文件记录**：按番号、识别状态、整理状态过滤
- **任务管理**：进度条、失败明细、运行中任务自动轮询
- **设置**：刮削 / 整理配置的查看与修改（PUT 立即生效）

## 开发

```powershell
cd sinjav_web
npm install
npm run dev    # http://localhost:5173，/api 代理到 localhost:8656
```

需先启动后端：`./gradlew bootRun`（端口 8656）。

## 构建与部署

```powershell
npm run build  # 产物在 dist/
```

将 `dist/` 内容拷贝到后端 `src/main/resources/static/`，即可由 Spring Boot 统一托管
（前端使用 hash 路由，无需后端配置 SPA fallback）。
