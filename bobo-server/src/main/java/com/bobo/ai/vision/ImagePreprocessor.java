package com.bobo.ai.vision;

import com.bobo.ai.spi.VisionInvoker;
import com.bobo.common.exception.BizException;
import com.bobo.config.props.BoboProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 图片预处理：把上传目录里的图片读成「可安全送给视觉模型」的二进制。
 *
 * <p>三件事，每一件都对应一个真实的坑：
 * <ol>
 *   <li><b>路径校验</b>：URL 来自前端，必须解析到上传根目录之内，防目录穿越；
 *       只允许本项目上传目录里的图片，避免把内网/本机任意文件读出来送给模型（SSRF 变种）。</li>
 *   <li><b>压缩到 1568px</b>：技术规格书 3.3 的性能要求。视觉 token 与边长正相关，
 *       不压缩会显著推高单次估价成本，且对成色判断几乎没有增益。</li>
 *   <li><b>限制数量与体积</b>：DeepSeek 单图上限 32MB、单请求 48MB，且图片越多 token 越贵。
 *       这里最多取 3 张（封面 + 两张细节），并压到 4.8MB 以内，足够判断成色。</li>
 * </ol>
 *
 * @author bobo
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImagePreprocessor {

    /**
     * 视觉模型长边上限。DeepSeek 会在服务端把图片缩放到约 1300×1300（每图最多 1024 token），
     * 这里提前压到 1568px 主要目的是减少 base64 体积与上传耗时，对成色判断没有损失。
     */
    private static final int MAX_LONG_EDGE = 1568;

    /** 单图最大字节数（DeepSeek 单图允许 32MB；这里压得更小，请求更快也更省带宽） */
    private static final long MAX_IMAGE_BYTES = 4_800_000L;

    /** 一次定价最多送的图片数：封面 + 细节 */
    private static final int MAX_IMAGE_COUNT = 3;

    private final BoboProperties properties;

    /**
     * 把图片 URL 列表转成可发送的附件。
     *
     * @param urls 商品图片 URL（第一张为封面），可为空
     * @return 最多 {@value #MAX_IMAGE_COUNT} 张；全部无效时返回空列表（调用方走纯文本）
     */
    public List<VisionInvoker.ImageAttachment> prepare(List<String> urls) {
        List<VisionInvoker.ImageAttachment> result = new ArrayList<>();
        if (urls == null || urls.isEmpty()) {
            return result;
        }
        for (String url : urls) {
            if (result.size() >= MAX_IMAGE_COUNT) {
                break;
            }
            if (!StringUtils.hasText(url)) {
                continue;
            }
            try {
                result.add(prepareOne(url.trim()));
            } catch (BizException e) {
                // 单张图失效不应该让整次估价失败：跳过它，用剩下的图继续
                log.warn("跳过无法读取的商品图片：url={}, reason={}", url, e.getMessage());
            }
        }
        return result;
    }

    public VisionInvoker.ImageAttachment prepareOne(String url) {
        Path path = resolveUploadPath(url);
        byte[] bytes;
        try {
            if (Files.size(path) > properties.getUpload().getMaxSizeMb() * 1024 * 1024) {
                throw new BizException("图片超过 " + properties.getUpload().getMaxSizeMb() + "MB，无法评估");
            }
            bytes = Files.readAllBytes(path);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("图片读取失败，请重新上传后再试");
        }

        String mediaType = detectMediaType(bytes);
        if (mediaType == null) {
            throw new BizException("仅支持 JPG / PNG / WebP / GIF 图片");
        }

        BufferedImage image = decode(bytes);
        if (image == null) {
            // ImageIO 读不了的格式（例如未装插件的 WebP）：体积在限制内就原样发送
            if (bytes.length <= MAX_IMAGE_BYTES) {
                return new VisionInvoker.ImageAttachment(mediaType, bytes);
            }
            throw new BizException("图片过大且无法压缩，请换一张 JPG/PNG 图片");
        }

        boolean needResize = image.getWidth() > MAX_LONG_EDGE || image.getHeight() > MAX_LONG_EDGE;
        if (!needResize && bytes.length <= MAX_IMAGE_BYTES) {
            return new VisionInvoker.ImageAttachment(mediaType, bytes);
        }

        byte[] compressed = compressToJpeg(image);
        if (compressed.length > MAX_IMAGE_BYTES) {
            throw new BizException("图片压缩后仍超过 5MB，请换一张更小的图片");
        }
        return new VisionInvoker.ImageAttachment("image/jpeg", compressed);
    }

    /**
     * 把上传 URL 解析为磁盘路径。
     *
     * <p>URL 可能带站点子路径（例如 {@code /bobo-campus/upload/2026/11/x.jpg}），
     * 因此不能简单用 {@code startsWith(prefix)} 截断——先找配置的 url-prefix，
     * 找不到再退回到 {@code /upload/} 这个约定路径。
     */
    Path resolveUploadPath(String url) {
        String clean = url;
        int query = clean.indexOf('?');
        if (query >= 0) {
            clean = clean.substring(0, query);
        }
        if (clean.contains("://")) {
            throw new BizException("只支持本站上传的图片，请重新上传");
        }

        String prefix = normalizePrefix(properties.getUpload().getUrlPrefix());
        String relative;
        int index = clean.indexOf(prefix);
        if (index >= 0) {
            relative = clean.substring(index + prefix.length());
        } else {
            int fallback = clean.indexOf("/upload/");
            if (fallback < 0) {
                throw new BizException("图片地址不在本站上传目录内");
            }
            relative = clean.substring(fallback + "/upload".length());
        }
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        if (!StringUtils.hasText(relative)) {
            throw new BizException("图片地址无效");
        }

        Path root = Paths.get(properties.getUpload().getPath()).toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new BizException("非法的图片路径");
        }
        if (!Files.isRegularFile(target)) {
            throw new BizException("图片不存在或已被删除");
        }
        return target;
    }

    private String normalizePrefix(String prefix) {
        String value = StringUtils.hasText(prefix) ? prefix.trim() : "/upload";
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** 按文件头判断类型，不信任扩展名（扩展名可以随便改） */
    private String detectMediaType(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length > 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            return "image/gif";
        }
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private BufferedImage decode(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 等比缩放到长边 1568px 以内并转 JPEG。
     *
     * <p>透明 PNG 先铺白底：直接转 JPEG 会把 alpha 变黑，模型可能把黑色当成磕碰/烧屏。
     */
    private byte[] compressToJpeg(BufferedImage source) {
        double scale = Math.min(1.0, (double) MAX_LONG_EDGE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.85f);
            }
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(target, null, null), param);
            } finally {
                writer.dispose();
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException("图片压缩失败，请换一张图片再试");
        }
    }
}
