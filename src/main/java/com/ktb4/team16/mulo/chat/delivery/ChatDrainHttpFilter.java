package com.ktb4.team16.mulo.chat.delivery;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 종료 중 새 HTTP 쓰기만 막고 DB 복구 GET은 허용한다. */
@Component
public class ChatDrainHttpFilter extends OncePerRequestFilter {
    private final ObjectProvider<ChatDeliveryLifecycle> lifecycle;

    public ChatDrainHttpFilter(ObjectProvider<ChatDeliveryLifecycle> lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var state = lifecycle.getIfAvailable();
        boolean read =
                request.getMethod().equals("GET")
                        || request.getMethod().equals("HEAD")
                        || request.getMethod().equals("OPTIONS");
        if (state != null && state.isDraining() && !read) {
            response.setStatus(503);
            response.setContentType("application/json");
            response.getWriter()
                    .write(
                            "{\"code\":\"CHAT_DELIVERY_UNAVAILABLE\",\"message\":\"채팅 서버가 연결을 정리하고"
                                    + " 있습니다.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
