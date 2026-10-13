# nginx 站点配置说明

## 两份配置

| 文件 | 用途 |
|---|---|
| `default.conf` | 生产配置：只暴露前端、`/api`、`/upload` |
| `default.with-docs.conf` | 调试配置：额外暴露 `/swagger-ui` 与 `/v3/api-docs` |

切换方式（项目根目录执行）：

```bash
cp docker/bobo-web/nginx/default.with-docs.conf docs-nginx.conf
# 编辑 docker-compose.yml，把 bobo-web 的挂载源换成 ./docs-nginx.conf
docker compose up -d bobo-web
```

为什么不用环境变量开关（比如 `if ($bobo_expose_docs = 0)`）：
nginx 的 `if` 是 rewrite 模块的一部分，在 location 里行为怪异且有已知坑；
用「两个文件 + 挂载切换」更直白，出了问题一眼能找到当前生效的是哪份配置。

## 两个已经踩过的坑（改配置前务必先看）

### 1. `/upload/` 必须要 `^~`，否则上传的图片全部 404

`default.conf` 里有一条给前端静态资源做长缓存的正则 location：

```nginx
location ~* \.(?:js|css|...|png|jpe?g|...)$ { ... try_files $uri =404; }
```

nginx 的匹配顺序是「先按前缀找出候选，再看正则（正则优先于前缀）」。如果 `/upload/`
只写成普通前缀 `location /upload/`，那么 `/upload/2026/10/xxx.png` 会**先命中上面那条正则**，
nginx 于是去 `root`（`/usr/share/nginx/html`）下找这个 png —— 文件当然不在那儿，结果 404。
日志里看得到请求、目录里也看得到文件、权限也没问题，但就是 404，很容易误判成卷没挂上。

加上 `^~` 后，前缀命中就直接采用该 location、跳过全部正则：

```nginx
location ^~ /upload/ { alias /data/upload/; ... }
```

### 2. 挡脚本的规则必须嵌套在 `^~` 内部

`^~` 的语义是「跳过所有顶层正则」，所以如果把「拒绝脚本扩展名」的正则写在
`^~ /upload/` **外面**，它会被一并跳过 —— `evil.php` 会变成 200 直接下载，
这比 404 严重得多。

正确写法是嵌套（嵌套 location 在正则 location 与 `^~` 前缀 location 内都合法）：

```nginx
location ^~ /upload/ {
    alias /data/upload/;
    location ~* \.(?:php|phtml|jsp|sh|py|pl|cgi|html?)$ { deny all; }
}
```

曾经试过「在非 `^~` 的 `location /upload/` 里嵌套 location」来同时解决这两点，
那是无效的（非正则前缀 location 不允许嵌套），会导致 alias 解析异常、图片仍然 404。

### 3. 改完配置务必这样验证

```bash
docker compose exec -T bobo-web nginx -t              # 语法检查
curl -I http://127.0.0.1/upload/<某个真实文件>          # 期望 200 + Content-Type: image/*
docker compose exec -T bobo-server sh -c 'echo x > /app/data/upload/evil.php'
curl -o /dev/null -w '%{http_code}\n' http://127.0.0.1/upload/evil.php   # 期望 403
curl -o /dev/null -w '%{http_code}\n' http://127.0.0.1/admin/items       # 期望 200（SPA 回落）
```
