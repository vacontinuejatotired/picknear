package com.hmdp.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * 本地文件上传实现 — dev 环境
 * 文件保存到 nginx 静态文件目录
 */
@Slf4j
@Service
@Profile("dev")
public class LocalFileServiceImpl implements FileService {

    private static final String UPLOAD_DIR = "E:\\nginx-1.18.0heima\\nginx-1.18.0\\html\\hmdp\\imgs";
    private static final String DOMAIN = "http://localhost:8082/imgs";

    @Override
    public String upload(InputStream inputStream, String originalFilename, String module) {
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        String uuid = UUID.randomUUID().toString();
        int d1 = uuid.hashCode() & 0xF;
        int d2 = (uuid.hashCode() >> 4) & 0xF;
        String relativePath = StrUtil.format("{}/{}/{}/{}.{}", module, d1, d2, uuid, suffix);

        File dir = new File(UPLOAD_DIR, StrUtil.format("/{}/{}/{}", module, d1, d2));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        FileUtil.writeFromStream(inputStream, new File(UPLOAD_DIR, relativePath));
        return DOMAIN + "/" + relativePath;
    }

    @Override
    public boolean delete(String fileUrl) {
        String relativePath = fileUrl.replace(DOMAIN, "");
        File file = new File(UPLOAD_DIR, relativePath);
        try {
            String canonicalPath = file.getCanonicalPath();
            String uploadDir = new File(UPLOAD_DIR).getCanonicalPath();
            if (!canonicalPath.startsWith(uploadDir)) {
                log.warn("路径穿越拦截: {} -> {}", fileUrl, canonicalPath);
                return false;
            }
            // 文件不存在时记录警告并返回 false
            if (!file.exists()) {
                log.warn("文件不存在，删除失败: {}", fileUrl);
                return false;
            }
        } catch (IOException e) {
            log.error("路径解析失败: {}", fileUrl, e);
            return false;
        }
        boolean deleted = FileUtil.del(file);
        if (deleted) {
            log.info("文件删除成功: {}", fileUrl);
        } else {
            log.warn("文件删除失败: {}", fileUrl);
        }
        return deleted;
    }

    @Override
    public String getDomain() {
        return DOMAIN;
    }
}
