package com.bobo.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型输出容错解析测试。
 *
 * <p>这些用例全部来自「模型输出不稳定」的真实形态：加了 Markdown 围栏、
 * 前面多一句"好的"、尾随解释、被 max_tokens 截断。它们不该让用户看到空白结果。
 *
 * @author bobo
 */
class SafeJsonParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SafeJsonParser parser = new SafeJsonParser(objectMapper);

    private String titleOf(String raw) {
        return parser.parse(raw, node -> {
            JsonNode title = node.get("title");
            return title == null || title.isNull() ? null : title.asText();
        });
    }

    @Test
    @DisplayName("纯 JSON 直接解析")
    void plainJson() {
        assertThat(titleOf("{\"title\":\"九成新 iPad\"}")).isEqualTo("九成新 iPad");
    }

    @Test
    @DisplayName("带 ```json 围栏时能剥掉围栏")
    void fencedJson() {
        String raw = "```json\n{\"title\":\"九成新 iPad\"}\n```";
        assertThat(titleOf(raw)).isEqualTo("九成新 iPad");
    }

    @Test
    @DisplayName("无语言标记的 ``` 围栏同样能处理")
    void fencedWithoutLanguage() {
        String raw = "```\n{\"title\":\"机械键盘\"}\n```";
        assertThat(titleOf(raw)).isEqualTo("机械键盘");
    }

    @Test
    @DisplayName("前后有解释文字时截取中间的 JSON 对象")
    void jsonWrappedInProse() {
        String raw = "好的，以下是提取结果：\n{\"title\":\"考研数学 1000 题\"}\n希望对你有帮助。";
        assertThat(titleOf(raw)).isEqualTo("考研数学 1000 题");
    }

    @Test
    @DisplayName("字符串值里出现花括号/转义引号时不会截错")
    void bracesInsideString() {
        String raw = "{\"title\":\"描述里带 } 和 \\\" 引号\"}";
        assertThat(parser.extractJson(raw)).isEqualTo(raw);
        assertThat(titleOf(raw)).isEqualTo("描述里带 } 和 \" 引号");
    }

    @Test
    @DisplayName("被截断的不完整 JSON 返回 null（由上层降级，而不是抛异常）")
    void truncatedJson() {
        assertThat(parser.extractJson("{\"title\":\"九成新")).isNull();
        assertThat(titleOf("{\"title\":\"九成新")).isNull();
    }

    @Test
    @DisplayName("完全没有 JSON 时返回 null")
    void noJsonAtAll() {
        assertThat(parser.extractJson("抱歉，我无法完成这个请求。")).isNull();
        assertThat(parser.extractJson("")).isNull();
        assertThat(parser.extractJson(null)).isNull();
    }

    @Test
    @DisplayName("业务校验不通过（缺少标题字段）时返回 null，交给降级")
    void missingField() {
        assertThat(titleOf("{\"description\":\"没有标题\"}")).isNull();
    }

    @Test
    @DisplayName("数组外包一层时取内层对象（模型偶尔会包一层数组）")
    void arrayWrappedObject() {
        // 行为说明：extractJson 只认 '{' 起始，因此 [{"title":"x"}] 会取到内层对象。
        // 这是有用的容错——模型把结果包进数组时不该直接判失败。
        assertThat(parser.extractJson("[{\"title\":\"x\"}]")).isEqualTo("{\"title\":\"x\"}");
        assertThat(titleOf("[{\"title\":\"被数组包住\"}]")).isEqualTo("被数组包住");
    }
}
