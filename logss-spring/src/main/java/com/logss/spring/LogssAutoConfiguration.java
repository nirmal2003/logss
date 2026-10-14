package com.logss.spring;

import io.opentelemetry.api.trace.Span;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/** Registers {@link BodyCaptureFilter} when {@code logss.capture.enabled=true}. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(Span.class)
@ConditionalOnProperty(prefix = "logss.capture", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(LogssCaptureProperties.class)
public class LogssAutoConfiguration {

    @Bean
    public FilterRegistrationBean<BodyCaptureFilter> logssBodyCaptureFilter(LogssCaptureProperties properties) {
        BodyCaptureFilter filter = new BodyCaptureFilter(properties.getMaxBodyBytes(),
                new BodyMasker(properties.getMaskedFields()));
        FilterRegistrationBean<BodyCaptureFilter> registration = new FilterRegistrationBean<>(filter);
        // Outermost, so bodies are recorded even when security or other filters reject the request.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("logssBodyCaptureFilter");
        return registration;
    }
}
