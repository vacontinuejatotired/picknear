package com.hmdp.user.profile;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hmdp.dto.Result;
import com.hmdp.service.FileService;
import com.hmdp.user.dto.ProfileUpdateDTO;
import com.hmdp.user.entity.UserInfo;
import com.hmdp.user.entity.UserinfoCache;
import com.hmdp.user.service.IUserInfoService;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.cache.CaffeineConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.util.Set;

/**
 * 个人资料服务 — 资料更新与头像上传（user 域收敛，P2-S6）
 * <p>
 * 职责边界（自 UserServiceImpl.updateProfile + UserController.uploadIcon 迁出，行为等价）：
 * <ul>
 *   <li>updateProfile：昵称/头像/城市/简介更新 + 旧头像删除 + Caffeine 缓存刷新</li>
 *   <li>uploadIcon：头像格式/大小校验 + FileService 上传（icons/ 目录）</li>
 * </ul>
 * 头像白名单常量从 Controller 下沉至此。
 * </p>
 */
@Slf4j
@Component
public class ProfileService {

    public static final Set<String> ALLOWED_ICON_TYPES = Set.of("jpg", "jpeg", "png", "gif", "webp");
    public static final long MAX_ICON_SIZE = 2 * 1024 * 1024L;

    @Resource
    private IUserInfoService userInfoService;
    @Resource
    private FileService fileService;
    @Resource(name = "userinfoCache")
    private LoadingCache<String, UserinfoCache> userinfoCaffeine;

    /** 编辑个人资料 — nickName/icon/city/introduce 均为可选 */
    public Result updateProfile(ProfileUpdateDTO dto) {
        Long userId = UserHolder.getUserId();

        // 从 DB 查出现有记录，避免 new UserInfo() 时默认值 "" 覆盖原有字段
        UserInfo userInfo = userInfoService.getById(userId);
        if (userInfo == null) {
            userInfo = new UserInfo();
            userInfo.setUserId(userId);
        }
        boolean needUpdateInfo = false;
        if (dto.getNickName() != null) {
            String nickName = dto.getNickName().strip();
            if (nickName.isEmpty()) {
                return Result.fail("昵称不能为空");
            }
            userInfo.setNickName(nickName);
            needUpdateInfo = true;
        }
        if (dto.getIcon() != null) {
            // 新旧 icon 不同时删除旧文件
            String oldIcon = userInfo.getIcon();
            if (oldIcon != null && !oldIcon.isEmpty()
                    && !oldIcon.equals(dto.getIcon())) {
                fileService.delete(oldIcon);
                log.info("已删除旧头像: userId={}, oldIcon={}", userId, oldIcon);
            }
            userInfo.setIcon(dto.getIcon());
            needUpdateInfo = true;
        }
        if (dto.getCity() != null) {
            userInfo.setCity(dto.getCity());
            needUpdateInfo = true;
        }
        if (dto.getIntroduce() != null) {
            userInfo.setIntroduce(dto.getIntroduce());
            needUpdateInfo = true;
        }
        if (needUpdateInfo) {
            userInfoService.updateById(userInfo);
            // 从 DB 查完整数据后刷新 Caffeine 缓存
            String userInfoKey = CaffeineConstants.USERINFO_CACHE_KEY + userId;
            UserInfo fresh = userInfoService.getById(userId);
            if (fresh != null) {
                UserinfoCache newCache = new UserinfoCache(userId, fresh.getNickName(), fresh.getIcon());
                userinfoCaffeine.put(userInfoKey, newCache);
                log.debug("已更新用户缓存 userId={}", userId);
            }
        }

        log.info("用户 {} 更新个人资料: nickName={}, icon={}, city={}, introduce={}",
                userId, dto.getNickName(), dto.getIcon(), dto.getCity(), dto.getIntroduce());
        return Result.ok();
    }

    /** 上传头像到 FileService（icons/ 目录），含基本校验 */
    public String uploadIcon(MultipartFile iconFile) throws IOException {
        String originalFilename = iconFile.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String ext = cn.hutool.core.util.StrUtil.subAfter(originalFilename, ".", true).toLowerCase();
        if (!ALLOWED_ICON_TYPES.contains(ext)) {
            throw new IllegalArgumentException("不支持的头像格式，仅允许: " + ALLOWED_ICON_TYPES);
        }
        if (iconFile.getSize() > MAX_ICON_SIZE) {
            throw new IllegalArgumentException("头像文件过大，最大允许 2MB");
        }
        return fileService.upload(iconFile.getInputStream(), originalFilename, "icons");
    }
}
