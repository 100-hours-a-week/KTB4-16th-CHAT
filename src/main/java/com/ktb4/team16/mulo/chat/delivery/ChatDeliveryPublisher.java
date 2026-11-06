package com.ktb4.team16.mulo.chat.delivery;

import java.util.List;

/** 커밋된 응답을 전체 태스크로 전파한다. */
public interface ChatDeliveryPublisher {
    void publish(Object response, List<Long> recipients);
}
