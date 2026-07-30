package com.hmdp.service;

import java.io.InputStream;

/**
 * 文件上传服务接口 — 支持本地存储和阿里云 OSS 两种实现
 * upload() 接收 InputStream，不依赖 Spring MultipartFile
 * 通过 @Profile 切换：dev → LocalFileServiceImpl，prod → OssFileServiceImpl
 */
public interface FileService {

    /** 上传文件，返回完整可访问 URL */
    String upload(InputStream inputStream, String originalFilename, String module);

    /** 删除文件 */
    boolean delete(String fileUrl);

    /** 获取文件访问域名前缀 */
    String getDomain();
}
