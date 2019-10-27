package com.setminusx.ramsey.qm.utility;

import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.UUID;

@Component
public class LoggingInterceptor extends HandlerInterceptorAdapter {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        MDC.put("rqid", ObjectUtils.defaultIfNull(request.getHeader("rqid"), UUID.randomUUID().toString()));
        return true;
    }

}
