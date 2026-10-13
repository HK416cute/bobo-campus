package com.bobo.service;

import com.bobo.common.exception.BizException;
import com.bobo.config.props.BoboProperties;
import com.bobo.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 图片上传服务。
 * <p>
 * 文件落在 {@code bobo.upload.path}（容器里是挂载卷），按 {@code yyyy/MM} 分目录，
 * 文件名用 UUID，避免中文名 / 重名 / 路径穿越问题；
 * 返回的 URL 以 {@code bobo.upload.url-prefix} 开头，由 nginx 反向代理到同一目录。
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService {

    private static final DateTimeFormatter DIR_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM");

    private final BoboProperties properties;

    /** 单张上传 */
    public Map<String, Object> upload(MultipartFile file) {
        return doUpload(file);
    }

    /** 批量上传 */
    public List<Map<String, Object>> uploadBatch(List<MultipartFile> files) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (files == null || files.isEmpty()) {
            return result;
        }
        if (files.size() > 9) {
            throw new BizException("一次最多上传 9 张图片");
        }
        for (MultipartFile file : files) {
            result.add(doUpload(file));
        }
        return result;
    }

    private Map<String, Object> doUpload(MultipartFile file) {
        // 上传是登录用户才有的能力，这里再兜一层（拦截器已保证，但便于单测直接调用）
        AuthContext.userId();

        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的图片");
        }

        long maxBytes = properties.getUpload().getMaxSizeMb() * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new BizException("图片不能超过 " + properties.getUpload().getMaxSizeMb() + "MB");
        }

        String originalName = StringUtils.hasText(file.getOriginalFilename())
                ? Paths.get(file.getOriginalFilename()).getFileName().toString()
                : "image";
        String ext = extensionOf(originalName);
        Set<String> allowed = allowedExtensions();
        if (!allowed.contains(ext)) {
            throw new BizException("仅支持 " + String.join("、", allowed) + " 格式的图片");
        }

        String relativeDir = LocalDate.now().format(DIR_FORMAT);
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path root = Paths.get(properties.getUpload().getPath()).toAbsolutePath().normalize();
        Path targetDir = root.resolve(relativeDir).normalize();
        Path target = targetDir.resolve(fileName).normalize();

        // 双保险：确保最终路径仍在根目录内
        if (!target.startsWith(root)) {
            throw new BizException("非法的上传路径");
        }

        try {
            Files.createDirectories(targetDir);
            file.transferTo(target.toFile());
        } catch (IOException e) {
            log.error("图片保存失败: {}", target, e);
            throw new BizException("图片保存失败，请重试");
        }

        String prefix = properties.getUpload().getUrlPrefix();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        String url = prefix + "/" + relativeDir + "/" + fileName;

        Map<String, Object> map = new HashMap<>(4);
        map.put("url", url);
        map.put("name", originalName);
        map.put("size", file.getSize());
        return map;
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw new BizException("无法识别图片格式，请使用 jpg / png / webp 图片");
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private Set<String> allowedExtensions() {
        return java.util.Arrays.stream(properties.getUpload().getAllowExt().split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }
}
