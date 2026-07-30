package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * 文件上传控制器 — 博客图片上传/删除，委托 FileService 处理
 * dev → LocalFileServiceImpl, prod → OssFileServiceImpl
 */
@Slf4j
@RestController
@RequestMapping("upload")
@Tag(name = "文件上传模块", description = "博客图片上传与删除接口")
public class UploadController {

    @Resource
    private FileService fileService;

    private static final Set<String> ALLOWED_TYPES = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024L;
    private static final int MAX_IMAGE_WIDTH = 4096;
    private static final int MAX_IMAGE_HEIGHT = 4096;

    @PostMapping("blog")
    @Operation(summary = "上传博客图片", description = "上传博客图片到文件服务，支持多种图片格式")
    public Result uploadImage(
            @Parameter(description = "图片文件") @RequestParam("file") MultipartFile image,
            @Parameter(description = "博客ID") @RequestParam("blogId") Long blogId) {
        try {
            // === ① 文件名非空校验 ===
            String originalFilename = image.getOriginalFilename();
            if (originalFilename == null || originalFilename.isBlank()) {
                return Result.fail("文件名不能为空");
            }

            // === ② 文件扩展名校验 ===
            String ext = StrUtil.subAfter(originalFilename, ".", true).toLowerCase();
            if (StrUtil.isBlank(ext)) {
                return Result.fail("文件无扩展名，无法识别类型");
            }
            if (!ALLOWED_TYPES.contains(ext)) {
                return Result.fail("不支持的文件类型，仅允许: " + ALLOWED_TYPES);
            }

            // === ③ 文件大小校验 ===
            if (image.getSize() > MAX_FILE_SIZE) {
                return Result.fail("文件过大，最大允许 5MB");
            }

            // === ④ MIME/魔数校验 — 通过 ImageIO 读取文件头 ===
            InputStream inputStream = image.getInputStream();
            BufferedImage bufferedImage = ImageIO.read(inputStream);
            if (bufferedImage == null) {
                return Result.fail("文件内容无法识别为有效图片");
            }

            // === ⑤ 像素尺寸限制 ===
            int width = bufferedImage.getWidth();
            int height = bufferedImage.getHeight();
            if (width > MAX_IMAGE_WIDTH || height > MAX_IMAGE_HEIGHT) {
                return Result.fail("图片尺寸过大，最大允许 " + MAX_IMAGE_WIDTH + "×" + MAX_IMAGE_HEIGHT + " 像素");
            }

            // === ⑥ 委托 FileService 上传 ===
            // module = "blogs/{blogId}"，blogId 做目录分组，替代 d1/d2 哈希散列
            String url = fileService.upload(image.getInputStream(), originalFilename, "blogs/" + blogId);
            log.info("文件上传成功，blogId={}, url={}", blogId, url);
            return Result.ok(url);
        } catch (IOException e) {
            log.error("文件上传 IO 异常", e);
            return Result.fail("文件上传失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/blog/delete")
    @Operation(summary = "删除博客图片", description = "删除已上传的博客图片")
    public Result deleteBlogImg(
            @Parameter(description = "图片URL") @RequestParam("url") String fileUrl) {
        boolean deleted = fileService.delete(fileUrl);
        if (!deleted) {
            log.warn("文件删除失败或不存在: {}", fileUrl);
            return Result.fail("文件删除失败");
        }
        return Result.ok();
    }
}
