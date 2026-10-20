package com.tencentcloudapi.observability.springai.demo.boot.tools;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 一个"获取当前时间"工具。
 *
 * <p>和 {@link WeatherTools} 一样通过 {@link Tool @Tool} 注解暴露；本工具
 * 不依赖任何外部服务，返回的是 JVM 当前时钟按指定时区格式化后的字符串。
 */
@Component
public class TimeTools {

    @Tool(description = "查询指定时区的当前时间。参数 timezone 必须是 IANA 时区名，例如：Asia/Shanghai、UTC、America/Los_Angeles。")
    public String getCurrentTime(
            @ToolParam(description = "IANA 时区名，如 Asia/Shanghai") String timezone) {

        try {
            String formatted = ZonedDateTime.now(ZoneId.of(timezone))
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return "{\"timezone\":\"" + timezone + "\",\"time\":\"" + formatted + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"invalid timezone: " + timezone + "\"}";
        }
    }
}
