# 01myBlog Frontend

这是 `01myBlog` 的前端项目，使用 Vue 3 + TypeScript + Vite + Pinia + Naive UI。

## 当前功能

- 首页文章列表、分类、标签
- 文章详情、Markdown 渲染、评论区
- 登录、注册、GitHub OAuth 登录入口
- 写文章 / 编辑文章
- 个人中心、公开用户主页
- 文章点赞 / 收藏
- 评论点赞
- 关注作者、关注列表、粉丝列表

## 开发命令

```bash
npm install
npm run dev
npm run build
```

## 约定

- 前端请求统一走 `/api`，由 Vite proxy 或 Nginx 转发到后端。
- 页面组件不要直接写接口地址，统一通过 `src/api/*.ts` 调用。
- 新增或修改接口前先看 `../docs/api-convention.md`。
- 前端交接笔记见 `../docs/frontend-notes.md`。
