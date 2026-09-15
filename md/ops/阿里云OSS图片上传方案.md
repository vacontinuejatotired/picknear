---
status: current
source_of_truth:
  - picknear/src/main/java/com/hmdp/service/FileService.java
  - picknear/src/main/java/com/hmdp/service/impl/LocalFileServiceImpl.java
  - picknear/src/main/java/com/hmdp/service/impl/OssFileServiceImpl.java
  - picknear/src/main/java/com/hmdp/controller/UploadController.java
  - picknear/src/main/java/com/hmdp/config/OssConfig.java
  - picknear/src/main/java/com/hmdp/config/OssProperties.java
  - picknear/src/main/resources/application-prod.yaml
superseded_by:
---

# 阿里云 OSS 图片上传方案

本文描述当前文件上传实现。接口参数和客户端调用见
[API 接口文档](API接口文档.md)。

## 1. 文件服务

统一接口：

```java
FileService
  String upload(InputStream, String originalFilename, String module)
  boolean delete(String fileUrl)
  String getDomain()
```

实现通过 Spring Profile 切换：

| Profile | 实现 | 存储 |
|---|---|---|
| `dev` | `LocalFileServiceImpl` | 本地 Nginx 静态目录 |
| `prod` | `OssFileServiceImpl` | 阿里云 OSS |

默认 Profile 为 `dev`，生产由 Docker Compose 设置 `SPRING_PROFILES_ACTIVE=prod`。

## 2. 上传接口

```http
POST /upload/blog
Content-Type: multipart/form-data
```

参数：

| 参数 | 类型 | 说明 |
|---|---|---|
| `file` | MultipartFile | 图片文件 |
| `blogId` | Long | 博客 ID |

成功返回：

```json
{
  "success": true,
  "data": "https://.../blogs/123/..."
}
```

## 3. 上传校验

`UploadController` 当前执行：

| 校验 | 规则 |
|---|---|
| 文件名 | 非空 |
| 扩展名 | `jpg`、`jpeg`、`png`、`gif`、`webp` |
| 文件大小 | 最大 5 MB |
| 图片内容 | `ImageIO.read()` 必须能解析 |
| 像素尺寸 | 最大 4096 × 4096 |

Multipart 层也在 `application-dev.yaml`、`application-prod.yaml` 中限制：

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 5MB
      max-request-size: 5MB
```

## 4. Object Key

Controller 传入 module：

```text
blogs/{blogId}
```

文件实现追加散列和 UUID：

```text
{module}/{d1}/{d2}/{uuid}.{ext}
```

最终示例：

```text
blogs/123/4/9/a1b2c3d4e5f6.jpg
```

`d1` / `d2` 取自 UUID hash 的低位，避免单目录文件过多。

## 5. 本地开发实现

`LocalFileServiceImpl`：

- 根路径硬编码为 `E:\nginx-1.18.0heima\nginx-1.18.0\html\hmdp\imgs`；
- 域名硬编码为 `http://localhost:8082/imgs`；
- 上传前创建目录；
- 删除前解析 canonical path 并检查是否位于上传根目录内，防止路径穿越；
- 文件不存在时返回 `false`。

这些硬编码路径只适用于当前 Windows 开发环境，是已知迁移债。

## 6. OSS 生产实现

`OssFileServiceImpl`：

1. 读取扩展名并生成 UUID；
2. 计算 `d1` / `d2`；
3. 调用 `ossClient.putObject()`；
4. 返回完整 URL。

URL 规则：

```text
https://{bucket}.{endpoint}/{key}
```

## 7. OSS 配置

`application-prod.yaml`：

```yaml
app:
  oss:
    region: cn-beijing
    endpoint: oss-cn-beijing.aliyuncs.com
    bucket: ntwitm1
    access-key-id: ${OSS_ACCESS_KEY_ID}
    access-key-secret: ${OSS_ACCESS_KEY_SECRET}
```

`OssConfig` 仅在 `prod` Profile 创建 `OSS` Bean，并使用 V4 签名。

## 8. 删除接口

```http
DELETE /upload/blog/delete?url={fileUrl}
```

OSS 实现：

- 从 URL path 提取 Object Key；
- 调用 `deleteObject()`；
- 异常时返回 `false`。

本地实现执行路径穿越检查后删除文件。

## 9. 博客图片流程

1. `POST /blog` 创建博客；
2. `POST /upload/blog?blogId={id}` 逐张上传；
3. `PUT /blog/{id}/images` 保存最终 URL 数组；
4. 数据库 `tb_blog.images` 使用逗号分隔字符串。

## 10. 已知限制

1. 删除接口没有博客归属校验。当前只校验登录，未校验 URL 对应的图片是否属于当前用户或当前博客。
2. OSS Object Key 只从 URL path 提取，没有校验 host、bucket 或允许前缀。
3. 没有独立孤儿图片清理任务。代码中不存在 `OrphanImageCleanupJob`。
4. OSS Bucket 当前依赖公开读或可访问 URL，没有生成签名 URL。
5. 本地实现路径和域名硬编码，不适用于 Linux 开发机。
6. `ImageIO.read()` 发生在像素尺寸校验之前，超大压缩图片仍可能消耗较高内存。
7. 上传成功但后续博客图片列表保存失败时，会产生孤儿文件。

## 11. 修改约束

1. 上传和删除必须经 `FileService`。
2. 新增存储实现通过 Profile 或策略选择，不在 Controller 中判断存储类型。
3. 写操作必须在发布前校验真实图片内容。
4. 删除能力增强前，不扩大接口开放范围。
5. OSS 接入参数只维护在 `app.oss.*`。
6. 客户端调用顺序以 [API 接口文档](API接口文档.md) 为准。
