package com.company.booking.common;

import java.time.OffsetDateTime;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final WriteRateLimitInterceptor writeRateLimit;

    public WebConfig(WriteRateLimitInterceptor writeRateLimit) {
        this.writeRateLimit = writeRateLimit;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(writeRateLimit).addPathPatterns("/api/v1/bookings/**", "/api/v1/admin/blocks/**");
    }

    /** "...T10:00:00 07:00" is what an unencoded "+07:00" turns into after URL decoding. */
    private static final Pattern SPACE_BEFORE_OFFSET = Pattern.compile("^(.+T[\\d:.]+) (\\d{2}:\\d{2})$");

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, OffsetDateTime.class, source -> {
            String s = source.trim();
            if (s.isEmpty()) {
                return null;
            }
            s = SPACE_BEFORE_OFFSET.matcher(s).replaceFirst("$1+$2");
            return OffsetDateTime.parse(s);
        });
    }
}
