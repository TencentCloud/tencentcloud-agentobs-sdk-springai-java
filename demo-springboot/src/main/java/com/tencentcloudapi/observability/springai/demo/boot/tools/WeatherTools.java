package com.tencentcloudapi.observability.springai.demo.boot.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 一个用假数据模拟的"天气查询"工具。
 *
 * <p>通过 {@link Tool @Tool} 注解把普通 Java 方法暴露为可被大模型调用的
 * function。Spring AI 的 {@code ToolCallingManager} 会在 ChatModel 返回
 * {@code finish_reason=tool_calls} 时自动匹配、反射调用，并把结果拼回 messages
 * 做第二轮 ChatModel 调用。整个过程由本仓库 CLS SDK 自动采集为一个独立的
 * {@code tool} span，挂在同一次 ChatClient 调用对应的 agent span 下。
 */
@Component
public class WeatherTools {

    private static final Map<String, String> DB = Map.of(
            "北京", "晴，18°C，东北风 2 级，空气质量优",
            "上海", "多云，22°C，东南风 3 级，空气质量良",
            "深圳", "阵雨，26°C，南风 4 级，空气质量良",
            "广州", "阴，25°C，东南风 2 级，空气质量良",
            "杭州", "晴转多云，20°C，西北风 2 级，空气质量优",
            "成都", "小雨，17°C，无持续风向，空气质量良"
    );

    @Tool(description = "查询指定中国城市的实时天气情况。参数 city 必须是中文城市名，例如：北京、上海、深圳、广州、杭州、成都。")
    public String getWeather(
            @ToolParam(description = "中文城市名，如：北京") String city) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("city", city);
        result.put("weather", DB.getOrDefault(city, "暂无该城市的实时天气数据"));
        result.put("provider", "demo-mock-weather-service");
        // 用最简单的 JSON 字符串返回，避免引入 Jackson 的 ObjectMapper 依赖
        return "{\"city\":\"" + result.get("city")
                + "\",\"weather\":\"" + result.get("weather")
                + "\",\"provider\":\"" + result.get("provider") + "\"}";
    }
}
