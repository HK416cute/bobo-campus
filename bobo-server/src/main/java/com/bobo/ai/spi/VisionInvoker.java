package com.bobo.ai.spi;

import java.util.List;

/**
 * 具备视觉能力（可接收图片）的模型调用器抽象。
 *
 * <p>与 {@link ChatInvoker} 分开而不是加一个默认方法，原因有二：
 * <ol>
 *   <li><b>能力显式化</b>：调价场景必须知道「当前模型到底能不能看图」，
 *       否则无 Vision 的模型会静默忽略图片，返回一段看似合理的文字估价；</li>
 *   <li><b>不污染已有实现</b>：DeepSeek 是纯文本模型，让它实现一个永远抛异常的
 *       {@code generateWithImages} 没有意义；独立接口让「不支持」这件事是编译期明确的。</li>
 * </ol>
 *
 * <p>实现类当前是 DeepSeek（OpenAI 兼容 Chat Completions 的 image_url 内容块），
 * 但接口不绑定厂商，以后接其他多模态模型只需新增实现。
 *
 * @author bobo
 */
public interface VisionInvoker {

    /** 实现名，写入调用流水的 provider 字段（如 deepseek-vision / noop） */
    String name();

    /** 当前使用的模型名，写入流水 */
    String model();

    /** 是否可用（开关打开、Key 非空、客户端构建成功） */
    boolean available();

    /** 不可用原因，可直接展示给用户/管理员；可用时返回 null */
    String unavailableReason();

    /**
     * 发起一次带图片的生成调用。
     *
     * @param systemPrompt 系统提示词（稳定内容，实现类会在其上打 Prompt Caching 断点）
     * @param userPrompt   本次任务文本
     * @param images       图片（已压缩/已编码），可为空列表表示纯文本
     * @param maxTokens    最大输出 token
     * @return 文本回复与 token 用量
     * @throws ChatInvoker.ChatInvokeException 网络、鉴权、限流、超时或响应不可解析
     */
    ChatInvoker.ChatReply generate(String systemPrompt, String userPrompt,
                                   List<ImageAttachment> images, int maxTokens);

    /**
     * 一张待发送的图片。
     *
     * <p>用 {@code byte[]} 而不是 URL：DeepSeek 支持 base64 内联、外部 URL 与
     * Files API 三种方式；本地部署的上传 URL（如 /upload/2026/11/x.jpg）在公网不可达，
     * 传 URL 会让模型服务抓不到图，因此这里统一用 base64。
     *
     * @param mediaType 如 image/jpeg、image/png、image/webp
     * @param data      图片二进制
     */
    record ImageAttachment(String mediaType, byte[] data) {
    }
}
